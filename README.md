# ST Mobile

SillyTavern on Android in a single APK. No laptop, no remote server, no Termux, no root, and no complex manual setup.

SillyTavern normally runs on a computer under Node.js. ST Mobile embeds that runtime directly into an Android application, manages it as a supervised local subprocess, and presents the full interface in a native, state-preserving WebView shell. Everything stays private, isolated, and offline on the device.

---

## Key Features

- **Embedded Node.js 24 Runtime:** Runs real Node.js directly on Android using a native ELF launcher (`libstnode.so`) linked against `libnode.so` and `libc++_shared.so`.
- **Zero-Reload WebView Host & Edge-Docked Quick Toolbar:** Uses a custom Jetpack Compose WebView coordinator that preserves browser state (`View.INVISIBLE`) when switching between screens. Features an edge-docked quick toolbar with Android system gesture exclusion rects (`Modifier.systemGestureExclusion()`) providing one-tap access to Dashboard, Reload, and Settings without triggering accidental edge back-swipes.
- **Edge-to-Edge Window Insets & Soft Keyboard Adaptation:** Built strictly against modern edge-to-edge contracts (`enableEdgeToEdge`), integrating `Modifier.imePadding()` on scroll containers so software keyboards never cover input fields in Settings or Setup.
- **Resilient Auto-Launch & Pure Screen Routing:** Pure functional routing state machine (`computeNextScreen`, `computeBackScreen`) that auto-launches the web view on server start, retains backstack history from the quick toolbar, and gracefully handles server restart flaps.
- **Smart Port Resolution & Auto-Fallback:** Loopback probing with `SO_REUSEADDR` to safely detect occupied ports and dynamically allocate kernel ephemeral ports (`1..65535`).
- **Background Battery Protection:** Daemon idle countdown timer (`BackgroundTimeoutManager`) that gracefully shuts down the server when backgrounded past the configured time limit.
- **AES-256 Data Backup & Migration:** Built-in archive engine (`BackupManager`) supporting standard ZIP and WinZip-compatible AES-256 encryption (`zip4j`). Includes pre-flight archive inspection, Clean Restore with atomic unified rollback, and Additive Merge.
- **Non-Destructive YAML Sync:** Two-way SnakeYAML synchronization for SillyTavern's `config.yaml` that preserves custom plugin settings and comments.
- **First-Run Setup & Restore:** Clean setup screen offering a fresh payload unpack or direct restore from an existing SillyTavern backup.
- **Fast Host JVM Unit Tests:** Pure JVM test suite (isolated without Robolectric) verifying configuration persistence, upgrade semantics, routing state machines, port probing, and archive operations.

---

## Developer Setup & Build Instructions

### Prerequisites

Ensure the following tools are installed and configured on your host machine (Linux/macOS):

- **JDK:** 17.
- **Android SDK:** Platform tools and Android SDK Platform 35 (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`).
- **Android NDK:** Version `25.2.9519653`.
- **CMake:** Version `3.22.1` (available via Android SDK Manager).
- **Python 3:** For payload checksumming and ELF 16KB alignment checks (`ci/check_elf_align.py`).
- **Standard CLI Tools:** `curl`, `tar`, `git`, `bash`.

### Quick Start (One-Shot Build)

The project includes an automated build script that fetches the pinned runtime, builds the compressed payload bundle, validates ELF memory alignment, and compiles the debug APK:

```bash
./ci/build_all.sh
```

The resulting debug APK will be located at:
`build/outputs/apk/debug/st-mobile-debug.apk`

---

### Step-by-Step Build Pipeline

If you prefer to execute the pipeline stages individually:

1. **Fetch Node.js Mobile Runtime:**
   Downloads and unpacks the pinned `libnode.so` and `libc++_shared.so` binaries for `arm64-v8a`:
   ```bash
   bash ci/fetch_node_runtime.sh
   ```

2. **Bundle SillyTavern Payload:**
   Clones the pinned SillyTavern release, installs production dependencies, strips unnecessary artifacts, and generates `st_bundle.tar` with a SHA-256 manifest:
   ```bash
   bash ci/build_st_bundle.sh
   ```

3. **Check ELF Alignment:**
   Verifies that all native libraries conform to Android 15's 16KB/4KB page alignment requirements:
   ```bash
   python3 ci/check_elf_align.py \
     src/main/jniLibs/arm64-v8a/libnode.so \
     src/main/jniLibs/arm64-v8a/libc++_shared.so
   ```

4. **Assemble APK:**
   ```bash
   ./gradlew assembleDebug
   ```

---

## Testing

ST Mobile implements a multi-tier testing pipeline combining fast host JVM unit tests, automated CI APK inspection, and physical ARM64 hardware verification:

- **Host JVM Unit Tests (Tier 1):**
  ```bash
  ./gradlew testDebugUnitTest
  ```
  Runs 90 pure JVM unit tests across 10 classes covering payload unpacking transactions, launch specs, HTTP polling, YAML syncing, backup/restore, and UI routing.

- **Static Native APK Inspection (Tier 2):**
  ```bash
  ./ci/check_apk.sh [path/to/apk]
  ```
  Audits APK ZIP integrity, native ELF64 binaries, dynamic linker dependencies, Android 15 16 KB page alignment, asset SHA-256 manifests, and DEX bytecode string leakage.

- **Physical Device Smoke Tests (Tier 3):**
  ```bash
  ./ci/run_device_tests.sh
  ```
  Executes on-device instrumentation tests (4 tests across 3 classes) on connected ARM64 hardware (or wireless ADB) verifying streaming extraction, native Node.js execution under Android SELinux / W^X boundaries, and loopback socket restart recovery.

For complete test inventories, architectural seams, failure simulations, and execution guides, see the [Testing Architecture & Verification Guide](testing.md).

---

## Architectural & Technical References

- [Architecture Guide](architecture.md) — High-level system architecture, subsystem boundaries, data flows, and state machines.
- [Technical Breakdown](technical-breakdown.md) — Exhaustive code-level breakdown, native launcher mechanics, security models, and recovery paths.
- [Testing Architecture & Verification](testing.md) — Multi-tier test suite design, test catalog, failure simulation, and verification scopes.

---

## Built On

- **SillyTavern** (AGPL-3.0), bundled unmodified.
- **Node.js Mobile Runtime** (MIT), embedding Node.js 24 on Android.
- **AndroidX & Jetpack Compose** (Apache-2.0), modern native UI and lifecycle coordinator.
- **SnakeYAML** (Apache-2.0), non-destructive configuration parser.
- **Zip4j** (Apache-2.0), WinZip-compatible AES-256 backup archive engine.
- **Apache Commons Compress** (Apache-2.0), streaming tar extraction.

For comprehensive component attributions and third-party notices, see [THIRD-PARTY.md](THIRD-PARTY.md).

---

## License

This project is licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE) because SillyTavern is bundled herein and governed by the AGPL-3.0. In compliance with Sections 6 and 13 of the AGPL-3.0, corresponding source code is made publicly available.

---

## Disclaimer

ST Mobile is an independent open-source project and is not affiliated with, endorsed by, sponsored by, or officially associated with the SillyTavern project or its maintainers. SillyTavern is a registered or common-law trademark of its respective authors.
