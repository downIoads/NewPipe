package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.upstream.cache.CacheDataSink;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheKeyFactory;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

final class CacheFactory implements DataSource.Factory {
    private static final String TAG = "CacheFactory";
    // NOTE: FLAG_BLOCK_ON_CACHE is deliberately NOT set. SimpleCache allows only one writer per
    // cache key, and the whole-stream disk prefetch (CacheWriter) holds that lock for the duration
    // of its download. With FLAG_BLOCK_ON_CACHE, playback requesting an uncached region of the same
    // key would block until the prefetch released the lock (i.e. until the whole file downloaded),
    // freezing playback. Without it, playback instead streams that region straight from upstream
    // when it cannot take the write lock, while still reading any already-cached region from disk.
    private static final int CACHE_FLAGS = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR;

    private final Context context;
    private final TransferListener transferListener;
    private final DataSource.Factory upstreamDataSourceFactory;
    private final SimpleCache cache;

    CacheFactory(final Context context,
                 final TransferListener transferListener,
                 final SimpleCache cache,
                 final DataSource.Factory upstreamDataSourceFactory) {
        this.context = context;
        this.transferListener = transferListener;
        this.cache = cache;
        this.upstreamDataSourceFactory = upstreamDataSourceFactory;
    }

    @NonNull
    @Override
    public CacheDataSource createDataSource() {
        Log.d(TAG, "Cache probe - createDataSource() called (a player/preload pipeline is "
                + "requesting a cache-backed source)");
        final DefaultDataSource dataSource = new DefaultDataSource.Factory(context,
                upstreamDataSourceFactory)
                .setTransferListener(transferListener)
                .createDataSource();

        final FileDataSource fileSource = new FileDataSource();
        final CacheDataSink dataSink =
                new CacheDataSink(cache, PlayerHelper.getPreferredFileSize());
        // Cache probe: tells us definitively whether a read was served from the on-disk cache or
        // had to go upstream to the network. Pair with Player's "Load probe"/"Seek probe" logs to
        // see whether a (double-tap) seek required any actual fetching.
        final CacheDataSource.EventListener eventListener = new CacheDataSource.EventListener() {
            @Override
            public void onCachedBytesRead(final long cacheSizeBytes, final long cachedBytesRead) {
                Log.d(TAG, "Cache probe - served from DISK cache: cachedBytesRead="
                        + cachedBytesRead + " totalCacheSize=" + cacheSizeBytes);
            }

            @Override
            public void onCacheIgnored(final int reason) {
                Log.w(TAG, "Cache probe - cache IGNORED (going upstream/network) reason=" + reason);
            }
        };
        // Use a stable cache key (see PlayerDataSource#stableCacheKey) so YouTube playback and the
        // disk prefetch share a single cache entry instead of keying by the volatile playback URL.
        final CacheKeyFactory keyFactory = dataSpec ->
                PlayerDataSource.stableCacheKey(dataSpec.uri, dataSpec.key);
        return new CacheDataSource(cache, dataSource, fileSource, dataSink, CACHE_FLAGS,
                eventListener, keyFactory);
    }
}
