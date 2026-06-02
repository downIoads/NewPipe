#!/usr/bin/env bash
# Build the debug APK and install it on the connected phone. Run after any code change, then use
# scripts/measure_startup.sh to measure.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
