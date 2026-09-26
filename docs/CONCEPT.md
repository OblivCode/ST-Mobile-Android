# Architecture & Technical Plan: Standalone Android APK for SillyTavern

This document outlines the architectural blueprint, engineering constraints, and phased implementation path for embedding Node.js, native local embedding inference (`llama.cpp`), and SillyTavern into a single, self-contained Android APK.

> **Status:** original concept document. The implemented application is `app-native`; see
> [`PLAN.md`](PLAN.md) for what was actually built and [`FEASIBILITY.md`](FEASIBILITY.md) for the
> ecosystem verification.

---

## 1. High-Level Architecture

The application operates as a self-contained local client-server ecosystem running entirely on the Android device without requiring root access, Termux, or an internet connection (except for optional cloud LLM APIs).

```
+-----------------------------------------------------------------------------------+
|                                 Android APK Shell                                 |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  |                         Android Shell (Kotlin/Java)                         |  |
|  |                                                                             |  |
|  |  +---------------------------+   +---------------------------------------+  |  |
|  |  |          WebView          |   |          Foreground Service           |  |  |
|  |  |  - Fullscreen UI          |   |  - Holds PartialWakeLock & WifiLock   |  |  |
|  |  |  - Custom WebChromeClient |   |  - Persistent notification            |  |  |
|  |  |  - Loads 127.0.0.1:8000   |   |  - Manages Node & llama-server threads|  |  |
|  |  +-------------^-------------+   +---------+-------------------+---------+  |  |
|  +----------------|---------------------------|-------------------|------------+  |
|                   |                           | JNI               | JNI / Exec    |
|                   | HTTP / SSE                v                   v               |
|                   | (Port 8000)      +-----------------+  +--------------------+  |
|                   |                  |   libnode.so    |  |  llama-server      |  |
|                   |                  | (pthread loop)  |  | (ARM NEON C++)     |  |
|                   |                  +--------+--------+  +---------+----------+  |
|                   |                           | runs                | listens     |
|                   |                           v                     v (Port 8080) |
|  +----------------v---------------------------+---------------------+----------+  |
|  | SillyTavern Express Server                                                 |  |
|  | - Core logic, prompts, card parsing, auth                                  |  |
|  | - Vector Storage plugin routes embeddings -> http://127.0.0.1:8080/v1       |  |
|  +--------------------------------------------+--------------------------------+  |
|                                               | fs read/write                     |
|  +--------------------------------------------v--------------------------------+  |
|  |                      App Private / External Storage                         |  |
|  |  - /data/user/0/.../files/sillytavern/ (App code & node_modules)            |  |
|  |  - /sdcard/Android/data/.../files/data/ (Chats, Characters, Lorebooks)      |  |
|  |  - /sdcard/Android/data/.../files/models/ (GGUF Embedding Models)           |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
```

---

## 2. Core Technical Challenges & Solutions

### A. The File System Boundary (APK vs. `fs`)
* **Problem:** Node's `fs` module cannot read files directly from inside an `.apk` file (which is an uncompressed/compressed zip archive).
* **Solution:** 
  1. Package SillyTavern source files and pre-installed `node_modules` inside the APK's `assets/` directory (compressed into a single `sillytavern.tar.gz` or `.zip`).
  2. On first launch (or when an APK version update is detected), the Kotlin app unpacks this archive into `context.filesDir.absolutePath + "/sillytavern"`.
  3. Change Node's working directory (`chdir`) to this folder before initiating the runtime.

### B. User Data & Model Storage Persistence
* **Problem:** If user data (characters, chats, user settings) and multi-megabyte GGUF models live in internal `filesDir`, they are wiped on app uninstall and cannot be reached via standard Android file managers.
* **Solution:** Split code, data, and models:
  * **Application Code:** Unpacked into `context.filesDir` (internal, high-speed, protected).
  * **User Data:** Pointed to `context.getExternalFilesDir("data")` (`/sdcard/Android/data/com.sillytavern.android/files/data`). Allows USB drag-and-drop for character cards, backgrounds, and chat logs.
  * **Vector Models:** Pointed to `context.getExternalFilesDir("models")`. Users can place any compatible embedding `.gguf` file here.

### C. WebView File Uploads (`<input type="file">`)
* **Problem:** By default, Android's `WebView` ignores HTML file picker triggers.
* **Solution:** Implement a custom `WebChromeClient` overriding `onShowFileChooser()`. This maps HTML file inputs directly to Android's native file picker (`ActivityResultContracts.GetContent()`).

### D. Process Killing & Android Lifecycle (LMK)
* **Problem:** Android's Low Memory Killer (LMK) will terminate background execution when the app is minimized.
* **Solution:** Host both the Node runtime and the local inference daemon inside an Android `ForegroundService` with a persistent notification. Hold a `PowerManager.PARTIAL_WAKE_LOCK` and `WifiLock` during streaming inference and vectorization.

