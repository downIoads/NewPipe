#!/usr/bin/env bash
# Single-play video-startup measurement for autonomous iteration.
#
# Does the full realistic warm-app flow ONCE and prints the annotated timeline (with inter-event
# gaps so main-thread stalls are obvious):
#   1. force-stop + launch MainActivity, settle (poToken/connections warm up)
#   2. prefetch the target URL (== list-visibility StreamPrefetcher path), wait for prefetch.done
#   3. fire the normal VIEW intent (the "tap") and trace until firstFrame
#
# Usage: scripts/measure_startup.sh [URL]
#   Rebuild+install first with: scripts/build_install.sh
set -euo pipefail
cd "$(dirname "$0")/.."

URL="${1:-https://www.youtube.com/watch?v=jNQXAC9IVRw}"
PKG=org.schabi.newpipe.debug

adb shell am force-stop "$PKG" >/dev/null 2>&1
adb shell am start -n "$PKG"/org.schabi.newpipe.MainActivity >/dev/null 2>&1
sleep 8
adb logcat -b all -c
adb shell am broadcast -a org.schabi.newpipe.debug.PREFETCH --es url "$URL" -p "$PKG" >/dev/null
# Wait until the first media chunk has been warmed onto disk (mediaWarm.done), not just StreamInfo,
# so the traced tap measures the full pre-warmed flow. 12s safety net for the MB-scale download.
for _ in $(seq 1 120); do
    if adb logcat -d -s StreamPrefetcher:I | grep -q "mediaWarm.done url=$URL"; then break; fi
    sleep 0.1
done
exec scripts/trace_video_startup.py "$URL" --timeout 60
