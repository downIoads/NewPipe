# Video Startup Optimization — working notes

> Goal: minimize time between **"user taps video" → "video is playing"** (first rendered
> frame). Target: **< 1 second**. This file is the resume point — read it first when starting
> a new session, then continue from the "Future plan" section.

Repos involved (local, NewPipe uses `includeBuild("../NewPipeExtractor")` so extractor changes
compile straight into the app):
- `/home/user/Documents/Github/downIoads/NewPipe`
- `/home/user/Documents/Github/downIoads/NewPipeExtractor`

---

## Session 4 (disk pre-buffer of the first media chunk + resume-state cache)

**TL;DR:** The fully-warm in-app path (extraction cached, player prewarmed, dispatch direct) is
gated ~3.3s, of which only the **terminal ~0.6s is network** (cold googlevideo CDN first-segment
fetch) — the rest is a serial main-thread pipeline whose internal gaps **absorb** any upstream
shaving (confirmed again this session). So this session attacked the one non-absorbable piece:
the network tail. Landed a **speculative first-chunk disk pre-buffer** — before the tap we pull the
first few MB of the streams playback will select into the shared on-disk ExoPlayer `SimpleCache`
(stable cache key already shared with playback), so the first frame renders **from disk**. Verified
on device: `CacheFactory: served from DISK cache` during playback, and the
`metadata.changed → firstFrame` tail dropped from ~600-700ms to **~326ms**. Also landed a
**resume-position cache** (removes a ~400ms main-thread-starved async DB hop — correct + foundational,
but absorbed on the benchmark) and lowered `bufferForPlayback` 2500→1000ms (helps high-bitrate start;
neutral on low-bitrate).

### Numbers (in-app prefetched flow, `measure_startup.sh` / `measure_prefetch_flow.sh`)
| Metric | Value |
|---|---|
| `firstFrameMs` this session (warm + disk pre-buffer) | ~2.7-3.4s (network-noisy) |
| `metadata.changed → firstFrame` tail, before disk pre-buffer | ~600-700ms |
| same tail, with disk pre-buffer (served from disk) | **~326ms** |

The terminal network tail is the only non-absorbed gain, and the disk pre-buffer captures it. The
remaining ~2.5s wall is the **app-side serial pipeline** (fragment open → cached extraction RxJava
hop → autoplay → `openMainPlayer` → handleIntent → initPlayback → the `useVideoSource` double
`reloadPlayQueueManager` → resolve → prepare), full of 60-220ms main-thread hops. Collapsing it is
the fragile single-player-lifecycle work prior sessions deferred; <1s requires it.

### What landed
- **`StreamPrefetcher.prefetchMedia()`** — extends the StreamInfo prefetch to also warm the first
  `WARM_VIDEO_BYTES` (3MB) / `WARM_AUDIO_BYTES` (1MB) of the default-selected video+audio streams
  into the on-disk cache. Stream selection mirrors `VideoPlaybackResolver` (default quality, no
  audio-track override) so the warmed `stableCacheKey` matches what playback reads. MB-scale, so
  it is a single-item path (NOT the bulk list-visibility `prefetch`). Wired to the DEBUG `PREFETCH`
  broadcast so the trace tools exercise it; logs `mediaWarm.done`.
