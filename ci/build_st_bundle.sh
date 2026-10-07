#!/usr/bin/env bash
# Build the bundled SillyTavern payload from a pinned upstream tag.
# Produces src/main/assets/st_bundle.tar + payload_manifest.json.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ST_TAG="1.19.0"
ST_COMMIT="7e8663cd9c184a550b37238218bdd32c6efc68e9"
NODE_VERSION="v24.20.0"

WORK="${ST_WORK_DIR:-/tmp/st-mobile-st}"
SRC="$WORK/SillyTavern"
STAGE="$WORK/stage"

mkdir -p "$WORK"

if [ ! -d "$SRC/.git" ]; then
  rm -rf "$SRC"
  git clone --depth 1 --branch "$ST_TAG" https://github.com/SillyTavern/SillyTavern.git "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin "$ST_COMMIT"
git -C "$SRC" checkout -q "$ST_COMMIT"

echo "Installing production dependencies (no scripts)…"
( cd "$SRC" && npm ci --omit=dev --ignore-scripts )

echo "Auditing for native addons…"
if find "$SRC/node_modules" \( -name binding.gyp -o -name '*.node' -o -type d -name prebuilds \) | grep -q .; then
  echo "ERROR: native addon found in node_modules; the embedded runtime cannot build these." >&2
  exit 1
fi

echo "Staging bundle…"
rm -rf "$STAGE"
mkdir -p "$STAGE/st"
tar -C "$SRC" -cf - \
  --exclude=./.git --exclude=./tests --exclude=./backups --exclude=./data \
  --exclude=./.github --exclude=./.vscode --exclude=./docker --exclude=./colab . \
  | tar -C "$STAGE/st" -xf -

MAIN_ASSETS="$ROOT/src/main/assets"
FULL_ASSETS="$ROOT/src/full/assets"
PAYLOAD_OUT="$ROOT/build/outputs/payload"

mkdir -p "$MAIN_ASSETS" "$FULL_ASSETS" "$PAYLOAD_OUT"
rm -f "$MAIN_ASSETS/st_bundle.tar"*
rm -f "$FULL_ASSETS/st_bundle.tar"*
rm -f "$PAYLOAD_OUT/st_bundle.tar"*

echo "Creating uncompressed TAR bundle for full flavor assets…"
tar -C "$STAGE" -cf "$FULL_ASSETS/st_bundle.tar" st
TAR_SHA="$(sha256sum "$FULL_ASSETS/st_bundle.tar" | cut -d' ' -f1)"

echo "Creating compressed GZIP release bundle for slim flavor / OTA distribution…"
gzip -9 -n -c "$FULL_ASSETS/st_bundle.tar" > "$PAYLOAD_OUT/st_bundle.tar.gz"
GZ_SHA="$(sha256sum "$PAYLOAD_OUT/st_bundle.tar.gz" | cut -d' ' -f1)"

cat > "$MAIN_ASSETS/payload_manifest.json" <<EOF
{
  "payload_version": "st-${ST_TAG}-${ST_COMMIT:0:8}",
  "st_version": "${ST_TAG}",
  "st_commit": "${ST_COMMIT}",
  "node_version": "${NODE_VERSION}",
  "node_runtime": "FongMi/nodejs-mobile ${NODE_VERSION}-android.2",
  "bundle": "st_bundle.tar.gz",
  "bundle_sha256": "${GZ_SHA}",
  "tar_bundle": "st_bundle.tar",
  "tar_sha256": "${TAR_SHA}"
}
EOF

cp -f "$MAIN_ASSETS/payload_manifest.json" "$PAYLOAD_OUT/payload_manifest.json"

echo "ST bundle ready: ${ST_TAG} (${ST_COMMIT:0:8})"
echo "  Full APK asset: $FULL_ASSETS/st_bundle.tar (sha256=${TAR_SHA})"
echo "  Release bundle: $PAYLOAD_OUT/st_bundle.tar.gz (sha256=${GZ_SHA})"
