#!/usr/bin/env python3
"""Reproduce and trace the "What's new" feed refresh ("Not loaded: N") issue via adb.

This launches NewPipe, fires the DEBUG `org.schabi.newpipe.debug.REFRESH_FEED` broadcast (see
MainActivity#registerDebugFeedRefreshReceiver), and tails the per-subscription `FeedDebug` trace
emitted by FeedLoadManager in real time. When the refresh completes it prints a summary of exactly
which subscription(s) were left NOT LOADED and why.

Examples:
    # refresh outdated/never-loaded subscriptions, like the manual refresh button
    scripts/trace_feed_refresh.py

    # force-refresh ALL subscriptions ignoring the update threshold
    scripts/trace_feed_refresh.py --ignore-threshold

    # don't relaunch the app / don't clear logs (attach to a running session)
    scripts/trace_feed_refresh.py --no-launch --no-clear
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field

DEFAULT_PACKAGE = "org.schabi.newpipe.debug"
REFRESH_ACTION = "org.schabi.newpipe.debug.REFRESH_FEED"

EPOCH_RE = re.compile(r"^\s*(\d+\.\d+)\s+")
# A logcat line for our tag looks like:
#   06-05 01:45:00.123  1234  1300 I FeedDebug: BEGIN svc=0 uid=12 name="..." url=...
MSG_RE = re.compile(r"\bFeedDebug\s*:\s*(.*)$")


@dataclass
class Summary:
    started: bool = False
    done: bool = False
    to_load: list[str] = field(default_factory=list)
    not_loaded: list[str] = field(default_factory=list)
    ok: list[str] = field(default_factory=list)


def adb(args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["adb", *args], text=True, check=check, capture_output=True)


def ensure_device() -> str:
    out = adb(["devices"]).stdout.strip().splitlines()[1:]
    devices = [ln.split()[0] for ln in out if ln.strip() and ln.split()[1] == "device"]
    if not devices:
        print("No adb device connected.", file=sys.stderr)
        sys.exit(1)
    return devices[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--group-id", type=int, default=None,
                        help="refresh a single subscription group id (default: all)")
    parser.add_argument("--ignore-threshold", action="store_true",
                        help="force-refresh ALL subscriptions, ignoring the update threshold")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--no-launch", action="store_true",
                        help="do not (re)launch the app first")
    parser.add_argument("--no-clear", action="store_true",
                        help="do not clear logcat before starting")
    parser.add_argument("--settle", type=float, default=4.0,
                        help="seconds to wait after launching the app before refreshing")
    args = parser.parse_args()

    ensure_device()

    if not args.no_clear:
        adb(["logcat", "-b", "all", "-c"], check=False)

    if not args.no_launch:
        adb(["shell", "am", "start", "-n",
             f"{args.package}/org.schabi.newpipe.MainActivity"], check=False)
        time.sleep(args.settle)

    # Start tailing before sending the broadcast so we never miss the first lines.
    logcat = subprocess.Popen(
        ["adb", "logcat", "-v", "epoch", "-s", "FeedDebug:*"],
        stdout=subprocess.PIPE, text=True,
    )

    # Fire the refresh broadcast.
    bcast = ["shell", "am", "broadcast", "-a", REFRESH_ACTION]
    if args.group_id is not None:
        bcast += ["--el", "group_id", str(args.group_id)]
    if args.ignore_threshold:
        bcast += ["--ez", "ignore_threshold", "true"]
    bcast += ["-p", args.package]
    adb(bcast, check=False)
    print(f"-> sent {REFRESH_ACTION} "
          f"(group_id={args.group_id or 'ALL'}, ignore_threshold={args.ignore_threshold})\n")

    summary = Summary()
    t0 = time.time()
    rc = 0
    try:
        while True:
            if time.time() - t0 > args.timeout:
                print(f"\n[timeout after {args.timeout:.0f}s] refresh did not report DONE",
                      file=sys.stderr)
                rc = 2
                break
            line = logcat.stdout.readline()
            if not line:
                time.sleep(0.05)
                continue
            m = MSG_RE.search(line)
            if not m:
                continue
            msg = m.group(1).rstrip()
            elapsed = time.time() - t0
            print(f"[{elapsed:6.2f}s] {msg}")

            if msg.startswith("REFRESH START"):
                summary.started = True
            elif msg.startswith("to-load:"):
                summary.to_load.append(msg.split("to-load:", 1)[1].strip())
            elif "NOT LOADED" in msg and ("markAsOutdated" in msg or "EXHAUSTED" in msg):
                summary.not_loaded.append(msg)
            elif msg.startswith("DB persisted OK"):
                summary.ok.append(msg)
            elif msg.startswith("REFRESH DONE"):
                summary.done = True
                # give a moment for any trailing lines, then stop
                time.sleep(0.5)
                break
    except KeyboardInterrupt:
        print("\n[interrupted]", file=sys.stderr)
        rc = 130
    finally:
        logcat.terminate()

    print("\n" + "=" * 72)
    print("FEED REFRESH SUMMARY")
    print("=" * 72)
    print(f"  subscriptions attempted : {len(summary.to_load)}")
    print(f"  loaded OK               : {len(summary.ok)}")
    print(f"  NOT LOADED              : {len(summary.not_loaded)}")
    if summary.not_loaded:
        print("\n  >>> culprit subscription(s) <<<")
        for entry in summary.not_loaded:
            print(f"    - {entry}")
    elif summary.done:
        print("\n  all subscriptions loaded successfully (no 'Not loaded' this run)")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
