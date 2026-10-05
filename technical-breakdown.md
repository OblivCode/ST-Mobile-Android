# ST Mobile: technical breakdown

A reference for how ST Mobile is built and how it runs. The high-level map is in
[`architecture.md`](architecture.md). This document goes a level down: what the pieces are, what the
app hands the operating system at launch, how the build works, and why each choice was made.


---

## 1. What it is

ST Mobile is an Android app that runs [SillyTavern](https://github.com/SillyTavern/SillyTavern)
entirely on the phone, with nothing else installed and nothing configured. One APK, no Termux, no
laptop, no server.

SillyTavern is a Node.js web application. Normally you run it on a computer, then open a browser to
`http://localhost:8000`. ST Mobile does the same thing, but the computer is the phone: it embeds a
Node runtime, bundles a copy of SillyTavern, starts the server as a background process, and points a
WebView at the local address.

Everything stays on the device. The app makes no network calls except loopback.

**Current state:** working end to end and verified on a physical device (Xiaomi 15, Android 16,
arm64). It is a prototype, not a released product. Section 9 lists what is still open.

## 2. Architecture at a glance

```
 ST Mobile (one APK)
 ┌──────────────────────────────────────────────────────────────┐
 │  Compose UI (MainActivity)                                   │
 │    Setup / Dashboard / Settings                              │
 │    └─ WebView ──── HTTP ────►  http://127.0.0.1:<port>       │
 │                                                              │
 │  NodeService (specialUse foreground service)                 │
 │    └─ NodeController                                         │
 │         └─ exec ─► libstnode.so ─► libnode.so ─► SillyTavern │
 │                     (launcher)      (Node 24)    (payload)   │
 │                                                              │
 │  Assets: st_bundle.tar, default_config.yaml, manifest        │
 └──────────────────────────────────────────────────────────────┘
```

Two things are worth noticing straight away:

- The server is a **separate process**, launched by a tiny native program, not a thread inside the
  app. That is a deliberate choice and section 7 explains it.
- The SillyTavern code ships **inside the APK** and is unpacked to app storage on first run. The app
  never downloads it.

## 3. Components

All paths are relative to the repository root. The Gradle module is the repository root itself, so
there is no `app/` directory. Source lives under `src/main/java/app/stmobile/`.

| File | Role |
| :--- | :--- |
| `src/main/cpp/launcher.cpp` | The entire launcher: calls `node::Start()` and returns its exit code. |
| `src/main/cpp/CMakeLists.txt` | Builds the launcher against the prebuilt runtime and sets 16 KB alignment. |
| `MainActivity.kt` | Lifecycle coordinator, pure screen routing (`computeNextScreen`, `computeBackScreen`), backstack tracking, and file-picker contracts. |
| `StApplication.kt` | Tracks app foreground/background with a 1 second debounce so rotation does not count as leaving. |
| `AppPaths.kt` | Single source of truth for every filesystem path. |
| `Utils.kt` | Battery-optimisation prompt (with OEM fallbacks), notification permission, log sharing, toasts. |
| `models/AppConfig.kt` | App settings in SharedPreferences: auto-start, background timeout, port fallback, basic auth. |
| `models/StConfig.kt` | Two-way SnakeYAML sync for `config.yaml`, preserving keys the app does not know about. |
| `models/NodeConfig.kt` | V8 memory ceiling (`--max-old-space-size`, clamped to 256 to 4096 MB), timezone, extra runtime options. |
| `models/NodeStatus.kt` | Lifecycle state enum and the status record shared with the UI. |
| `node/NodeService.kt` | Foreground service that owns the server process, its status flow, and its lifecycle. |
| `node/NodeController.kt` | Builds the launch command, starts and stops the process, rotates logs. |
| `node/PortResolver.kt` | Probes the port on loopback and falls back to a kernel-assigned one if it is taken. |
| `node/BackgroundTimeoutManager.kt` | Idle countdown that stops the server after the app has been backgrounded for N minutes. |
| `sillytavern/PayloadManager.kt` | Extract, verify, version, and swap the bundled SillyTavern. |
| `sillytavern/BackupManager.kt` | Export and import user data (ZIP, optional AES-256), pre-flight inspection, clean or merge restore. |
| `ui/` | Compose screens: `SetupScreen`, `DashboardScreen`, `SettingsScreen`, `StWebView` (with edge-docked quick toolbar and gesture exclusion rects), `BackupDialogs`, `MainScreen`, `Navigation`. |

**Build configuration** (`build.gradle.kts`): AGP 8.13.0, Kotlin 2.0.21, Jetpack Compose, Java 17,
`compileSdk` 35, `minSdk` 26, `targetSdk` 35, NDK 25.2.9519653, CMake 3.22.1, and
`abiFilters = arm64-v8a`.

## 4. The runtime contract

This is what `NodeController.start()` actually hands the operating system. Everything SillyTavern
needs to know about its new home is here.

**Program** (`argv[0]`): `nativeLibraryDir/libstnode.so`, the launcher.

**Arguments**, in this order (Node and V8 options must come before the script):

| Argument | Value | Why |
| :--- | :--- | :--- |
| `--max-old-space-size` | `NodeConfig` value, default 1024 MB | Caps the JavaScript heap so the phone is not starved. |
| (extra options) | from `NodeConfig`, if any | Passed through to Node. |
| (entry file) | `filesDir/st/server.js` | Tells Node which script to run. |
| `--configPath` | `filesDir/config/config.yaml` | Points at the user's config, outside the replaceable tree. |
| `--dataRoot` | `filesDir/data` | Points at the user's data, outside the replaceable tree. |
| `--browserLaunchEnabled` | `false` | There is no desktop browser to open. |
| `--port` | the effective port (see below) | The port the server listens on. |

**Port.** The target comes from `config.yaml` (default `8000`). `PortResolver` tries to bind it on
`127.0.0.1` with `SO_REUSEADDR`, so a socket lingering in `TIME_WAIT` is not mistaken for a conflict.
If it is taken and auto fallback is on, the kernel is asked for a free port instead. If fallback is
off, the start fails cleanly with an error status instead of crashing later. The effective port is
what appears in the status the UI reads.

**Working directory:** `filesDir/st`. SillyTavern builds many of its paths from the process working
directory, so it must be its own tree and not the app's.

**Environment:**

| Variable | Value | Why |
| :--- | :--- | :--- |
| `LD_LIBRARY_PATH` | `nativeLibraryDir` | How the launcher finds `libnode.so` at runtime. |
| `HOME` | `filesDir` | Gives Node a sane home for caches and dotfiles. |
| `TMPDIR`, `TMP`, `TEMP` | `cacheDir/node_tmp` | Writable scratch space, kept off the main storage. |
| `NODE_ENV` | `production` by default | Skips development behaviour. |
| `TZ` | the device timezone | Stops the runtime defaulting to UTC. |
| `SILLYTAVERN_PORT` | the effective port | Belt and braces alongside `--port`. |
| `ST_ANDROID` | `1` | Marker so the environment is identifiable. |
| `SILLYTAVERN_BASICAUTHMODE` and friends | only when enabled | Applies basic auth without editing the config file. |

**Output:** stdout and stderr are redirected to `filesDir/logs/node_stdout.log` and
`node_stderr.log`. Each file rotates to a `.1` backup once it passes 10 MB, so logs stay bounded on
a phone.

## 5. On-device layout

```
filesDir/                       (app private storage)
├── st/                         replaceable SillyTavern tree, extracted from the APK
│   ├── server.js               entry point
│   ├── config.yaml             symlink -> ../config/config.yaml
│   └── data                    symlink -> ../data
├── config/
│   └── config.yaml             user config, seeded once, never overwritten
├── data/                       user data: chats, characters, settings
├── logs/
│   ├── node_stdout.log
│   ├── node_stderr.log
│   └── *.log.1                 rotated prior file
└── tmp/                        staging for the atomic st/ swap (st_new, st_old) and backup exports

cacheDir/
└── node_tmp/                   TMPDIR for the Node process

nativeLibraryDir/               read-only, extracted from the APK, executable
├── libstnode.so                the launcher
├── libnode.so                  the Node runtime
└── libc++_shared.so            the C++ standard library both link against
```

The split between `st/` and `config/` + `data/` is the important part. `st/` is disposable and gets
replaced on updates. `config/` and `data/` are the user's and never move. The two symlinks inside
`st/` exist because SillyTavern, run standalone, expects them in its own tree.

During a clean restore, a temporary staging folder (`.restore_staging_*`) and a rollback copy
(`.data_rollback_*`) are created inside `filesDir`, next to `data/`. They sit beside it on purpose,
so the final swap is a same-filesystem rename instead of a slow copy.

## 6. Startup sequence

1. You open the app. `MainActivity` asks `PayloadManager` whether the bundled SillyTavern still needs
   unpacking. If so, the setup screen runs first (a fresh unpack, or a restore from a backup
   followed by the unpack). Otherwise it goes to the dashboard. The server starts when you tap
   Start, or automatically if auto-start is on or the app was killed while the server was running.
2. `NodeService.start()` promotes the service to the foreground, which posts its notification, and a
   worker thread takes over.
3. The worker calls `PayloadManager.getExistingLayout()`, which makes sure the folders exist, seeds
   `config.yaml` if it is absent, and recreates the symlinks. It then loads the app, Node, and
   SillyTavern configs.
4. `NodeController.start()` resolves the port and launches the launcher with the arguments from
   section 4.
5. The service itself then probes `http://127.0.0.1:<port>`, up to 120 times, 250 ms apart. Any
   response from 200 to 499 counts as ready. If the process dies, or nothing answers within about 30
   seconds, the status becomes `ERROR`.
6. The service reports `RUNNING` with the process id and port. If the app is in the background at
   that moment, the idle timeout is armed. A separate supervisor thread waits for the process to
   exit.
7. The UI observes a process-level status flow, so it does not bind to the service. When the state transitions to `RUNNING`, `computeNextScreen` automatically switches the view to `WEBVIEW` if `autoLaunchWebViewOnStart` is enabled (the default), while leaving the user uninterrupted if currently navigating `SETTINGS` or `SETUP`.
8. The WebView waits until it has a real size before loading, so SillyTavern lays out against a correctly sized viewport. It then reloads once after first paint, which fixes a quirk where viewport units resolve to zero on the first pass. When another screen is shown, the WebView is hidden (`View.INVISIBLE`) but kept alive, so pages and chat state are not lost. An edge-docked menu handle provides a quick toolbar for jumping to Dashboard, Reload, or Settings without disrupting browser state, registering Android system gesture exclusion rects (`Modifier.systemGestureExclusion()`) against edge-swipe false triggers. Navigating to Settings records the previous screen so pressing Back returns directly to the active WebView.
9. When the process exits, the supervisor reports `STOPPED` if a stop was requested or the exit code was 0, and `ERROR` with the exit code otherwise, then stops the service.

**Background timeout.** When the app has been out of sight for the configured number of minutes (default 5, `0` disables it), the service stops the server and removes its notification. Coming back to the app cancels the countdown. The service is `START_NOT_STICKY`, so Android does not quietly revive Node while the phone is locked. If the system did kill the app while the server was running, the app notices on the next launch and starts the server again.

**Soft keyboard & edge-to-edge insets.** The app runs edge-to-edge (`enableEdgeToEdge()`). Scroll containers in `SettingsScreen` and `SetupScreen` apply `Modifier.imePadding()` before vertical scrolling, ensuring soft keyboards resize the scroll viewport so input fields (like idle timeouts and restore passwords) are never obscured.

## 7. Decisions and why

**A separate process, not a thread.** The alternative was to load the runtime into the app with JNI
and call `node::Start()` on a thread. That saves a little packaging and memory, but it means a crash
in Node (a memory fault, an out-of-memory in the JavaScript engine) takes the whole app with it. In
its own process, Node can die and the app survives.

**The launcher ships as a library.** Android will not let an app execute a binary from its own
writable storage. It will run the shared libraries that ship inside the APK, which it extracts to a
read-only, executable folder at install time. So the launcher is built as an executable and named
`libstnode.so`, which makes the packaging step treat it as a native library and put it where the app
can run it.

**`useLegacyPackaging = true`.** This forces the native libraries to be extracted to
`nativeLibraryDir` at install time instead of being loaded straight from the APK. That extraction is
what puts the executable bit on the launcher.

**A plain `.tar`, not `.tar.gz`.** The Android build tools decompress `.gz` assets during merging,
which inflates the APK again before it is repackaged. Shipping a plain tar lets the normal APK
compression do the work once. Related: `tar` must **not** be added to `noCompress`.

**Config seeded once, then never touched.** The app copies `assets/default_config.yaml` into place
only if `config/config.yaml` does not already exist. Any app level toggle (port, basic auth) is
passed as an environment override instead of being written into the user's file. When the app does
edit the file, it keeps keys it does not understand, and writes through a temporary file and an
atomic move.

**Symlinks for `config` and `data`.** They keep user state outside the tree that updates replace. If
symlinks are unavailable, the `--configPath` and `--dataRoot` arguments still point at the real
locations, so the design degrades safely.

**`specialUse` foreground service.** A data sync foreground service is capped (6 hours in a 24 hour
window on Android 15 and later). `specialUse` has no such cap, which matters for something that is
meant to run for as long as the user wants it to.

**Backups restore safely.** A clean restore extracts into a staging folder first, moves the current
data aside, swaps the new data in, and only then deletes the old copy. If anything fails midway, both
`data/` and `config.yaml` are put back. Every archive entry is checked so that nothing can be written
outside the target folder, including paths written with Windows separators.

**arm64 only.** The bundled runtime targets `arm64-v8a`. Shipping a second ABI would roughly double
the native payload for no practical gain on current hardware.

**Everything is checksummed.** The runtime download, the bundled library files, and the SillyTavern
payload are all verified by SHA-256, at fetch time or at extract time. A build either reproduces the
pinned bytes or fails loudly.

## 8. Build pipeline

Three steps, in order.

**1. Fetch the Node runtime** (`ci/fetch_node_runtime.sh`). Downloads one pinned release archive,
verifies it against a hardcoded SHA-256, then copies `libnode.so` and `libc++_shared.so` into
`src/main/jniLibs/arm64-v8a/`, the headers into `src/main/cpp/node-include/`, and the provenance
files into `third_party/`. It verifies the individual library hashes again after copying.

Pinned: a community `nodejs-mobile` build of Node `v24.20.0` (NDK r27d). The exact release tag and
hashes live in the script.

**2. Build the SillyTavern bundle** (`ci/build_st_bundle.sh`). Clones the pinned tag and checks out
the exact commit, installs production dependencies with `npm ci --omit=dev --ignore-scripts`, and
audits the installed packages for native addons (`binding.gyp`, `*.node`, `prebuilds`). If any are
found it fails the build, because the embedded runtime cannot compile them. It then stages the tree
(minus `.git`, tests, backups, data, and CI or editor folders), tars it to
`src/main/assets/st_bundle.tar`, and writes `payload_manifest.json` with the version, commit, runtime,
and the bundle's SHA-256.

Pinned: SillyTavern `1.19.0`, commit `7e8663cd9c184a550b37238218bdd32c6efc68e9`.

**3. Build the APK** (`./gradlew assembleDebug` or `assembleRelease`). Gradle builds the launcher
with CMake, packages the assets and native libraries, and produces the APK.

`ci/build_all.sh` runs all three in sequence for local use.

**What extraction does at run time** (`PayloadManager`): streams the tar out of the assets while
computing SHA-256 over the raw bytes, refuses the result if the hash does not match the manifest,
rejects any archive entry whose canonical path escapes the target folder, extracts into `tmp/st_new`,
preserves the executable bit from the tar entry's mode, and finally swaps the tree into place with a
rename (`tmp/st_old` holds the previous one until the swap succeeds). A failure at any point leaves
the previous tree intact.

**Signing.** Release signing credentials come from `RELEASE_*` environment variables or Gradle
properties, or from a git-ignored `keystore.properties` for local builds. If none are present,
`assembleRelease` produces an unsigned APK. `versionName` is left unset until a first public
release, so the app reports its `versionCode` instead.

**CI** (`.github/workflows/build.yml`): on push to `main` and on `v*` tags, it installs the SDK, NDK,
and CMake, caches the two `/tmp` payload directories keyed on the script hashes, runs the two fetch
or build steps, builds debug and release APKs, verifies 16 KB ELF alignment, writes a checksum file,
and uploads everything as an artifact. On a `v*` tag it also publishes a GitHub release.

**16 KB alignment.** Android 15 and later require native libraries to be aligned to 16 KB pages. The
launcher sets this in `CMakeLists.txt`, the prebuilt runtime is already aligned, and CI checks every
library with `ci/check_elf_align.py`.

## 9. Known gaps and next steps

- **CI is unconfirmed.** The workflow has been reworked since its early failures, but a green run has
  not been confirmed here. Check the Actions tab before relying on it.
- **No emulator or device tests.** The host JVM unit tests cover the logic that does not need
  Android. Nothing yet exercises the real runtime, loopback networking, or asset extraction on a
  device or emulator; that suite is planned.
- **arm64 only.** No x86_64 build, so a standard x86 emulator will not run it.
- **Signing is opt-in.** Without credentials, `assembleRelease` produces an unsigned APK.
- **`versionCode` defaults to 1** outside CI, where it comes from the run number.
- **Leftover restore folders.** If the app is killed outright in the middle of a restore, the
  `.restore_staging_*` or `.data_rollback_*` folder can be left behind. A cleanup sweep at startup is
  planned.
- **No in-app update path** beyond a payload version change: a new SillyTavern means a new APK.
- **Distribution is not set up yet:** release publishing, F-Droid metadata, and Play Store size and
  service-policy review are all still to do.

## 10. Glossary

- **ABI**: the CPU architecture a native binary targets. Here, `arm64-v8a` (64 bit ARM).
- **APK**: the installable Android package.
- **Native addon**: a Node package that includes compiled C or C++, rather than plain JavaScript.
  Cannot be built by the embedded runtime, so the build rejects them.
- **NDK**: Android's toolchain for building native code.
- **`.so` (shared object)**: the Linux equivalent of a DLL. Code loaded by a program at run time.
- **JNI**: the bridge that lets Kotlin or Java call into C or C++. Not used in the shipped design,
  but it was the alternative that was evaluated.
- **Foreground service**: an Android service that keeps running with a visible notification.
- **StateFlow**: a Kotlin observable value. The service publishes its status through one, and the UI
  collects it.
- **W^X**: the security rule that a page of memory cannot be both writable and executable. It is why
  the launcher runs from the read-only `nativeLibraryDir` rather than writable app storage.
- **WebView**: the Android component that renders web pages; it displays SillyTavern's UI.
- **16 KB alignment**: page size requirement for native libraries on Android 15 and later.
