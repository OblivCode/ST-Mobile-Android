# Feasibility Deep-Dive: Headless Termux vs Embedded `libnode.so`

Verified against the ecosystem as of **September 25, 2026**. Claims are marked ✅ verified, ⚠️ true-with-caveats, ❌ outdated.

**Terminology.** *"Doc B" / "Approach 1" / embedded `libnode.so`* = the implemented **`app-native`**.
*"Doc A" / "Approach 3" / headless Termux* = the reserved alternate approach in **`app-termux`**
(outside this repository).

Method: each load-bearing assumption in [`CONCEPT.md`](CONCEPT.md) and the headless-Termux concept
document was checked against current primary sources (Google official docs, Termux package repo,
nodejs-mobile forks, SillyTavern `release` branch `package.json`, and the existing ST-android app).

---

## 0. Verdict Summary

| # | Claim | Doc | Verdict |
| :-- | :---- | :-- | :------ |
| 1 | Termux publishes per-arch bootstrap zips | A | ✅ Fresh builds monthly (`bootstrap-2026.08.16-r1+apt.android-7` etc.) |
| 2 | Bootstrap contains node / llama-server / git | A | ⚠️ **No** — bootstrap is only the minimal rootfs (bash, coreutils, apt). Runtimes must be pulled as separate `.deb`s from the Termux apt repo and merged |
| 3 | Termux ships prebuilt `llama.cpp` | A | ✅ `llama-cpp` v0.5.0, MIT, arm64-only, CPU+Vulkan+OpenCL, includes `llama-server` |
| 4 | Termux node is Bionic-linked & current | A | ✅ Node **26.4.0** — but needs shared `.deb` deps (libc++, openssl, c-ares, libicu, libsqlite, zlib, libffi); npm split into its own package since node 25.3.0 |
| 5 | `targetSdk 28` enables `execve()` from `filesDir` | A | ✅ Officially confirmed: W^X restriction begins at targetSdk 29 |
| 6 | `targetSdk 28` is viable for distribution | A | ⚠️ Sideload-only. Android 15/16 install floor is targetSdk 24, so 28 still installs — but Google "expects to increment" the floor over time |
| 7 | Play Store requires modern targetSdk | A/B | ❌→✅ Worse than doc assumed: new apps/updates must target **API 36** since Aug 31, 2026 |
| 8 | Native git is what makes self-updates work | A | ⚠️ **Weakened** — SillyTavern now bundles `isomorphic-git` (pure JS); native git is a nice-to-have, not load-bearing |
| 9 | nodejs-mobile provides `libnode.so` prebuilts | B | ✅✅ Rehabilitated: community forks actively ship **Node 24**-class runtimes (`digidem` v24.18.0, `FongMi` v24.20.0 with 16KB page alignment, NDK r27d) |
| 10 | Upstream Node cannot be built for Android without Termux patches | B | ✅ Confirmed hard (nodejs/node#34115) — but moot if using the fork prebuilts |
| 11 | `sharp` is a native-C++ problem | B | ❌ **Outdated** — SillyTavern v1.19.0 removed `sharp`; images are handled by pure-JS/WASM `@jimp/*` |
| 12 | No self-update story without native git | B | ❌ Resolved by `isomorphic-git` in ST's dependencies |
| 13 | Executables via `jniLibs` + `lib*.so` naming works on modern targets | B | ✅ Proven in production by ST-android (`targetSdk = 36` + `jniLibs { useLegacyPackaging = true }`) |
| 14 | ForegroundService + wake locks, indefinitely | Both | ⚠️ Android 14 requires FGS **type** declarations; Android 15 caps `dataSync` at **6 h/24 h** — `specialUse` is the correct type |
| 15 | SillyTavern needs Node ≥ 18 | Both | ⚠️ Current `engines` is **`node >= 20`** (old nodejs-mobile 16/18 builds are dead ends; Node 24 forks comply) |
| 16 | llama.cpp builds fine for arm64-v8a via NDK | B | ✅ Officially supported (llama.cpp docs; Termux and ST-android both do it) |

---

## 1. Runtime Sourcing

### 1.1 Termux bootstrap & packages (Doc A)

- **Bootstrap zips** are published on `termux/termux-packages` GitHub releases on a rolling basis and mirrored on SourceForge. Verified: `bootstrap-2026.08.16-r1+apt.android-7` with per-arch assets.
- **Correction to Doc A §3:** the bootstrap zip alone does **not** contain `node`, `llama-server`, or `git`. The asset bundle must be assembled as *bootstrap rootfs + extracted `.deb`s from the Termux apt repo* (`nodejs` 26.4.0, `llama-cpp` 0.5.0, plus node's shared-library deps). This is mechanical but must be scripted; npm is now a separate package (`pkg install npm` equivalent).
- **`llama-cpp` package quality is high**: auto-updated, MIT, dynamically loads CPU/Vulkan/OpenCL backends (`GGML_BACKEND_DL`). For embedding-only use, the CPU backend suffices; Vulkan/OpenCL backends can be omitted from the bundle to save space.
- **Licensing:** termux-app (incl. `termux-shared` classes like `TermuxService`) is **GPLv3**. Reusing its Java classes makes the APK a GPLv3 derivative. The *binaries* are a mix: node MIT, llama.cpp MIT, bash/git/coreutils GPLv3. Doc A's GPLv3 tax is real but shrinkable (see §4 hybrid).

### 1.2 nodejs-mobile (Doc B)

- Original JaneaSystems project is dead (last maintained release was Node 16/18-era, Chakracore-based).
- **The community org (`github.com/nodejs-mobile`) carries it forward**, and active forks publish modern runtimes:
  - `digidem/nodejs-mobile` — Node **24.18.0**, upstream version string preserved, mobile build flagged via `process.versions.mobile`.
  - `FongMi/nodejs-mobile` — Node **24.20.0**, Android-only stripped runtime, **API 24+, NDK r27d, 16 KB ELF alignment (0x4000)**, arm64-v8a + armeabi-v7a.
- Consequence: Doc B's Option 1 is no longer "stuck on Node 18" — you can pin a Node 24-class prebuilt and satisfy SillyTavern's `node >= 20` engine requirement.

### 1.3 Direct precedent: `Sanitised/ST-android`

A shipping app that already proves most of Doc B's plan (AGPL-3.0, 128★, sideloaded from GitHub releases):

- Bundles unmodified SillyTavern source + **Node.js with minimal Android patches**, compiling upstream node from source in CI (Docker, ~2–3 h first build).
- Gradle: `minSdk 26`, **`targetSdk 36`**, `compileSdk 36`, and `packaging { jniLibs { useLegacyPackaging = true } }` — the exact W^X-compliant exec mechanism.
- Supports Android 8.0+ arm64, one-click run, data import/export, arbitrary ST version/branch/ZIP installs.
- Gaps it leaves open: no local `llama-server` embedding daemon, and "extensions are not properly supported yet."
- It is a *runner*, not affiliated with SillyTavern — but it is prior art worth studying (or forking) before building either plan.

---

## 2. Android Platform Constraints (both docs)

| Constraint | Rule | Impact |
| :--- | :--- | :--- |
| W^X / `execve()` | targetSdk ≥ 29 may not exec from the writable app home dir (official Android 10 behavior change). Binaries shipped inside the APK as native libs are extracted to the read-only `nativeLibraryDir` and remain executable | Doc A's targetSdk-28 trick works for sideload; **everyone else** must use `jniLibs` + `useLegacyPackaging = true` (proven by ST-android) |
| Sideload install floor | Android 14: ≥ targetSdk 23; Android 15/16: ≥ 24 (bypass only via ADB) | Doc A's 28 survives today; expect the floor to ratchet |
| Play targetSdk | Since Aug 31, 2026: new apps/updates must target **36**; existing apps must target **35** to stay visible | Any Play ambition forces the jniLibs route |
| FGS types | Android 14+ requires `foregroundServiceType` | Declare `specialUse` (with justification string) — `dataSync` is killed after 6 h/24 h on Android 15+ |
| 16 KB pages | New Play apps targeting Android 15+ must ship 16 KB-aligned native libs (Nov 2025+) | Self-built or forked `libnode.so` must be 16 KB-aligned; FongMi's build already is; Termux binaries need checking |
| Localhost scope | `127.0.0.1` on a phone is device-wide — **any** app can reach ports 8000/8080 | Enable ST's basic auth (`basicAuthMode: true` + credentials) in both plans; Doc A is currently silent on this |

---

## 3. SillyTavern Reality Check (`release` branch, v1.19.0)

From `package.json`:

- **`engines: node >= 20`** — floors both plans on runtime currency.
- **`sharp` is gone.** Image handling now uses `@jimp/*` v1 with WASM codecs (`wasm-avif`, `wasm-jpeg`, `wasm-png`, `wasm-webp`). Doc B's dependency-audit row about native C++ image compression is obsolete — no native npm deps remain in the runtime dependency list.
- **`isomorphic-git` ^1.36.3 is bundled** (plus `simple-git` for environments that have native git). Self-updates without a git binary are a supported path. Doc A's "native git just works" advantage is real but no longer decisive.
- `sillytavern-transformers` 2.14.6 runs **server-side** (in the Node process), not in the WebView — so the "browser crashes under Transformers.js" argument in both docs is outdated framing; the real advantage of `llama-server` is speed and process isolation, not browser crash avoidance.
- ST is **AGPL-3.0** — bundling unmodified ST is fine with source availability; ST-android demonstrates the pattern.
- Official docs include an **"Android (Termux) Installation"** page (git clone + `start.sh` + `git pull --rebase --autostash` updates) — the Termux runtime path for ST is officially exercised.

---

## 4. Re-scored Comparison (supersedes Doc A §8)

| Dimension | Doc B — Embedded `libnode.so` | Doc A — Headless Termux |
| :--- | :--- | :--- |
| Runtime currency | ✅ Node 24 via community forks | ✅ Node 26.4.0 in Termux repo |
| NDK compilation work | ✅ Avoidable (fork prebuilts) | ✅ Avoidable (Termux `.deb`s) |
| Self-updates | ✅ `isomorphic-git` (was doc B's biggest gap) | ✅ Native git (marginally more robust) |
| W^X strategy | ✅ jniLibs, Play-proven | ⚠️ targetSdk 28 sideload-only; jniLibs would require repackaging the whole rootfs as `lib*.so` |
| Play Store viability | ✅ Proven pattern (ST-android @ targetSdk 36) | ❌ Only via awkward repackaging + GPLv3 |
| APK footprint | ✅ ~50–70 MB | ❌ ~110–150 MB (full distro) |
| Licensing | ✅ MIT only (node, llama.cpp) | ⚠️ GPLv3 bash/git/coreutils + GPLv3-derived Termux Java classes |
| Build pipeline | ⚠️ Pin + track a fork's release cadence | ✅ Script pulls current `.deb`s |
| FGS longevity | Both: `specialUse` type, avoid `dataSync` | Same |
| 16 KB pages | ✅ Fork builds provide it | ⚠️ Must verify Termux binaries are 16 KB-aligned before Play distribution |

**Net effect of the deep-dive:** the gap between the two approaches narrowed substantially.
Doc A's two headline advantages (native git self-updates, zero-compile llama.cpp) are one-half
neutralized (isomorphic-git) and one-half confirmed; Doc B's two headline weaknesses (stale
nodejs-mobile, sharp) are both resolved upstream or in forks.

---

## 5. The Hybrid That Falls Out of the Data

Neither doc considers a minimal synthesis, which the evidence now supports:

> **Ship only the binaries you need as `jniLibs`:** `node` (+ its shared libs: `libicu`, `libssl`,
> `libsqlite`, `libc++`, `libz`, `c-ares`, `libffi`), `llama-server` (+ `libggml-*`), and the CA
> cert bundle — renamed `lib*.so`, extracted via `useLegacyPackaging = true`, exec'd from
> `nativeLibraryDir`. SillyTavern source unpacks to `filesDir` as in both docs. **No bash, no git,
> no coreutils, no full Termux rootfs.**

Properties of this hybrid:
- Play-compliant targetSdk 36 (ST-android proves the packaging trick).
- GPLv3 exposure drops to ~zero (all shipped binaries MIT; no bash/git/coreutils).
- Footprint between the two originals (~60–90 MB).
- Updates via ST's bundled `isomorphic-git`; binaries refreshed only on app updates.
- Termux remains just the *source* of prebuilt binaries — extract `.deb`s in CI, repackage as `lib*.so`.

The remaining genuine fork is distribution channel:
- **Sideload/GitHub only** → Doc A as written (targetSdk 28) is the lowest-effort path and remains legal today.
- **Play Store or broad distribution** → Doc B or the hybrid; Doc A's targetSdk-28 trick is a dead end on Play regardless of anything else.

---

## 6. Open Items (not fully verified)

1. **16 KB page alignment of current Termux binaries** — must be checked if any jniLibs-style Play distribution is attempted.
2. **ST-android's exact node patches** — review `Sanitised/ST-android` `ci/` scripts and node submodule before compiling anything yourself.
3. **`specialUse` FGS review posture on Play** — justification required at review time; sideload needs no review.
4. **ST extension surface on-device** (server plugins, UI extensions that spawn processes) — untested in both docs.
5. **Model acquisition UX** (bundle vs first-boot download of the ~35–45 MB embedding GGUF) — undecided in both docs.

---

## 7. Sources (primary)

- developer.android.com — Android 10 behavior changes (W^X), FGS types (14), FGS timeouts (15), Play target API requirements (2026).
- `termux/termux-packages` — releases (bootstrap zips), `packages/nodejs/build.sh` (v26.4.0), `packages/llama-cpp/build.sh` (v0.5.0).
- `nodejs-mobile` org — `digidem/nodejs-mobile` (Node 24.18.0), `FongMi/nodejs-mobile` (Node 24.20.0, 16 KB alignment).
- `SillyTavern/SillyTavern` release branch — `package.json` (v1.19.0, engines ≥ 20, isomorphic-git, jimp).
- `Sanitised/ST-android` — README + `app/build.gradle.kts` (targetSdk 36, useLegacyPackaging).
- docs.sillytavern.app — official Termux install/update guide.