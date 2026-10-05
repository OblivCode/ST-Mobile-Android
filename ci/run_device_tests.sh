#!/usr/bin/env bash
# Tier 3 Physical Device Test Runner (Phase H)
# Executes on-device instrumentation tests against real ARM64 hardware (e.g. Snapdragon 8 Elite).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
  export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
fi

echo "============================================================"
echo " ST-Mobile Physical Device Test Runner (Tier 3)"
echo "============================================================"

if ! command -v adb >/dev/null 2>&1; then
  echo "[SKIP] 'adb' command not found in PATH. Skipping Tier 3 tests."
  exit 0
fi

# Select attached active device (handles wireless ADB like 192.168.1.206:40539)
DEVICE="${ANDROID_SERIAL:-$(adb devices | awk '$2=="device"{print $1; exit}')}"

if [ -z "$DEVICE" ]; then
  echo "[SKIP] No adb device attached. Skipping Tier 3 physical hardware tests."
  exit 0
fi

# Query device properties and strip Windows line endings (\r)
ABI="$(adb -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')"
MODEL="$(adb -s "$DEVICE" shell getprop ro.product.model | tr -d '\r')"
MANUFACTURER="$(adb -s "$DEVICE" shell getprop ro.product.manufacturer | tr -d '\r')"

echo "[INFO] Detected active ADB device: $MANUFACTURER $MODEL ($DEVICE)"
echo "[INFO] Device ABI: $ABI"

if [ "$ABI" != "arm64-v8a" ]; then
  echo "[SKIP] Device ABI ($ABI) is not arm64-v8a. libstnode.so is ARM64-only."
  echo "       Host emulation/x86 container detected. Skipping Tier 3 tests."
  exit 0
fi

export ANDROID_SERIAL="$DEVICE"

echo "============================================================"
echo " Running on-device connected smoke tests on $MODEL…"
echo "============================================================"

./gradlew connectedDebugAndroidTest --info

echo "============================================================"
echo " TIER 3 PHYSICAL HARDWARE SMOKE TESTS PASSED ON: $MODEL"
echo "============================================================"
