#!/usr/bin/env bash
# Tier 2 Artifact Verification Script (Phase H)
# Inspects built APK archives without requiring physical devices or emulators.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APK="${1:-$ROOT/build/outputs/apk/debug/st-mobile-debug.apk}"

if [ ! -f "$APK" ]; then
  echo "ERROR: Target APK not found at $APK" >&2
  echo "Usage: $0 [path/to/apk]" >&2
  exit 1
fi

echo "============================================================"
echo " ST-Mobile Artifact Verification (Tier 2)"
echo " APK: $APK"
echo "============================================================"

# Ensure required host tools exist
for tool in unzip zipinfo readelf python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: Required host tool '$tool' not found in PATH." >&2
    exit 1
  fi
done

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

FAILED=0

pass() {
  echo "  [PASS] $1"
}

fail() {
  echo "  [FAIL] $1" >&2
  FAILED=1
}

# 1. Archive Integrity
echo "1. Checking ZIP archive integrity…"
if unzip -tq "$APK" >/dev/null 2>&1; then
  pass "APK is a valid, readable ZIP archive."
else
  fail "APK failed ZIP integrity check."
fi

# Pre-fetch file listing once to avoid SIGPIPE in pipelines under set -o pipefail
APK_FILE_LIST="$(zipinfo -1 "$APK")"

# 2. Bundled Native Binaries
echo "2. Verifying bundled ARM64 native binaries…"
for lib in "libstnode.so" "libnode.so" "libc++_shared.so"; do
  if echo "$APK_FILE_LIST" | grep -Fqx "lib/arm64-v8a/${lib}"; then
    pass "lib/arm64-v8a/${lib} is bundled."
  else
    fail "lib/arm64-v8a/${lib} is MISSING from APK."
  fi
done

# Extract native libraries to temp dir for binary inspection
unzip -q -j "$APK" "lib/arm64-v8a/*.so" -d "$TMP_DIR"

# 3. ELF Headers & Machine Architecture
echo "3. Inspecting ELF headers and architecture…"
for elf in "$TMP_DIR/libstnode.so" "$TMP_DIR/libnode.so"; do
  LIB_NAME="$(basename "$elf")"
  HEADER="$(readelf -h "$elf")"
  
  if echo "$HEADER" | grep -q "ELF64"; then
    pass "$LIB_NAME is 64-bit ELF (ELF64)."
  else
    fail "$LIB_NAME is not ELF64."
  fi

  if echo "$HEADER" | grep -q "AArch64"; then
    pass "$LIB_NAME target machine is AArch64."
  else
    fail "$LIB_NAME target machine is not AArch64."
  fi
done

# 4. Dynamic Linker DT_NEEDED Resolution
echo "4. Checking dynamic linker dependencies (DT_NEEDED)…"
PLATFORM_WHITELIST="^(libc\.so|libm\.so|libdl\.so|liblog\.so|libz\.so|libandroid\.so)$"

for elf in "$TMP_DIR/libstnode.so" "$TMP_DIR/libnode.so"; do
  LIB_NAME="$(basename "$elf")"
  NEEDED_LIBS="$(readelf -d "$elf" | awk -F'[][]' '/\(NEEDED\)/ {print $2}')"

  for dep in $NEEDED_LIBS; do
    if echo "$dep" | grep -qE "$PLATFORM_WHITELIST"; then
      pass "$LIB_NAME -> $dep (allowed platform library)."
    elif [ -f "$TMP_DIR/$dep" ]; then
      pass "$LIB_NAME -> $dep (bundled in APK lib/arm64-v8a/)."
    else
      fail "$LIB_NAME requires $dep, which is neither a platform library nor bundled in APK!"
    fi
  done
done

# 5. 16 KB Page Alignment
echo "5. Verifying 16 KB memory page alignment…"
if [ -f "$ROOT/ci/check_elf_align.py" ]; then
  for elf in "$TMP_DIR"/*.so; do
    if python3 "$ROOT/ci/check_elf_align.py" "$elf" >/dev/null 2>&1; then
      pass "$(basename "$elf") is 16 KB page-aligned (PT_LOAD >= 0x4000)."
    else
      fail "$(basename "$elf") FAILED 16 KB page alignment check."
    fi
  done
else
  fail "ci/check_elf_align.py script not found."
fi

# 6. Bundled Assets & Payload Verification
echo "6. Verifying SillyTavern assets and payload manifest…"
for asset in "assets/st_bundle.tar" "assets/payload_manifest.json" "assets/default_config.yaml"; do
  if echo "$APK_FILE_LIST" | grep -Fqx "$asset"; then
    pass "$asset is present in APK."
  else
    fail "$asset is MISSING from APK."
  fi
done

# Check tar asset is non-empty
TAR_SIZE="$(unzip -l "$APK" | awk '$4 == "assets/st_bundle.tar" {print $1}')"
if [ -n "$TAR_SIZE" ] && [ "$TAR_SIZE" -gt 1000000 ]; then
  pass "assets/st_bundle.tar is non-empty (${TAR_SIZE} bytes)."
else
  fail "assets/st_bundle.tar is missing or unexpectedly small (${TAR_SIZE:-0} bytes)."
fi

# Check payload_manifest.json is non-empty and valid JSON
if unzip -p "$APK" "assets/payload_manifest.json" | python3 -m json.tool >/dev/null 2>&1; then
  pass "assets/payload_manifest.json is valid JSON."
else
  fail "assets/payload_manifest.json is malformed or invalid JSON."
fi

# 7. Release Leak Audit (DEX bytecode string inspection)
echo "7. Auditing DEX bytecode for test artifact leakage…"
IS_RELEASE=0
if echo "$APK" | grep -qi "release"; then
  IS_RELEASE=1
fi

DEX_STRINGS="$(unzip -p "$APK" "classes*.dex" | strings 2>/dev/null || true)"
TEST_PATTERNS="FakeSharedPreferences|PayloadManagerTest|ProcessLaunchContractTest|ReadinessPollingTest"

if echo "$DEX_STRINGS" | grep -qE "$TEST_PATTERNS"; then
  if [ "$IS_RELEASE" -eq 1 ]; then
    fail "Test classes leaked into release APK DEX bytecode!"
  else
    pass "Debug APK contains debug/test symbols as expected."
  fi
else
  pass "No test symbols or test fixtures detected in DEX bytecode."
fi

echo "============================================================"
if [ "$FAILED" -eq 0 ]; then
  echo " ALL ARTIFACT CHECKS PASSED: $APK"
  echo "============================================================"
  exit 0
else
  echo " ARTIFACT VERIFICATION FAILED FOR: $APK" >&2
  echo "============================================================"
  exit 1
fi