- **`PlayerDataSource.createFirstChunkDiskCacheWriter(info, stream, maxBytes)`** — bounded
  `CacheWriter` (a wrong guess wastes a few MB, not a whole stream; finishes fast enough to release
  SimpleCache's single-writer lock before the tap).
- **`StreamStateCache` + `Player.handleIntent` fast path** — prefetch warms the resume position;
  `handleIntent` reads it synchronously and calls `initPlayback()` inline (before `handleResult()`
  grabs the main thread) instead of via the ~400ms-starved `observeOn(main)` DB callback. Correct +
  foundational; **absorbed** on the benchmark (moving `initPlayback` earlier just shifts the
  bottleneck onto the `useVideoSource`-triggered `reloadPlayQueueManager`).
- **`handleIntentPost` guard** (`initPlaybackRanThisIntent`) — skips a redundant
  `reloadPlayQueueManager()` when `initPlayback()` already (re)built the manager this intent.
- **`LoadController` `PRELOAD_BUFFER_FOR_PLAYBACK_MS` 2500→1000** — faster start on high-bitrate
  streams; measured neutral on the low-bitrate benchmark (first frame is gated by CDN connect
  latency, not buffer-fill, there).

### Open decision (the production trigger)
`prefetchMedia` is currently only invoked by the DEBUG broadcast. The remaining step is **which
item(s) to disk-warm in production** — it is bandwidth-sensitive (MB per item, possibly metered),
so it should NOT run for every visible list item. Sensible options: warm the top related/up-next
item on the detail page; or the centered/longest-visible list item; gate behind data-saver/metered.

---

## Session 3 (prefetch + dispatch + full gap profiling)

**TL;DR:** Mapped every silent stall with new logging. Confirmed the warm-path gate is NOT
extraction — it's the serial player pipeline (foreground-service dispatch + main-thread
saturation + media-source resolve + CDN first-segment buffering). Landed: StreamInfo **prefetch**
(neutral on the cold benchmark, foundational for in-app <1s) and a **direct-dispatch fast path**
(warm/repeat plays skip the ~300-600ms `startForegroundService` round-trip; cold start unchanged).
Tooling: `scripts/measure_startup.sh` (single-play, full annotated timeline with inter-event gaps)
and `scripts/build_install.sh`.

### Full cold-start gap breakdown (prefetched, `cached=true`, ~3.8s; from `measure_startup.sh`)
| Gap | ~ms | What it is | Eliminable? |
|---|---|---|---|
| warmPlayerService → player.construct | 295 | 1st `startForegroundService` system dispatch | only by not foreground-starting |
| construct.end → extractor.success | 279 | cached-extraction callback starved on main thread | reduce main-thread load |
| handleResult.end → handleIntent.start | **538** | 2nd `startForegroundService` dispatch (the queue) | **yes, when player already bound** |
| resume DB done → stateLoaded | 319 | `loadStreamState` `observeOn(main)` callback starved (DB work is ~10ms) | reduce main-thread load |
| initPlayback.end → mediaSource.resolve.start | **504** | playQueue INIT event starved behind `setupAfterIntent`/player-UI setup | reduce main-thread load |
| prepare → firstFrame | ~450 | CDN first-segment fetch (network) | preconnect/pre-buffer only |

### Key findings (don't re-investigate)
1. **Prefetch alone does not move cold firstFrame.** Caching StreamInfo makes extraction a hit
   (`cached=true`), but extraction overlaps the player pipeline and is not the gate, so firstFrame
   is unchanged (~3.8s). Prefetch is kept because it is neutral and is the foundation for the
   in-app <1s path. Code: `util/StreamPrefetcher.java`, hooked on list child-attach in
   `BaseListFragment`; DEBUG broadcast `org.schabi.newpipe.debug.PREFETCH --es url <url>` in
   `MainActivity` lets the trace tool warm the cache before firing the VIEW intent.
2. **Player is created fast (~68ms) but the bind callback is starved ~1000ms.** `createdPlayer` at
   ~305ms, but the fragment's `onServiceConnected`/`onPlayerConnected` doesn't fire until ~1390ms
   because the main thread is saturated. So at `openMainPlayer` time (cold) the player is not yet
   available → must fall back to `startForegroundService`.
3. **Upstream wins get ABSORBED.** Eliminating the 2nd dispatch moves `handleIntent.start` ~300ms
   earlier, but the downstream media-source/`setupAfterIntent` stall expands to fill the gap, so
   cold firstFrame barely moves. firstFrame is effectively gated by player-UI/surface setup + the
   CDN first-segment fetch on a roughly fixed timeline.
4. **Warm player reuse is NOT automatically fast.** Replaying with an already-alive player still
   takes ~3.5s+ for the *new* video's true first frame (the old frame stays on the surface
   meanwhile, which can fool a naive firstFrame matcher). The pipeline re-runs each time.
5. **REVERTED (regressed ~+400ms): pending-intent cold path + early/guarded warmup.** Delivering
   the queue inside the starved bind callback and re-binding added main-thread work in the worst
   window. The surviving change only takes the direct path when the player is *already* available
   and is otherwise byte-for-byte the old behavior.

