# ST Mobile — Implementation Plan

Status: **approved for implementation** · Date: 2026-09-26
Supersedes the exploratory architecture notes for the embedded-runtime path. Grounded in an
on-device prototype (verified on Xiaomi 15 / Android 16 / arm64) and an ecosystem feasibility review.

---

## 1. Locked decisions

| # | Decision | Choice |
| :- | :--- | :--- |
| D1 | Distribution | **Sideload only** (GitHub Releases / F-Droid). No Play Store track. |
| D2 | Node runtime | **Native launcher executable + prebuilt `libnode.so`** (FongMi Node 24.20.0), exec'd from `nativeLibDir`. |
| D3 | SillyTavern delivery | **Bundled in APK** (source + preinstalled `node_modules`), zero-setup first launch, offline. |
| D4 | Local embeddings | **Deferred to v2** (`llama-server` + GGUF). v1 is chat-only. |
| D5 | Identity | `applicationId = app.stmobile`, label **"ST Mobile"**. |
| D6 | Distribution target | **arm64-v8a only** in v1. |
| D7 | App stack | **Kotlin + Jetpack Compose**, Android-only (no cross-platform). |
| D8 | Native settings page | **In scope for v1** (app-level controls; ST's own settings stay in the web UI). |

Consequence of D1: we are not bound by Play policy, but we still follow its hygiene (targetSdk 36,
16 KB alignment, FGS `specialUse`) so nothing is a dead end later.

---

## 2. Goals / Non-goals

**Goals (v1)**
- One APK, one icon. Tap → splash → full-screen SillyTavern. No Termux, no setup, no root.
- Works fully offline for chat when using a local/remote endpoint the user configures; cloud APIs work as normal.
- Node runtime and ST live behind a `ForegroundService` so streaming survives backgrounding.
- User data (chats, characters, settings) survives APK updates and is reachable for backup/restore.
- A native settings page for app-level control (server lifecycle, port, basic auth, backups, logs, updates).
- Reproducible, checksummed builds with pinned upstream versions.

**Non-goals (v1)**
- Local embedding/vectorization (v2).
- Multi-user / server mode / listening on LAN.
- Play Store publishing, x86_64/emulator, armeabi-v7a.
- In-app ST version switching (v2 candidate), browser autolaunch.

---

## 3. Target architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│ app.stmobile — "ST Mobile" APK                                        │
│                                                                      │
│  ┌────────────────────────┐        ┌──────────────────────────────┐  │
│  │  MainActivity (Compose)│        │  NodeService (Foreground,    │  │
│  │  - splash / status      │◄──────►│   specialUse)                │  │
│  │  - WebView (chrome-less)│ Binder │  - extracts payload          │  │
│  │  - file-picker bridge   │        │  - launches node process      │  │
│  │  - back → history       │        │  - notification (Stop)        │  │
│  └───────────┬────────────┘        │  - log rotation               │  │
│              │ HTTP/WS             └───────────────┬──────────────┘  │
│              │ 127.0.0.1:8000                      │ ProcessBuilder  │
│  ┌───────────▼────────────────────────────────────▼──────────────┐   │
│  │ SillyTavern server (Node 24.20.0)                             │   │
│  │  launched as: nativeLibDir/libstnode.so filesDir/st/server.js │   │
│  │  --configPath filesDir/config/config.yaml                     │   │
│  │  --dataRoot   filesDir/data  --browserLaunchEnabled false     │   │
│  └───────────────────────────────┬───────────────────────────────┘   │
│                                   │ fs                                │
│  ┌───────────────────────────────▼───────────────────────────────┐   │
│  │ App-private storage (filesDir)                                 │   │
│  │  st/         ST source + node_modules (extracted, replaceable) │   │
│  │  config/     config.yaml        (survives updates)             │   │
│  │  data/       user state         (survives updates)             │   │
│  │  logs/       node_stdout/stderr, service.log                   │   │
│  └────────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  jniLibs/arm64-v8a: libstnode.so (our launcher, ~few KB)              │
│                     libnode.so    (FongMi Node 24.20.0, ~81 MB)       │
│                     libc++_shared.so (runtime dep)                    │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.1 On-device runtime layout

| Path | Contents | Lifetime |
| :--- | :--- | :--- |
| `nativeLibraryDir/libstnode.so` | Launcher executable (exec'd) | APK-managed |
| `nativeLibraryDir/libnode.so` | Node 24.20.0 shared library | APK-managed |
| `nativeLibraryDir/libc++_shared.so` | C++ runtime | APK-managed |
| `filesDir/st/` | ST source + `node_modules` | replaced on payload change |
| `filesDir/config/config.yaml` | Server config | persists |
| `filesDir/data/` | Chats, characters, worlds, settings | persists |
| `filesDir/logs/` | `node_stdout.log`, `node_stderr.log`, `service.log` | rotated at 10 MB |
| `cacheDir/node_tmp/` | `TMPDIR` for Node | cache |
| `filesDir/st/config.yaml` | **symlink** → `filesDir/config/config.yaml` | recreated |
| `filesDir/st/data` | **symlink** → `filesDir/data` | recreated |

Symlinks keep ST's standalone-mode relative paths working while user data lives outside the
replaceable `st/` tree. If symlinks fail (rare), fall back to pointing `--configPath`/`--dataRoot`
directly (ST accepts both).

### 3.2 Boot sequence

1. `MainActivity.onCreate` → bind `NodeService`, show splash.
2. Service `ACTION_START` → `startForeground()` (specialUse) → `NodePayload.ensureExtracted()`.
3. Extraction (first run or payload version change):
   - verify `nativeLibraryDir/libstnode.so` + `libnode.so` exist;
   - if `st/server.js` missing or payload stamp ≠ manifest → untar `assets/st_bundle.tar` to
     `filesDir/st` via temp dir + atomic rename;
   - create `config/`, `data/`, `logs/`, symlinks;
   - stamp `payload_version` in SharedPreferences.
4. Spawn process (see §3.3). Poll `http://127.0.0.1:8000/` until HTTP 200.
5. WebView loads the URL; splash fades.
6. On `onDestroy`/notification Stop → `process.destroy()` then `destroyForcibly()` fallback.

### 3.3 Process & environment contract

```
exec: nativeLibraryDir/libstnode.so
argv: [libstnode.so, filesDir/st/server.js,
       --configPath, filesDir/config/config.yaml,
       --dataRoot,   filesDir/data,
       --browserLaunchEnabled, false]
cwd:  filesDir/st
env:
  HOME            = filesDir
  TMPDIR/TMP/TEMP = cacheDir/node_tmp
  LD_LIBRARY_PATH = nativeLibraryDir        # resolves libnode.so, libc++_shared.so
  NODE_ENV        = production
  PORT            = 8000                    # overrides config
  ST_ANDROID      = 1                       # optional; ST-friendly flag
```

ST command-line contract verified against docs (`--configPath`, `--dataRoot`,
`--browserLaunchEnabled` are supported standalone-mode flags; env vars `SILLYTAVERN_*` also work).

---

## 4. Node runtime: the native launcher

**Why not the prototype's `dlopen` + `System.loadLibrary`?** It works, but the runtime is not a real
process: `process.execPath`, `child_process`, and `npm` are degraded. **Why not compile Node from
source?** ST-android proves it but it is a ~2–3 h build plus patch maintenance. The launcher gives
us a *real process* on a *prebuilt modern runtime* with almost no build cost.

**Design** (`src/main/cpp/launcher.cpp`):

```cpp
#include <node/node.h>
int main(int argc, char** argv) {
    // node::Start blocks until the runtime exits.
    return node::Start(argc, argv);
}
```

- Built as a **PIE executable** with CMake, linking an imported `libnode.so` (DT_NEEDED).
- Linker flags: `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` (16 KB) and
  `-Wl,-rpath,$ORIGIN`.
- Output renamed to `jniLibs/arm64-v8a/libstnode.so` so AGP extracts it with exec permission.
- `android:extractNativeLibs="true"` + `packaging { jniLibs { useLegacyPackaging = true } }`.

**Validation gate (Phase 1) — must pass before anything else:** exec the launcher, confirm
`process.execPath`, `process.version === v24.20.0`, and that `child_process.spawnSync(process.execPath, ['-e','...'])` works. If this fails, fall back to D2-alt: build Node from source (ST-android pipeline).

**Runtime provenance**: pin `FongMi/nodejs-mobile` tag `v24.20.0-android.2`; verify
`libnode.so` SHA-256 `413a51c0…` and `libc++_shared.so` `d523468d…` in CI. Keep
`third_party/` (manifest, `config.gypi`) for auditability.

---

## 5. SillyTavern payload

**Build-time (CI)** — `ci/build_st_bundle.sh`:
1. Checkout SillyTavern at a pinned tag (target `v1.19.0`; record commit).
2. `npm ci --omit=dev --ignore-scripts` into a staging copy (exclude `.git`, `tests`, `data`, `backups`, `docker`, `colab`).
3. **Audit**: fail if any installed package declares native addons (`binding.gyp` / prebuilds) — v1.19.0 has none, but lock it.
4. `tar` the tree → `src/main/assets/st_bundle.tar` (uncompressed tar; AAPT handles the container).
5. Write `assets/payload_manifest.json` with `st_version`, `st_commit`, `node_version`, bundle sha256.

**config.yaml** (seeded on first run, then user-owned):
```yaml
listen: false
port: 8000
whitelistMode: true
whitelist: ["127.0.0.1", "::1"]
protocol: { ipv4: true, ipv6: false }
browserLaunch: { enabled: false }
git: { backend: builtin }        # isomorphic-git: no git binary needed
extensions: { enabled: true, autoUpdate: true }
enableServerPlugins: false
dataRoot: ./data                 # resolves via symlink to filesDir/data
skipContentCheck: false
logging: { minLogLevel: 2 }      # WARN to keep logs small
```

**First-run seeding**: ship `assets/default_config.yaml`; copy to `filesDir/config/config.yaml` only
if absent. Never overwrite an existing user config on update.

---

## 6. Android app shell

| Component | Responsibility |
| :--- | :--- |
| `MainActivity` (Compose) | Hosts `WebView`; splash/status overlay; navigation to Settings/Logs/About. |
| `SettingsScreen` (Compose) | App-level controls (see §6.1). Writes `config.yaml` / env overrides, then prompts Restart. |
| `NodeService` | `ForegroundService`, `specialUse`; payload extraction; process lifecycle; notification; log rotation. |
| `NodePayload` | Asset extraction, payload versioning, symlink setup, atomic `st/` swap. |
| `AppPaths` | Single source of truth for all paths. |
| `NodeStatus` / listener | `STARTING/RUNNING/STOPPING/STOPPED/ERROR` + message + pid, observed by UI via `Binder`. |
| `WebViewHost` | `WebSettings`, `WebChromeClient.onShowFileChooser` → `ActivityResultContracts.GetContent`, `WebViewClient` errors → status, back → `canGoBack()`. |
| `BackupManager` | Import/export ST user-data archives (`.zip`/`.tar.gz`) via SAF. |
| `LogsScreen` | Tails `filesDir/logs/*`; share via `FileProvider`. |
| `UpdateChecker` | Optional: query GitHub Releases API; prompt (no auto-install in v1). |

### 6.1 Settings page contents

App-level only — SillyTavern's own settings remain in the web UI. Stored app-side (DataStore) and
applied at launch.

| Section | Controls |
| :--- | :--- |
| **Server** | Status (state/pid/version); Start / Stop / Restart; port (default 8000). |
| **Access** | Bind scope (loopback in v1; "Allow LAN" deferred); basic-auth toggle + username/password. |
| **Data** | Data folder info + size; **Backup** (export archive via SAF); **Restore** (import); Reset ST payload (keeps user data). |
| **Diagnostics** | Logs viewer + share; Node version; ST version; payload version/commit. |
| **Updates** | Check for app updates (GitHub Releases). |
| **System** | Battery-optimization opt-in; notification-permission status. |
| **About** | Not-affiliated disclaimer; AGPL-3.0 license; source link; third-party notices. |

**How settings reach the server:** prefer launching with `SILLYTAVERN_*` env overrides (e.g.
`SILLYTAVERN_PORT`, `SILLYTAVERN_BASICAUTHMODE`) so app-managed toggles never clobber the user's
`config.yaml`. The config file stays the user's; the app layers on top.

**Permissions (manifest)**
```xml
INTERNET
FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE
POST_NOTIFICATIONS          <!-- Android 13+ -->
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS  <!-- prompt only, optional -->
```
**Service**
```xml
<service android:name=".NodeService" android:exported="false"
         android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="Runs the local SillyTavern Node.js server" />
</service>
```

**UX details**
- Notification: persistent while running, with a **Stop** action; tapping opens the app.
- Battery-optimization opt-in prompt on first successful start (never forced).
- Splash text reflects real states ("Preparing SillyTavern (first run)…", "Starting…", "Server not responding — check Logs").
- Hardware back → WebView history; back at root → minimize (not kill).

---

## 7. Platform concerns (checklist)

| Concern | Handling |
| :--- | :--- |
| **W^X / exec** | Exec launcher from `nativeLibraryDir` (read-only, executable). Never exec from `filesDir`. |
| **16 KB pages** | `-Wl,-z,max-page-size=16384` on our launcher; `libnode.so` verified 16 KB-aligned; AGP ≥ 8.5.1. |
| **Extraction** | `extractNativeLibs="true"` / `useLegacyPackaging = true` so binaries land on disk executable. |
| **FGS** | `specialUse` type + justification property; **not** `dataSync` (6 h cap on Android 15+). |
| **Doze/background** | FGS + optional `PARTIAL_WAKE_LOCK` only during active streaming (v2 refinement). |
| **Storage** | All app code/data in `filesDir` (private, reliable). No scoped-storage friction. External access via SAF backup/restore. |
| **Loopback security** | ST bound to `127.0.0.1`, `whitelistMode: true`, `browserLaunch` off. `network_security_config` permits cleartext **only** for loopback. Optional `basicAuthMode` offered in Settings. |
| **Notifications** | Runtime `POST_NOTIFICATIONS` request on Android 13+. |
| **ABI** | arm64-v8a only; `abiFilters += "arm64-v8a"`. |
| **APK size** | ~180 MB expected (node 81 MB + ST bundle). Documented; acceptable for sideload. |

---

## 8. Build & CI pipeline

Two equivalent entry points; CI is authoritative.

**Local dev**: `./gradlew assembleDebug` for the shell; `ci/build_st_bundle.sh` + `ci/fetch_node_runtime.sh` to refresh payloads.

**CI (GitHub Actions)** — `.github/workflows/build.yml`:
1. `fetch_node_runtime.sh` — download pinned FongMi release, verify SHA-256, place `jniLibs`.
2. `build_st_bundle.sh` — pinned ST tag, `npm ci --omit=dev --ignore-scripts`, audit, tar, manifest.
3. Build launcher + APK (NDK, CMake 3.22.1, JDK 17, compileSdk 36).
4. Verify: `check_elf_align.py` on launcher + `libnode.so`; assert APK contains expected entries.
5. Sign (encrypted keystore in CI secrets), produce `ST-Mobile-<ver>.apk`, publish an **immutable** release.

**Pinning**: ST tag+commit, FongMi tag+sha256, NDK version, AGP/Gradle/JDK — all recorded in the
artifact's `payload_manifest.json` for reproducibility.

---

## 9. Testing strategy

| Layer | Method |
| :--- | :--- |
| Runtime smoke | `adb` script: install, launch, assert logcat has `server listening`, assert `/api/info` (via WebView console) shows Node ≥ 20. |
| Boot matrix | Cold start; first-run extraction; update (payload change) preserving `config/`+`data/`; Stop/Restart; process-kill recovery. |
| WebView | File upload (character card via picker), back navigation, long chat scroll, dark mode. |
| ST functional | Create character, chat with a mock/echo endpoint, settings persist across restart, backup export/import round-trip. |
| Resilience | Low-memory kill → service restart; app backgrounded during streaming; airplane mode. |
| Device matrix | Primary: Xiaomi 15 (Android 16). Secondary: one Android 10–13 arm64 device. |

---

## 10. Phased roadmap (with exit criteria)

**Phase 0 — Prototype (done).** `libnode.so` loads, Node boots, WebView serves. ✅

**Phase 1 — Runtime launcher (de-risk).** ✅ **DONE (2026-09-26).** `libstnode.so` (22 KB PIE)
exec'd from `nativeLibraryDir` gives a real Node process: `process.version=v24.20.0`,
`execPath=<launcher path>`, and `child_process.spawnSync(process.execPath, …)` returned
status 0 with the child reporting `v24.20.0`. Loopback server reachable (verified via
`adb forward`). **Architecture de-risked — no need for the source-built Node fallback.**
**Exit:** launcher runs a JS file that spawns a child node and prints both versions. ✅

**Phase 2 — ST payload.** ✅ **DONE (2026-09-26).** Bundled SillyTavern 1.19.0
(`7e8663cd`) with `npm ci --omit=dev --ignore-scripts` (native-addon audit: 0 hits) as
`assets/st_bundle.tar`; implemented `AppPaths`, `PayloadManager` (streaming tar extraction,
payload versioning, `config`/`data` symlinks, config seeding), `NodeController`, and the
splash→WebView shell. Verified from a **clean install** (`pm clear`): extraction ~5 s, server
reaches `SillyTavern is listening on IPv4: 127.0.0.1:8000`, and the full onboarding UI renders.
APK = **167 MB**. **Exit:** real ST boots; UI reachable; `config/`+`data/` live outside the
replaceable `st/` tree and persist. ✅

Two lessons (both now encoded):
1. **`skipContentCheck: true` breaks first launch.** ST's content check also seeds first-run
   scaffolding (`data/default-user/settings.json`); skipping it makes `/api/settings/get` 500 and
   the UI hang on "Initializing…". Keep it **false**.
2. **AGP decompresses `.gz` assets during merge**, so a shipped `.gz` was inflated to a 372 MB
   `st_bundle.tar` and then stored verbatim → 387 MB APK. Ship a plain `.tar` and let the
   packaging step deflate it (→ 142 MB in-APK).

**Phase 3 — Foreground service & hardening.** ✅ **DONE (2026-09-26).** Added `NodeService`
(`specialUse` FGS) owning the process, `NodeStatus` model observed via `Binder`, persistent
notification with a **Stop** action, log rotation (10 MB), `POST_NOTIFICATIONS` request, and a
one-time battery-optimization prompt. Verified on device: server returns HTTP 200 **while the app
is backgrounded**; `dumpsys` shows `isForeground=true`, `types=0x40000000` (SPECIAL_USE) and the
notification; `force-stop` leaves **no orphan** `libstnode.so` and stops the server; relaunch
restarts cleanly. **Exit:** ✅

**Phase 4 — Shell polish.** ✅ **DONE (2026-09-26).** Compose shell (D7): Home (WebView + Menu
FAB), Settings page (§6.1: Server / Access / Data / Diagnostics / About), Logs viewer + share
(FileProvider), hardware-back navigation, and the file-picker bridge
(`WebChromeClient.onShowFileChooser` → SAF `GetContent`). Verified on device: ST 1.19.0 renders
full-screen and interactive; Settings opens with live status and correct versions (Node
v24.20.0 · ST 1.19.0); Back returns to the Home WebView. Fixed an **Android WebView `vh`/`dvh`=0
bug** by reloading once after the view is laid out — **no modification to SillyTavern's page or
source**. Interactive file-upload and backup export/import round-trips remain as a manual pass.
**Exit:** core ✅.

**Phase 5 — Release engineering.** ✅ **DONE (2026-09-26).** Added `ci/fetch_node_runtime.sh`
(pinned download + SHA-256 verification), `ci/build_st_bundle.sh` (pinned ST tag, `npm ci`,
native-addon audit, tar + manifest), `ci/check_elf_align.py` (16 KB PT_LOAD check), and
`ci/build_all.sh`; plus `.github/workflows/build.yml` (build, optional signing from secrets,
checksums, tagged release). Identity set to `app.stmobile` / **"ST Mobile"**; release signing
wired via `RELEASE_*` env/properties; `LICENSE` (AGPL-3.0) + `THIRD-PARTY.md` added; `.gitignore`
excludes generated payload/runtime. **Runtime integrity:** the bundle's SHA-256 is now verified
during extraction. Verified locally: all scripts run, `assembleRelease` produces a 153 MB APK,
and a clean install of `app.stmobile` boots ST with no checksum error. **Exit:** ✅ (CI run on
GitHub pending a push).

**Phase 6 — v1.0 acceptance.** Device matrix pass, docs complete, the feasibility review and this
plan updated with outcomes.

**v2 backlog** — `llama-server` embeddings (build llama.cpp CPU-only via NDK; ship as jniLibs
`lib*.so`; `--embeddings`; ST Vector Storage → `http://127.0.0.1:8080/v1`); model manager;
in-app ST version switching; armeabi-v7a; optional DocumentsProvider for external data access.

---

## 11. Risk register

| Risk | Severity | Mitigation |
| :--- | :--- | :--- |
| Launcher exec / `execPath` doesn't behave | High | Phase 1 gate; fallback to source-built Node (ST-android pipeline). |
| AGP strips/omits launcher binary | Medium | Verify APK contents in CI; disable strip for it if needed. |
| ST upstream changes break flags/paths | Medium | Pin ST tag; smoke-test on bump; `payload_manifest.json`. |
| 180 MB APK / download friction | Low | Sideload-only accepted; document size; consider split assets later. |
| Native addon sneaks into `node_modules` | Medium | CI audit fails the build automatically. (Verified clean for ST 1.19.0.) |
| ST first-run scaffolding skipped (`skipContentCheck`) | High | Keep `skipContentCheck: false`; documented in the default config. |
| Android WebView caches `vh`/`dvh` as 0 (collapsed layout) | High | Attach the WebView before loading and reload once after it has a real size; never patch ST's page. |
| FGS killed on aggressive OEMs (Xiaomi) | Medium | Battery opt-in prompt; document per-OEM settings; `START_STICKY`. |
| Loopback exposed to other apps | Low–Med | whitelist + loopback bind; optional basic auth. |
| Licensing (AGPL/mixed) | Medium | See §12. |

---

## 12. Licensing

- **SillyTavern is AGPL-3.0**; bundling it makes the whole distributed app subject to AGPL-3.0
  (source availability, license notice). Sideload-only makes compliance straightforward.
- **Node.js** MIT; **libc++** Apache-2.0 w/ LLVM exception; **FongMi/nodejs-mobile** MIT.
- **Consequence:** ship `LICENSE` (AGPL-3.0), a `THIRD-PARTY` notice, and a public source link in
  the app (About screen). Do not use ST branding in the app name/icon; keep the "not affiliated"
  disclaimer.

---

## 13. Open questions (resolve during phase 1–2)

1. Launcher exec semantics on Android 10–13 (device matrix in Phase 1).
2. Whether to embed `npm` for future custom ST installs (ST-android bundles `npm.tar`; defer).
3. Wake-lock strategy for v2 embeddings (CPU-bound indexing).
4. Backup format/UX for non-technical users.

---

## 14. References

- On-device prototype results: this plan's phase notes (see §10).
- Background material (original architecture concept and the ecosystem feasibility review) is kept
  outside this repository, in the project's `notes/` folder.
- Prior art (AGPL-3.0, study-only — do not copy): `Sanitised/ST-android` — `NodeService.kt`,
  `NodePayload.kt`, `AppPaths.kt`, `build_st_bundle.sh`, manifest (exec-from-`nativeLibDir`,
  `specialUse` FGS, payload versioning).
- ST CLI/config contract: docs.sillytavern.app → Configuration File.
- llama.cpp embeddings (v2): `--embeddings`, `POST /v1/embeddings` (pooling ≠ none).
- Runtime: `FongMi/nodejs-mobile` v24.20.0-android.2.
