#!/usr/bin/env bash
# Measure the realistic in-app video-startup flow: an item that was visible in a list (and so was
# prefetched into the StreamInfo cache via StreamPrefetcher) is then tapped.
#
# It warms the cache with a DEBUG-only PREFETCH broadcast (the same code path as list-visibility
# prefetch), waits for the prefetch to complete, then fires the normal VIEW intent and measures
# firstFrameMs with scripts/trace_video_startup.py.
#
# Usage: scripts/measure_prefetch_flow.sh [URL] [RUNS]
set -euo pipefail
cd "$(dirname "$0")/.."

URL="${1:-https://www.youtube.com/watch?v=jNQXAC9IVRw}"
RUNS="${2:-3}"
PKG=org.schabi.newpipe.debug

for i in $(seq 1 "$RUNS"); do
    adb shell am force-stop "$PKG" >/dev/null 2>&1
    adb shell am start -n "$PKG"/org.schabi.newpipe.MainActivity >/dev/null 2>&1
    sleep 8
    adb logcat -b all -c
    # Warm the StreamInfo cache (== the list-visibility prefetch path).
    adb shell am broadcast -a org.schabi.newpipe.debug.PREFETCH --es url "$URL" -p "$PKG" >/dev/null
    # Wait until the prefetch has populated the cache (or 6s safety net).
    for _ in $(seq 1 60); do
        if adb logcat -d -s StreamPrefetcher:I | grep -q "prefetch.done url=$URL"; then
            break
        fi
        sleep 0.1
    done
    echo "===== RUN $i ====="
    scripts/trace_video_startup.py "$URL" --timeout 60 2>&1 \
        | grep -E '^(firstFrameMs|commentsLoadedMs|allReadyMs)='
done