### Why <1s is not reachable for a fresh video load
Even with extraction cached and dispatch eliminated, a fresh load must still: init ExoPlayer
(~150ms) + resolve the media source (~30-65ms) + **fetch the first media segment from the
googlevideo CDN (~450-750ms)** + prepare/buffer to first frame. That network-bound tail alone is
~0.7-1s, before any of the main-thread pipeline. True <1s requires the first segment to already be
buffered **before** the tap (speculative pre-buffer of the most-likely item during idle browsing),
which is bandwidth-costly and touches the fragile single-player lifecycle. Next concrete steps:
preconnect/warm the CDN host on prefetch (plan item 5), then speculative media pre-buffer.

---

## Current status (end of last session)

| Metric (test video `jNQXAC9IVRw`, warm app) | Value |
|---|---|
| Baseline `firstFrameMs` (before this session's work) | **~7640 ms** |
| After this session | **~3780 ms** (median of 5: 3680/3707/3779/3871/3872) |

~50% faster. Still far from <1s — see "Why <1s needs preloading" below.

`allReadyMs` = `max(firstFrameMs, commentsLoadedMs)`; comments are a separate network call and
sometimes finish after the first frame. The user's metric is **firstFrameMs** ("video is
playing"), so optimize that.

---

## How to measure (do this every time)

```bash
cd /home/user/Documents/Github/downIoads/NewPipe
# build + install (mandatory after any code change, per CODEX.md)
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# realistic warm-app run (let poToken + connections settle, then "tap" a video):
adb shell am force-stop org.schabi.newpipe.debug
adb shell monkey -p org.schabi.newpipe.debug 1 >/dev/null 2>&1
sleep 8
adb logcat -b all -c
scripts/trace_video_startup.py 'https://www.youtube.com/watch?v=jNQXAC9IVRw' --timeout 60
```

Network is noisy; run 3–5× and take the median. force-stop each run = cold connections.

### Logging we added (read directly via logcat)
- `adb logcat -s PlayerStartupTrace` — player-side timing: DB-load split
  (`loadStreamState.getStream/upsert/getState`), `mediaSource.resolve.start/end durationMs=`.
- `PlaybackStartTrace ... stepMs=N` — **per-phase** cost of each player-startup step (already in
  the trace tool output). `stepMs` is the delta since the previous marker — read it straight off.
- `YTSTREAMLOG` (extractor, System.err) — `timing +Xms step=... durationMs=...` per client, plus
  `androidVr.hasFormats skipping=android` / `fallback=android`, and `web/androidVr.visitorData`
  + `.playerPost` splits in `YoutubeStreamHelper`.
- `DetailLoadTrace` / `CommentsLoadTrace` — fragment-side milestones (incl. `deferredDetailUi.*`).
- `scripts/trace_video_startup.py` already greps all of the above (logcat tags
  `PersistentPlayerLogger:D System.err:I PlayerStartupTrace:I`).

Per CODEX.md: keep logs that helped find a root cause (comment out the helper if noisy), don't
delete them.

---

## Changes made this session

### NewPipeExtractor
`extractor/.../services/youtube/extractors/YoutubeStreamExtractor.java`
- **Parallelized the player-client fetches.** `onFetchPage` now launches html5, androidVr, and
  (poToken→android) on their own threads via `runAsync()` + `awaitTask()` helpers; `next` was
  already async. Cost went from SUM of clients to slowest single client.
- **ANDROID client is now a fallback.** After `androidVr` returns, if it has playable formats we
  **skip** the android client entirely (and its WebView poToken — the slowest single step,
  0.5–1.3s). Only when androidVr has no playable formats do we fetch android (then iOS) as a
  fallback. Look for `androidVr.hasFormats skipping=android`.
- Added `ytLogStep` timing + `runAsync`/`awaitTask` helpers.

`extractor/.../services/youtube/YoutubeStreamHelper.java`
- Added `hlog()` timing: split visitorData fetch vs player POST for web + androidVr.

### NewPipe app
`app/.../fragments/detail/VideoDetailFragment.java`
- **Deferred heavy detail-UI rendering.** `updateTabs()` + `refreshCommentsTab()` (related-items
  fragment + comments fragment — expensive main-thread work) are no longer run synchronously in
  `handleResult()`. They're scheduled via `scheduleDeferredDetailUi()` and flushed by
  `flushDeferredDetailUi()` **only when playback reaches `STATE_PLAYING`** (in `onPlaybackUpdate`),
  with a 4000ms safety-net timeout (`DEFERRED_DETAIL_UI_TIMEOUT_MS`) for the no-playback case.
  Cancelled in `onDestroyView`. When autoplay is OFF it runs immediately (old behaviour).
  - **Why STATE_PLAYING, not earlier:** flushing at BLOCKED/BUFFERING runs the heavy work right
    in the middle of media-source resolution and re-starves it (saw `setMediaSource` balloon to
    829ms). Only flush after first frame.

`app/.../player/Player.java`
- `logPlaybackStartTrace` now prints `stepMs=` (per-step delta) + `playbackStartTraceLastMs`.
- Added `handleIntent.resumePlayback.dbStart` marker before the resume DB read.

`app/.../player/playback/MediaSourceManager.java`
- Added `PlayerStartupTrace` `mediaSource.resolve.start/end durationMs=` around the per-item
  resolve in `maybeLoadItem`.

`app/.../local/history/HistoryRecordManager.java`
- Added `PlayerStartupTrace loadStreamState.getStream/upsert/getState durationMs=` split (proved
  the DB itself is fast — see findings).

`scripts/trace_video_startup.py`
- Capture `PlayerStartupTrace:I` tag + include those lines in the trace output.

---

## Root-cause findings (important — don't re-investigate)

1. **The DB resume read is NOT slow.** `loadStreamState` actual work = getStream 3ms + upsert 7ms
   + getState ~15ms ≈ **~25ms**. The ~300–540ms we saw as `dbStart → stateLoaded` was the
   RxJava `observeOn(mainThread)` **callback being starved** because the main thread was busy
   rendering the detail page. A startup DB pre-warm did **nothing** — already reverted. Don't redo it.
2. **The player-startup phase was dominated by main-thread contention** from the detail-page UI
   (related-items + comments tab creation/binding). Deferring that (above) fixed the starvation:
   `mediaSource.resolve` 314ms→83ms, `setMediaSource` 829ms→308ms.
3. **ANDROID_VR needs no poToken** and returns playable streams. The plain ANDROID client only
   adds extra format variants but requires the slow WebView poToken → made it a fallback.
4. **`fetchNext` (~0.9s) blocks `extractor.success`** even though playback doesn't need it — it
   feeds related-items/description/chapters/metaInfo, which `StreamInfo.getInfo()` reads
   synchronously. Deferring it inside the extractor alone gives no win because StreamInfo is built
   synchronously and the getters block on it. See future plan.
5. **High run-to-run variance is network** (extraction 1.3–1.7s). androidVr cold TLS to
   `youtubei.googleapis.com` once spiked to 3.1s; normally ~0.4–0.5s.

### Current phase breakdown (~3.8s, from a clean trace)
| Phase | ~cost | source |
|---|---|---|
| Extraction (network) | ~1.7s | androidVr streams ready ~0.8s, but blocks on `next` ~0.9s |
| extractor.success → handleIntent.start | ~0.6s | autoplay + handleResult + `startForegroundService` dispatch (~360ms inherent) |
| ExoPlayer init (`initPlayer`) | ~0.32s | created fresh on the play intent |
| resolve + media-source plumbing | ~0.3s | RxJava main-thread hops (resolve itself ~83ms now) |
| prepare → first segment buffering → firstFrame | ~0.5s | network fetch from googlevideo CDN |

---

## Why <1s needs preloading

The benchmark launches `am start VIEW <url>` — the video ID is unknown until the tap, so nothing
can be prefetched, and the floor is ~2.5s (one YouTube player request + ExoPlayer init +
first-segment CDN fetch each cost hundreds of ms). True <1s requires doing the work **before** the
tap. That's viable for the real in-app flow (tapping a feed/search/channel item, and the
recommended-video flow already added in commit ba358d1), just not for the external-link benchmark.

