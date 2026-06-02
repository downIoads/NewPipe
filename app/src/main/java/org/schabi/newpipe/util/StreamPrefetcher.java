package org.schabi.newpipe.util;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.cache.CacheWriter;

import org.schabi.newpipe.App;
import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Warms the {@link InfoCache} with {@link org.schabi.newpipe.extractor.stream.StreamInfo} for
 * stream URLs that the user is likely to open next (e.g. items currently visible in a list).
 *
 * <p>When the user then taps such an item, {@code VideoDetailFragment}'s
 * {@link ExtractorHelper#getStreamInfo} call becomes a cache hit (~0ms) instead of a ~1.7s network
 * extraction — the single biggest contributor to video-startup latency. See OPTIMIZATIONS.md.</p>
 *
 * <p>Prefetches are deduplicated against the in-memory cache and against in-flight requests, and
 * capped at {@link #MAX_INFLIGHT} concurrent extractions to avoid wasting bandwidth while
 * scrolling.</p>
 */
public final class StreamPrefetcher {
    private static final String TAG = "StreamPrefetcher";
    private static final boolean DEBUG = MainActivity.DEBUG;

    // Keep this small: prefetching is opportunistic and must never starve the foreground
    // extraction the user actually asked for.
    private static final int MAX_INFLIGHT = 4;

    private static final Set<String> IN_FLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Separate in-flight guard for the (heavier, MB-scale) media warm so it dedups independently
    // of the lightweight StreamInfo prefetch.
    private static final Set<String> MEDIA_IN_FLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final CompositeDisposable DISPOSABLES = new CompositeDisposable();

    // How many leading bytes of each selected stream to pull into the disk cache before the tap.
    // Enough to cover the moov/init segment + first GOP so the first frame renders from disk; small
    // enough that a wrong guess wastes only a few MB. Video and audio are separate googlevideo
    // requests (adaptive streams), so they are warmed independently.
    private static final long WARM_VIDEO_BYTES = 3L * 1024 * 1024;
    private static final long WARM_AUDIO_BYTES = 1L * 1024 * 1024;

    private StreamPrefetcher() {
    }

    /**
     * Kick off a background extraction for the given stream so its {@code StreamInfo} is warm in
     * {@link InfoCache} by the time the user taps it. No-op if already cached, already in flight,
     * or if too many prefetches are running.
     *
     * @param serviceId the streaming service id of the stream
     * @param url       the stream url to warm into the cache
     */
    public static void prefetch(final int serviceId, @Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        PlayerHolder.getInstance().warmServiceForStartup();
        if (ExtractorHelper.isCached(serviceId, url, InfoCache.Type.STREAM)) {
            return;
        }
        if (IN_FLIGHT.size() >= MAX_INFLIGHT) {
            return;
        }
        if (!IN_FLIGHT.add(url)) {
            // already prefetching this url
            return;
        }

        final long startMs = SystemClock.elapsedRealtime();
        if (DEBUG) {
            Log.i(TAG, "prefetch.start url=" + url + " inFlight=" + IN_FLIGHT.size());
        }
        DISPOSABLES.add(ExtractorHelper.getStreamInfo(serviceId, url, false)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        info -> {
                            IN_FLIGHT.remove(url);
                            if (DEBUG) {
                                Log.i(TAG, "prefetch.done url=" + url
                                        + " durationMs=" + (SystemClock.elapsedRealtime() - startMs)
                                        + " videoStreams=" + info.getVideoStreams().size());
                            }
                            // Warm the resume-position lookup too, so the tap's
                            // Player.handleIntent can set recovery synchronously instead of via a
                            // main-thread-starved async DB callback (~400ms off the critical path).
                            // See StreamStateCache.
                            warmStreamState(url, info);
                        },
                        throwable -> {
                            IN_FLIGHT.remove(url);
                            if (DEBUG) {
                                Log.i(TAG, "prefetch.failed url=" + url + " "
                                        + throwable.getClass().getSimpleName());
                            }
                        }));
    }

    private static void warmStreamState(
            final String url,
            final org.schabi.newpipe.extractor.stream.StreamInfo info) {
        final HistoryRecordManager records = new HistoryRecordManager(App.getApp());
        DISPOSABLES.add(records.loadStreamState(info)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        state -> {
                            StreamStateCache.put(url, state);
                            if (DEBUG) {
                                Log.i(TAG, "prefetch.stateWarmed url=" + url
                                        + " progressMs=" + state.getProgressMillis());
                            }
                        },
                        throwable -> { },
                        // completed with no resume state — cache the "no state" answer too
                        () -> {
                            StreamStateCache.put(url, null);
                            if (DEBUG) {
                                Log.i(TAG, "prefetch.stateWarmed url=" + url + " noState");
                            }
                        }));
    }

    /**
     * In addition to warming the {@link InfoCache} (and resume position), speculatively pulls the
     * first few MB of the streams playback would select into the shared on-disk ExoPlayer cache.
     * When the user then taps this stream, the first rendered frame is served from disk instead of
     * waiting on a cold googlevideo CDN fetch — the terminal, network-bound chunk of
     * tap-to-first-frame that upstream pipeline work cannot remove. See OPTIMIZATIONS.md.
     *
     * <p>This is MB-scale, so it is reserved for the single most-likely-next stream rather than the
     * bulk list-visibility {@link #prefetch} path. No-op if already warming this url.</p>
     *
     * @param serviceId the streaming service id of the stream
     * @param url       the stream url whose first media chunk should be warmed
     */
    public static void prefetchMedia(final int serviceId, @Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        // Also warm StreamInfo + resume position (dedup'd internally).
        prefetch(serviceId, url);

        if (!MEDIA_IN_FLIGHT.add(url)) {
            return;
        }
        DISPOSABLES.add(ExtractorHelper.getStreamInfo(serviceId, url, false)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        info -> {
                            try {
                                warmFirstChunks(url, info);
                            } finally {
                                MEDIA_IN_FLIGHT.remove(url);
                            }
                        },
                        throwable -> {
                            MEDIA_IN_FLIGHT.remove(url);
                            if (DEBUG) {
                                Log.i(TAG, "mediaWarm.failed url=" + url + " "
                                        + throwable.getClass().getSimpleName());
                            }
                        }));
    }

    private static void warmFirstChunks(final String url, final StreamInfo info) {
        final Context context = App.getApp();
        final List<Stream> streams = selectStreamsToWarm(context, info);
        if (streams.isEmpty()) {
            if (DEBUG) {
                Log.i(TAG, "mediaWarm.done url=" + url + " noWarmableStreams");
            }
            return;
        }
        final PlayerDataSource dataSource = new PlayerDataSource(context, null);
        final long startMs = SystemClock.elapsedRealtime();
        for (final Stream stream : streams) {
            final long maxBytes = stream instanceof VideoStream
                    ? WARM_VIDEO_BYTES : WARM_AUDIO_BYTES;
            final CacheWriter writer =
                    dataSource.createFirstChunkDiskCacheWriter(info, stream, maxBytes);
            if (writer == null) {
                continue;
            }
            try {
                writer.cache();
            } catch (final Exception e) {
                if (DEBUG) {
                    Log.i(TAG, "mediaWarm.streamFailed url=" + url + " "
                            + e.getClass().getSimpleName());
                }
            }
        }
        if (DEBUG) {
            Log.i(TAG, "mediaWarm.done url=" + url
                    + " durationMs=" + (SystemClock.elapsedRealtime() - startMs)
                    + " streams=" + streams.size());
        }
    }

    /**
     * Selects the streams playback would use by default (no quality/audio-track override),
     * mirroring {@code VideoPlaybackResolver}, so the warmed disk-cache key matches what playback
     * reads.
     *
     * @param context the Android context (for resolution/format preferences)
     * @param info    the stream info to select playback streams from
     * @return the video and (when needed) audio streams to warm, possibly empty
     */
    private static List<Stream> selectStreamsToWarm(final Context context,
                                                    final StreamInfo info) {
        final List<Stream> out = new ArrayList<>(2);
        final List<VideoStream> sortedVideos = ListHelper.getSortedStreamVideosList(context,
                ListHelper.getPlayableStreams(info.getVideoStreams(), info.getServiceId()),
                ListHelper.getPlayableStreams(info.getVideoOnlyStreams(), info.getServiceId()),
                false, true);
        VideoStream video = null;
        if (!sortedVideos.isEmpty()) {
            final int idx = ListHelper.getDefaultResolutionIndex(context, sortedVideos);
            if (idx >= 0 && idx < sortedVideos.size()) {
                video = sortedVideos.get(idx);
            }
        }
        if (video != null) {
            out.add(video);
        }

        // A separate audio source is needed when there is no video, or the chosen video is
        // video-only (the common YouTube adaptive case).
        if (video == null || video.isVideoOnly()) {
            final List<AudioStream> audioList =
                    ListHelper.getFilteredAudioStreams(context, info.getAudioStreams());
            if (!audioList.isEmpty()) {
                final int aidx = ListHelper.getAudioFormatIndex(context, audioList, null);
                if (aidx >= 0 && aidx < audioList.size()) {
                    out.add(audioList.get(aidx));
                }
            }
        }
        return out;
    }
}
