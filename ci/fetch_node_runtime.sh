#!/usr/bin/env bash
# Fetch the pinned Node.js Android runtime and place it where the build expects it.
# Verifies every artifact by SHA-256 so releases are reproducible and tamper-evident.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TAG="v24.20.0-android.2"
ZIP_NAME="nodejs-mobile-android-24.20.0-android.2.zip"
ZIP_URL="https://github.com/FongMi/nodejs-mobile/releases/download/${TAG}/${ZIP_NAME}"
ZIP_SHA="630a4eea9ca984dc5457c5ef7aa0929b3846f77884d6badacda30be275825ef2"

LIB_ARM64_SHA="413a51c05b4fc8d7493ea0d8a2df3ccaef2055adc67337e057d295c4465bb7bf"
LIBCXX_ARM64_SHA="d523468d62d9b603cb3354294d70d4b2feabf2c3f1e43b0c96c9aabf32813708"

CACHE="${NODE_RUNTIME_CACHE:-/tmp/st-mobile-node}"
mkdir -p "$CACHE"
ZIP="$CACHE/$ZIP_NAME"
EXTRACT="$CACHE/extract"

if [ ! -f "$ZIP" ]; then
  echo "Downloading $ZIP_NAME"
  curl -sSfL -o "$ZIP" "$ZIP_URL"
fi
echo "${ZIP_SHA}  ${ZIP}" | sha256sum -c -

rm -rf "$EXTRACT"
mkdir -p "$EXTRACT"
unzip -oq "$ZIP" -d "$EXTRACT"

JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI"
cp "$EXTRACT/bin/arm64-v8a/libnode.so" "$JNI/libnode.so"
cp "$EXTRACT/bin/arm64-v8a/libc++_shared.so" "$JNI/libc++_shared.so"

echo "${LIB_ARM64_SHA}  ${JNI}/libnode.so" | sha256sum -c -
echo "${LIBCXX_ARM64_SHA}  ${JNI}/libc++_shared.so" | sha256sum -c -

# Headers for the JNI bridge + provenance for audits.
INC="$ROOT/app/src/main/cpp/node-include"
rm -rf "$INC"
mkdir -p "$INC"
cp -r "$EXTRACT/include/node" "$INC/node"

mkdir -p "$ROOT/third_party"
cp -r "$EXTRACT/config" "$ROOT/third_party/config"
[ -f "$EXTRACT/runtime-manifest.json" ] && cp "$EXTRACT/runtime-manifest.json" "$ROOT/third_party/"

echo "Node runtime ready: $TAG"