---

## Future plan (TODO — ordered by expected impact)

1. **[BIGGEST] Prefetch StreamInfo before the tap.** When list items (feed/search/channel/related)
   are visible or on touch-down, kick off `ExtractorHelper.getStreamInfo(serviceId, url, false)`
   so it's warm in `InfoCache` when the user taps. On tap, `VideoDetailFragment` extraction is a
   cache hit (~0ms) instead of ~1.7s. Consider also pre-resolving/pre-buffering the media source
   for the single most-likely next item. This is THE path to <1s for in-app navigation.
   - Entry points to look at: list adapters / `StreamInfoItem` click handlers,
     `InfoCache`, `ExtractorHelper.getStreamInfo`. Guard against wasting bandwidth (only prefetch
     a small number; respect data-saver / metered settings).
2. **Pre-create ExoPlayer during the warmup window** (~-0.32s, overlaps extraction). The player
   service is warmed at ~240ms (`warmPlayerService.start`) but `ExoPlayer` is only built on the
   play intent (`Player.initPlayer`, ~320ms). Pre-build it during warmup and have
   `Player.initPlayback` reuse a fresh pre-warmed instance instead of `destroyPlayer()+initPlayer()`.
   CAUTION: NewPipe's player lifecycle is fragile (track selector, tunneling, UIs, audioReactor) —
   needs careful testing on device.
