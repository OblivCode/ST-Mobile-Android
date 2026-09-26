#!/usr/bin/env bash
# One-shot local build: pinned runtime -> ST bundle -> APK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash ci/fetch_node_runtime.sh
bash ci/build_st_bundle.sh

python3 ci/check_elf_align.py \
  app/src/main/jniLibs/arm64-v8a/libnode.so \
  app/src/main/jniLibs/arm64-v8a/libc++_shared.so 2>/dev/null || true

./gradlew assembleDebug

echo "APK: app/build/outputs/apk/debug/app-debug.apk"
