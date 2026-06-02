#!/usr/bin/env python3
"""Measure NewPipe video startup via adb.

The script launches a URL through NewPipe's normal VIEW intent, watches logcat, and stops once
both the first rendered frame and comments loaded markers have appeared.
"""

from __future__ import annotations

import argparse
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass


DEFAULT_PACKAGE = "org.schabi.newpipe.debug"
DEFAULT_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

EPOCH_RE = re.compile(r"^\s*(\d+\.\d+)\s+")
ELAPSED_RE = re.compile(r"\+(-?\d+)ms")
YT_STEP_RE = re.compile(r"step=([^ ]+) durationMs=(\d+)")


@dataclass
class Event:
    elapsed_ms: int
    label: str


def adb(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["adb", *args], text=True, check=True, capture_output=True)


def parse_elapsed(line: str) -> int | None:
    match = ELAPSED_RE.search(line)
    return int(match.group(1)) if match else None


def parse_epoch(line: str) -> float | None:
    match = EPOCH_RE.search(line)
    return float(match.group(1)) if match else None


def event_label(line: str) -> str:
    for marker in ("PersistentPlayerLogger:", "System.err:"):
        if marker in line:
            return line.split(marker, 1)[1].strip()
    return line


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("url", nargs="?", default=DEFAULT_URL)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--timeout", type=float, default=45.0)
    args = parser.parse_args()

    adb(["logcat", "-c"])
    logcat = subprocess.Popen(
        ["adb", "logcat", "-v", "epoch", "PersistentPlayerLogger:D", "System.err:I",
         "PlayerStartupTrace:I", "*:S"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=1,
    )

    events: list[Event] = []
    yt_steps: list[tuple[str, int]] = []
    first_frame_ms: int | None = None
    comments_loaded_ms: int | None = None
    base_epoch: float | None = None

    try:
        adb([
            "shell", "am", "start",
            "-a", "android.intent.action.VIEW",
            "-d", args.url,
            args.package,
        ])
        deadline = time.monotonic() + args.timeout
        assert logcat.stdout is not None

        while time.monotonic() < deadline:
            line = logcat.stdout.readline()
            if not line:
                continue
            line = line.rstrip()
            if not (
                "DetailLoadTrace" in line
                or "PlaybackStartTrace" in line
                or "CommentsLoadTrace" in line
                or "PoTokenProvider." in line
                or "YTSTREAMLOG" in line
                or "PlayerStartupTrace" in line
            ):
                continue

            epoch = parse_epoch(line)
            if base_epoch is None and "DetailLoadTrace" in line and " startLoading" in line:
                base_epoch = epoch

            elapsed = parse_elapsed(line)
            absolute_elapsed = (
                int((epoch - base_epoch) * 1000)
                if epoch is not None and base_epoch is not None
                else elapsed
            )
            if absolute_elapsed is not None:
                events.append(Event(absolute_elapsed, event_label(line)))

            yt_match = YT_STEP_RE.search(line)
            if yt_match:
                yt_steps.append((yt_match.group(1), int(yt_match.group(2))))

            if "PlaybackStartTrace" in line and " firstFrame " in line:
                first_frame_ms = absolute_elapsed
            if "CommentsLoadTrace" in line and " loaded" in line:
                comments_loaded_ms = absolute_elapsed

            if first_frame_ms is not None and comments_loaded_ms is not None:
                break
    finally:
        logcat.send_signal(signal.SIGINT)
        try:
            logcat.wait(timeout=2)
        except subprocess.TimeoutExpired:
            logcat.kill()

    print("Startup trace")
    print(f"url={args.url}")
    print(f"firstFrameMs={first_frame_ms}")
    print(f"commentsLoadedMs={comments_loaded_ms}")
    if first_frame_ms is not None and comments_loaded_ms is not None:
        print(f"allReadyMs={max(first_frame_ms, comments_loaded_ms)}")

    print("\nSlowest extractor steps")
    for name, duration in sorted(yt_steps, key=lambda item: item[1], reverse=True):
        print(f"{duration:5d} ms  {name}")

    print("\nTrace events (gap = ms since previous event; large gaps = main-thread stalls)")
    prev_ms: int | None = None
    for event in sorted(events, key=lambda item: item.elapsed_ms):
        gap = "" if prev_ms is None else f"+{event.elapsed_ms - prev_ms:4d}"
        print(f"{event.elapsed_ms:5d} ms  {gap:>6}  {event.label}")
        prev_ms = event.elapsed_ms

    return 0 if first_frame_ms is not None and comments_loaded_ms is not None else 2


if __name__ == "__main__":
    sys.exit(main())