---

## 3. Native Embedding Inference Engine (`llama.cpp`)

Instead of relying on in-browser WebAssembly/Transformers.js (which suffers from severe WebView memory caps, lack of SIMD optimizations, and frequent LMK crashes), the APK runs an embedded C++ inference server.

### A. The Embedding Server (`llama-server`)
* **Engine:** `llama.cpp` built with Android NDK targeting `arm64-v8a` with ARM NEON / DotProduct vector extensions enabled.
* **Service:** Run as an internal daemon listening on `127.0.0.1:8080`.
* **Execution Flags:**
  ```bash
  llama-server \
    -m /sdcard/Android/data/com.sillytavern.android/files/models/bge-small-en-v1.5.Q8_0.gguf \
    --embedding \
    --host 127.0.0.1 \
    --port 8080 \
    -c 2048 \
    --threads 4
  ```
* **Performance Footprint:**
  * **Model size:** ~35 MB – 45 MB (`bge-small-en-v1.5` or `all-MiniLM-L6-v2` in Q8_0/FP16).
  * **Active RAM:** Under 90 MB.
  * **Latency:** ~10–25 ms per chunk on modern mobile CPUs (CPU-only execution avoids mobile GPU thermal throttling).

### B. SillyTavern Integration (Zero Forking)
SillyTavern's native Vector Storage module supports OpenAI-compatible embedding endpoints out of the box:
* **Vector Source:** `Custom / OpenAI Compatible`
* **Server URL:** `http://127.0.0.1:8080/v1`
* **Embedding API Key:** Any dummy string (e.g., `local`)
* SillyTavern natively sends text chunks to `POST /v1/embeddings`, which `llama-server` handles transparently.

---

## 4. Node.js Embedded Runtime Strategy

### Option 1: Use `nodejs-mobile` Prebuilts (Recommended)
The **`nodejs-mobile`** project maintains patches for Node.js (v18.x compatible) specifically for Android NDK:
* Exports `libnode.so` compiled for `arm64-v8a` and `x86_64`.
* Overrides `node::Start()` to prevent it from calling `exit()` on thread shutdown.
* Provides a JNI bridge to spawn Node on a dedicated POSIX pthread.

### Option 2: Custom Termux-based NDK Build
* Extract the patches from the official `termux/termux-packages` repository (`packages/nodejs`).
* Compile using Android NDK r26+ targeting `aarch64-linux-android33`.
* Link dynamically against Bionic `libc` and export `libnode.so`.

---

## 5. SillyTavern Dependency Audit

| Dependency Area | Status | Mitigation / Strategy |
| :--- | :--- | :--- |
| **HTTP / SSE** (`express`, `ws`) | Pure JS | Fully compatible out of the box. |
| **PNG Metadata Parsing** | Pure JS | Card metadata parsing is pure JS; no C++ image library required. |
| **Image Compression (`sharp`)** | **Native C++** | SillyTavern marks image recompression features as optional. Run with native compression disabled or substitute pure-JS fallback (`jimp`). |
| **Vector Storage** | HTTP Client | Uses standard HTTP requests to query `127.0.0.1:8080` (`llama-server`). |
| **Configuration** | File-based | Modify `config.yaml` to bind strictly to `127.0.0.1`. |

---

## 6. Implementation Roadmap

### Phase 1: Native Runtimes via NDK
1. Create a blank Android Studio project (Kotlin) with C++ (NDK/CMake) support.
2. Link `libnode.so` into `app/src/main/jniLibs/arm64-v8a/`.
3. Add `llama.cpp` source as a submodule and build the `llama-server` binary for `arm64-v8a`.
4. Verify both runtimes boot via JNI and listen on local ports (`8000` and `8080`).

### Phase 2: Assets & Data Provisioning
1. Package a production SillyTavern install into `assets/sillytavern.zip`.
2. Bundle a quantized embedding model (`all-MiniLM-L6-v2.Q8_0.gguf`) into assets or create a first-boot downloader.
3. Implement Kotlin extraction logic unpacking assets to `context.filesDir`.

### Phase 3: WebView & Frontend Shell
1. Create the main `Activity` displaying a full-screen `WebView`.
2. Implement a splash screen polling `http://127.0.0.1:8000` until HTTP 200 is returned.
3. Configure `WebChromeClient` to route `<input type="file">` to Android's system file picker.
4. Hook hardware Back navigation to WebView history.

### Phase 4: Lifecycle & Persistence Hardening
1. Host Node and `llama-server` in an Android `ForegroundService`.
2. Symlink or configure `/sdcard/Android/data/.../files/data` for accessible user data.
3. Acquire wake locks during active LLM streaming generation and lorebook vector indexing.