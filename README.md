# ST Mobile

SillyTavern on Android in a single APK. No laptop, no remote server, no Termux, no root, and no complex manual setup.

SillyTavern normally runs on a computer under Node.js. ST Mobile embeds that runtime directly into an Android application, manages it as a supervised local subprocess, and presents the full interface in a native, state-preserving WebView shell. Everything stays private, isolated, and offline on the device.

---

## Key Features

- **Embedded Node.js 24 Runtime:** Runs real Node.js directly on Android using a native ELF launcher (`libstnode.so`) linked against `libnode.so` and `libc++_shared.so`.
- **Zero-Reload WebView Host:** Uses a custom Jetpack Compose WebView coordinator that preserves browser state (`View.INVISIBLE`) when switching between the Dashboard, Settings, and SillyTavern.
- **Smart Port Resolution & Auto-Fallback:** Loopback probing with `SO_REUSEADDR` to safely detect occupied ports and dynamically allocate kernel ephemeral ports (`1..65535`).
- **Background Battery Protection:** Daemon idle countdown timer (`BackgroundTimeoutManager`) that gracefully shuts down the server when backgrounded past the configured time limit.
- **AES-256 Data Backup & Migration:** Built-in archive engine (`BackupManager`) supporting standard ZIP and WinZip-compatible AES-256 encryption (`zip4j`). Includes pre-flight archive inspection, Clean Restore with atomic unified rollback, and Additive Merge.
- **Non-Destructive YAML Sync:** Two-way SnakeYAML synchronization for SillyTavern's `config.yaml` that preserves custom plugin settings and comments.
- **First-Run Setup & Restore:** Clean setup screen offering a fresh payload unpack or direct restore from an existing SillyTavern backup.
- **Fast Host JVM Unit Tests:** Pure JVM test suite (isolated without Robolectric) with planned emulation/device validation suite.

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

ST Mobile runs its complete unit test suite on the host JVM without requiring an emulator, Robolectric, or connected hardware:

```bash
./gradlew testDebugUnitTest
```

All unit tests execute cleanly across configuration serialization, numerical clamping, port boundary edge cases, timeout concurrency, and archive operations.

---

## Architectural Reference

For complete architectural details, domain package boundaries, lifecycle state machines, and launch contracts, refer to the [Architecture Guide](architecture.md).

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
