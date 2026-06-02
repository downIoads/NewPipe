package org.schabi.newpipe.util;

import androidx.annotation.Nullable;

import org.schabi.newpipe.database.stream.model.StreamStateEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny consume-once cache of resume-position ({@link StreamStateEntity}) keyed by stream URL,
 * warmed by {@link StreamPrefetcher} ahead of a likely tap.
 *
 * <p>On the playback critical path, {@code Player.handleIntent()} normally fires an async DB read
 * for the resume position and only calls {@code initPlayback()} from its {@code observeOn(main)}
 * callback. That callback gets queued <b>behind</b> the detail fragment's {@code handleResult()}
 * main-thread rendering, delaying {@code initPlayback()} (and therefore media-source resolve +
 * first-segment buffering) by ~400ms. When the resume position was prefetched it is available here
 * synchronously, so the player can set recovery and call {@code initPlayback()} inline — before
 * {@code handleResult()} runs — removing that stall. See OPTIMIZATIONS.md.</p>
 *
 * <p>Entries are <b>consumed</b> on read so that a later replay of the same stream falls back to
 * the authoritative DB read (the resume position changes as the user watches); the cache only ever
 * serves the single tap that immediately follows the prefetch.</p>
 */
public final class StreamStateCache {

    // Present mapping = a warmed lookup. A null element means "looked up, no resume state".
    private static final Map<String, StreamStateEntity[]> CACHE = new ConcurrentHashMap<>();

    private StreamStateCache() {
    }

    /**
     * Store the resume state (or its absence) for a prefetched stream.
     *
     * @param url   the stream url
     * @param state the resume state, or {@code null} if the stream has none
     */
    public static void put(@Nullable final String url, @Nullable final StreamStateEntity state) {
        if (url == null || url.isEmpty()) {
            return;
        }
        CACHE.put(url, new StreamStateEntity[]{state});
    }

    /**
     * Atomically read-and-remove the warmed resume lookup for a url.
     *
     * @param url the stream url
     * @return {@code null} on a cache miss; otherwise a single-element array whose element is the
     *         resume state or {@code null} if the warmed lookup found no resume state
     */
    @Nullable
    public static StreamStateEntity[] consume(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        return CACHE.remove(url);
    }
}
