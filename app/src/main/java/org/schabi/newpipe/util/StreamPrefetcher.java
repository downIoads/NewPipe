package org.schabi.newpipe.util;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.player.helper.PlayerHolder;

import java.util.Collections;
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
    private static final CompositeDisposable DISPOSABLES = new CompositeDisposable();

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
                        },
                        throwable -> {
                            IN_FLIGHT.remove(url);
                            if (DEBUG) {
                                Log.i(TAG, "prefetch.failed url=" + url + " "
                                        + throwable.getClass().getSimpleName());
                            }
                        }));
    }
}
