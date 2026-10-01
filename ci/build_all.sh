#!/usr/bin/env bash
# One-shot local build: pinned runtime -> ST bundle -> APK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
  export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
fi

bash ci/fetch_node_runtime.sh
bash ci/build_st_bundle.sh

python3 ci/check_elf_align.py \
  src/main/jniLibs/arm64-v8a/libnode.so \
  src/main/jniLibs/arm64-v8a/libc++_shared.so 2>/dev/null || true

./gradlew assembleDebug

python3 ci/check_elf_align.py \
  src/main/jniLibs/arm64-v8a/libnode.so \
  src/main/jniLibs/arm64-v8a/libc++_shared.so \
  src/main/jniLibs/arm64-v8a/libstnode.so 2>/dev/null || true

echo "APK: build/outputs/apk/debug/st-mobile-debug.apk"
