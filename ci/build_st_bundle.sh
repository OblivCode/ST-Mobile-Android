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

ASSETS="$ROOT/src/main/assets"
mkdir -p "$ASSETS"
rm -f "$ASSETS/st_bundle.tar" "$ASSETS/st_bundle.tar.gz"
tar -C "$STAGE" -cf "$ASSETS/st_bundle.tar" st

SHA="$(sha256sum "$ASSETS/st_bundle.tar" | cut -d' ' -f1)"
cat > "$ASSETS/payload_manifest.json" <<EOF
{
  "payload_version": "st-${ST_TAG}-${ST_COMMIT:0:8}",
  "st_version": "${ST_TAG}",
  "st_commit": "${ST_COMMIT}",
  "node_version": "${NODE_VERSION}",
  "node_runtime": "FongMi/nodejs-mobile ${NODE_VERSION}-android.2",
  "bundle": "st_bundle.tar",
  "bundle_sha256": "${SHA}"
}
EOF

echo "ST bundle ready: ${ST_TAG} (${ST_COMMIT:0:8}), sha256=${SHA}"