3. **Bypass the foreground-service dispatch** (~-0.36s). `openMainPlayer` uses
   `startForegroundService` (~360ms dispatch). The service is already bound (`playerHolder` /
   `onPlayerConnected`) and already foreground from warmup, so hand the queue to the bound player
   directly. CAUTION: must not violate the "startForegroundService must call startForeground"
   contract or Android force-crashes.
4. **Decouple `next` from playback-critical StreamInfo.** Since related-items/comments are now
   deferred in the UI, the player doesn't need `next` at handleResult time. Explore returning
   StreamInfo as soon as streams+metadata are ready and populating next-derived fields lazily.
   More invasive (touches extractor core used elsewhere) — lower priority.
5. Preconnect/warm the googlevideo CDN host once stream URLs are known (shave first-segment
   buffering ~100–200ms). Minor. (Largely superseded by the Session-4 disk pre-buffer, which
   removes the first-segment fetch from the critical path entirely when the guess is right.)

### New TODOs from Session 4 (ordered by expected impact)
6. **[BIGGEST now] Wire `StreamPrefetcher.prefetchMedia()` to a real production trigger.** The disk
   pre-buffer is built + verified but only fires from the DEBUG `PREFETCH` broadcast. Pick a
   bandwidth-safe trigger (it is MB-scale, must NOT run per visible list item):
   - Detail page: warm the **top related / up-next** item (most likely next tap / autoplay target).
   - Or the **centered / longest-visible** feed item (debounced), one at a time.
   - Gate behind data-saver / metered-connection checks; cap concurrent/total warmed items.
   - Consider warming a touch of more than the first chunk for high-bitrate (the residual ~326ms
     tail suggests not the entire first GOP was always cached — tune `WARM_VIDEO_BYTES`).
7. **[BIGGEST for <1s] Collapse the app-side serial pipeline** (`openMainPlayer` → first frame,
   ~1.7s of 60-220ms main-thread hops). With the network tail handled, this is now the wall. Two
   concrete targets seen in every trace:
   - **The `useVideoSource(true)` double `reloadPlayQueueManager`.** `ACTION_VIDEO_FRAGMENT_RESUMED`
     → `MainPlayerUi.useVideoSource(true)` → `playQueueManagerReloadingNeeded` returns true (video
     renderer index unknown pre-prepare) → it **disposes the manager `initPlayback` just built and
     rebuilds it**, so the media-source resolve is delayed ~700ms (the first manager never resolves).
     Make the first `initPlayback` build the video-enabled manager so the resume-time reload is a
     no-op. CAUTION: fragile (audio/video source-type switching, renderer index).
   - **The cached-extraction RxJava hop** (`extractor.start → extractor.success` is ~210ms even when
     `cached=true`): the `observeOn(main)` result is starved behind `initTabs`/comments-prefetch.
8. **Speculatively pre-PREPARE the player (not just disk), gated on idle.** The disk pre-buffer
   removes the CDN fetch; the next step is pre-resolving the media source + `ExoPlayer.prepare()`
   for the single most-likely item into the idle prewarmed player so the tap hits the existing
   `handleIntent.sameQueuePlayWhenReady` fast path (just `setPlayWhenReady(true)`), skipping
   resolve+prepare entirely. BLOCKER: a loaded play queue makes `isPlayerOpen()` true → the
   mini-player would appear before the tap; needs a "primed but hidden" player state. Highest risk,
   highest reward (this is what actually gets a fresh load under 1s).

## Open trade-off to remember
- The android-client-as-fallback change means the common path uses ANDROID_VR's format ladder
  (~10 adaptive) instead of ANDROID's (~19). Playback is fine for the test video; if a user
  reports missing qualities, revisit (e.g. fetch android client in the background after playback
  starts to enrich formats without blocking).
