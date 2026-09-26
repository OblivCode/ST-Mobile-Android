# ST Mobile — Android app

The Android application. It runs **SillyTavern** on-device and opens it in a full-screen web
view. Everything stays local to the phone, and the server runs in the background so it keeps
working when you switch apps.

Package `app.stmobile` · label **ST Mobile** · arm64.

## Build

```bash
bash ci/build_all.sh     # fetch the pinned runtime, build the SillyTavern bundle, assemble
# or, when assets/ and jniLibs/ are already populated:
./gradlew assembleDebug
```

Output: `build/outputs/apk/debug/st-mobile-debug.apk`.

## Install & run

```bash
adb install -r build/outputs/apk/debug/st-mobile-debug.apk
adb shell am start -n app.stmobile/app.stmobile.MainActivity
```

First launch unpacks SillyTavern, then opens it. Uploads (for example character cards) go through
the Android file picker.

## Project layout

| Path | What |
| :--- | :--- |
| `src/main/java/app/stmobile/` | App code — UI, background service, runtime management |
| `src/main/cpp/` | The small native launcher for the embedded runtime |
| `src/main/assets/` | The SillyTavern bundle (generated at build time) |
| `ci/` | Build scripts |
| `docs/` | Technical plan (`PLAN.md`) |

## Status

Working end-to-end on Android 16 / arm64 (Xiaomi 15): SillyTavern 1.19.0 boots, renders, and
runs in the background.

## Licensing

Bundles SillyTavern (AGPL-3.0) and Node.js (MIT). See [`LICENSE`](LICENSE) and
[`THIRD-PARTY.md`](THIRD-PARTY.md).
