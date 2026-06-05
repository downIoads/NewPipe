#!/usr/bin/env python3
"""Reproduce, trace and PROFILE the "What's new" feed refresh via adb.

This launches NewPipe, fires the DEBUG `org.schabi.newpipe.debug.REFRESH_FEED` broadcast (see
MainActivity#registerDebugFeedRefreshReceiver), and tails two traces emitted during the refresh:

  * `FeedDebug` (per-subscription) from FeedLoadManager: BEGIN/OK/PARTIAL/FAIL with a phase
    breakdown (tookMs, channelInfoMs, tabsMs, moreMs, feedMs, reqs) plus the final REFRESH DONE
    wall-clock line.
  * `FeedNet`  (per-HTTP-request) from DownloaderImpl: ms/code/kind/tag for every network request
    issued while a subscription is being fetched, so you can see which request kind (browse / rss /
    next / player) dominates.

When the refresh completes it prints:
  * overall wall-clock + average per subscription
  * the slowest subscriptions (where the time actually went)
  * a phase breakdown (how much total time is channel-info vs tabs vs pagination)
  * a network breakdown by request kind (count, total ms, avg ms)
  * a parallelism-efficiency estimate (sum of per-subscription time / wall-clock)

Examples:
    # refresh outdated/never-loaded subscriptions, like the manual refresh button
    scripts/trace_feed_refresh.py

    # force-refresh ALL subscriptions ignoring the update threshold (best for profiling)
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

# A logcat line for our tags looks like:
#   06-05 01:45:00.123  1234  1300 I FeedDebug: OK svc=0 uid=12 name="..." url=... tookMs=900 ...
MSG_RE = re.compile(r"\b(FeedDebug|FeedNet)\s*:\s*(.*)$")


def _num(field_name: str, text: str) -> int | None:
    m = re.search(rf"\b{field_name}=(-?\d+)", text)
    return int(m.group(1)) if m else None


def _name(text: str) -> str:
    m = re.search(r'name="([^"]*)"', text)
    return m.group(1) if m else "?"


@dataclass
class SubResult:
    uid: int | None
    name: str
    status: str       # OK / PARTIAL / FAIL
    took_ms: int = 0
    channel_info_ms: int = 0
    tabs_ms: int = 0
    more_ms: int = 0
    feed_ms: int = 0
    reqs: int = 0
    streams: int = 0


@dataclass
class NetReq:
    ms: int
    code: int
    kind: str
    tag: str


@dataclass
class Summary:
    started: bool = False
    done: bool = False
    to_load: list[str] = field(default_factory=list)
    not_loaded: list[str] = field(default_factory=list)
    subs: list[SubResult] = field(default_factory=list)
    net: list[NetReq] = field(default_factory=list)
    total_ms: int | None = None
    avg_ms: int | None = None
    parallel_extractions: int | None = None


def adb(args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["adb", *args], text=True, check=check, capture_output=True)


def ensure_device() -> str:
    out = adb(["devices"]).stdout.strip().splitlines()[1:]
    devices = [ln.split()[0] for ln in out if ln.strip() and ln.split()[1] == "device"]
    if not devices:
        print("No adb device connected.", file=sys.stderr)
        sys.exit(1)
    return devices[0]


def parse_sub_line(status: str, msg: str) -> SubResult:
    return SubResult(
        uid=_num("uid", msg),
        name=_name(msg),
        status=status,
        took_ms=_num("tookMs", msg) or 0,
        channel_info_ms=_num("channelInfoMs", msg) or 0,
        tabs_ms=_num("tabsMs", msg) or 0,
        more_ms=_num("moreMs", msg) or 0,
        feed_ms=_num("feedMs", msg) or 0,
        reqs=_num("reqs", msg) or 0,
        streams=_num("streams", msg) or 0,
    )


def parse_net_line(msg: str) -> NetReq | None:
    ms = _num("ms", msg)
    if ms is None:
        return None
    kind_m = re.search(r"kind=(\S+(?:\s+\S+)?)\s+tag=", msg)
    tag_m = re.search(r"tag=(\S+)", msg)
    return NetReq(
        ms=ms,
        code=_num("code", msg) or 0,
        kind=(kind_m.group(1).strip() if kind_m else "?"),
        tag=(tag_m.group(1) if tag_m else "?"),
    )


def print_report(s: Summary) -> None:
    print("\n" + "=" * 72)
    print("FEED REFRESH PROFILE")
    print("=" * 72)

    ok = [x for x in s.subs if x.status == "OK"]
    partial = [x for x in s.subs if x.status == "PARTIAL"]
    failed = [x for x in s.subs if x.status == "FAIL"]

    print(f"  subscriptions attempted : {len(s.to_load)}")
    print(f"  loaded OK               : {len(ok)}")
    print(f"  PARTIAL / FAIL          : {len(partial)} / {len(failed)}")
    print(f"  NOT LOADED              : {len(s.not_loaded)}")
    if s.total_ms is not None:
        print(f"\n  WALL-CLOCK (REFRESH DONE): {s.total_ms} ms "
              f"({s.total_ms / 1000:.1f}s)  avg/sub={s.avg_ms} ms  "
              f"parallel={s.parallel_extractions}")

    if s.subs:
        sum_took = sum(x.took_ms for x in s.subs)
        print("\n  --- per-subscription phase totals (sum across all subs) ---")
        print(f"    sum tookMs        : {sum_took} ms")
        print(f"    sum channelInfoMs : {sum(x.channel_info_ms for x in s.subs)} ms")
        print(f"    sum tabsMs        : {sum(x.tabs_ms for x in s.subs)} ms")
        print(f"    sum moreMs        : {sum(x.more_ms for x in s.subs)} ms")
        print(f"    sum feedMs (rss)  : {sum(x.feed_ms for x in s.subs)} ms")
        print(f"    sum reqs          : {sum(x.reqs for x in s.subs)}")
        if s.total_ms and s.total_ms > 0:
            eff = sum_took / s.total_ms
            print(f"\n    serialization factor = sum(tookMs)/wall = {eff:.1f}x")
            print(f"    (ideal == PARALLEL_EXTRACTIONS={s.parallel_extractions}; "
                  f"lower than that means threads sat idle / throttled)")

        slow = sorted(s.subs, key=lambda x: x.took_ms, reverse=True)[:12]
        print("\n  --- slowest subscriptions ---")
        for x in slow:
            print(f"    {x.took_ms:6d} ms  [{x.status:7s}] reqs={x.reqs} "
                  f"chan={x.channel_info_ms} tabs={x.tabs_ms} more={x.more_ms} "
                  f"feed={x.feed_ms} streams={x.streams}  {x.name!r}")

    if s.net:
        kinds: dict[str, list[int]] = {}
        for r in s.net:
            kinds.setdefault(r.kind, []).append(r.ms)
        print("\n  --- network requests by kind ---")
        for kind, times in sorted(kinds.items(), key=lambda kv: -sum(kv[1])):
            tot = sum(times)
            print(f"    {kind:32s} n={len(times):4d}  total={tot:7d} ms  "
                  f"avg={tot // len(times):5d} ms  max={max(times):5d} ms")
        codes = sorted({r.code for r in s.net})
        bad = [r for r in s.net if r.code >= 400]
        print(f"\n    http codes seen: {codes}"
              + (f"   ({len(bad)} requests >=400!)" if bad else ""))

    if s.not_loaded:
        print("\n  >>> culprit subscription(s) (NOT LOADED) <<<")
        for entry in s.not_loaded:
            print(f"    - {entry}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--group-id", type=int, default=None,
                        help="refresh a single subscription group id (default: all)")
    parser.add_argument("--ignore-threshold", action="store_true",
                        help="force-refresh ALL subscriptions, ignoring the update threshold")
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--no-launch", action="store_true",
                        help="do not (re)launch the app first")
    parser.add_argument("--no-clear", action="store_true",
                        help="do not clear logcat before starting")
    parser.add_argument("--quiet", action="store_true",
                        help="do not stream per-line trace, only print the final report")
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
        ["adb", "logcat", "-v", "epoch", "-s", "FeedDebug:*", "FeedNet:*"],
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
            tag, msg = m.group(1), m.group(2).rstrip()
            elapsed = time.time() - t0
            if not args.quiet:
                print(f"[{elapsed:6.2f}s] {tag[4:]:>4s}| {msg}")

            if tag == "FeedNet":
                req = parse_net_line(msg)
                if req:
                    summary.net.append(req)
                continue

            # FeedDebug
            if msg.startswith("REFRESH START"):
                summary.started = True
                summary.parallel_extractions = _num("parallelExtractions", msg)
            elif msg.startswith("to-load:"):
                summary.to_load.append(msg.split("to-load:", 1)[1].strip())
            elif msg.startswith("OK "):
                summary.subs.append(parse_sub_line("OK", msg))
            elif msg.startswith("PARTIAL "):
                summary.subs.append(parse_sub_line("PARTIAL", msg))
            elif msg.startswith("FAIL "):
                summary.subs.append(parse_sub_line("FAIL", msg))
            elif "NOT LOADED" in msg and ("markAsOutdated" in msg or "EXHAUSTED" in msg):
                summary.not_loaded.append(msg)
            elif msg.startswith("REFRESH DONE"):
                summary.done = True
                summary.total_ms = _num("tookMs", msg)
                summary.avg_ms = _num("avgMsPerSub", msg)
                if summary.parallel_extractions is None:
                    summary.parallel_extractions = _num("parallelExtractions", msg)
                # give a moment for any trailing lines, then stop
                time.sleep(0.5)
                break
    except KeyboardInterrupt:
        print("\n[interrupted]", file=sys.stderr)
        rc = 130
    finally:
        logcat.terminate()

    print_report(summary)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
