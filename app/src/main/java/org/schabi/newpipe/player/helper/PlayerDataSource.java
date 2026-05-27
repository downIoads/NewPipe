package org.schabi.newpipe.player.helper;

import static org.schabi.newpipe.MainActivity.DEBUG;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.hls.playlist.DefaultHlsPlaylistTracker;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.upstream.cache.CacheSpan;
import com.google.android.exoplayer2.upstream.cache.CacheWriter;
import com.google.android.exoplayer2.upstream.cache.ContentMetadata;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.player.datasource.NonUriHlsDataSourceFactory;
import org.schabi.newpipe.player.datasource.YoutubeHttpDataSource;
import org.schabi.newpipe.player.resolver.PlaybackResolver;

import java.io.File;
import java.util.Locale;
import java.util.NavigableSet;

public class PlayerDataSource {
    public static final String TAG = PlayerDataSource.class.getSimpleName();

    public static final int LIVE_STREAM_EDGE_GAP_MILLIS = 10000;

    /**
     * An approximately 4.3 times greater value than the
     * {@link DefaultHlsPlaylistTracker#DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT default}
     * to ensure that (very) low latency livestreams which got stuck for a moment don't crash too
     * early.
     */
    private static final double PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 15;

    /**
     * The maximum number of generated manifests per cache, in
     * {@link YoutubeProgressiveDashManifestCreator}, {@link YoutubeOtfDashManifestCreator} and
     * {@link YoutubePostLiveStreamDvrDashManifestCreator}.
     */
    private static final int MAX_MANIFEST_CACHE_SIZE = 500;

    /**
     * The folder name in which the ExoPlayer cache will be written.
     */
    private static final String CACHE_FOLDER_NAME = "exoplayer";

    /**
     * The {@link SimpleCache} instance which will be used to build
     * {@link com.google.android.exoplayer2.upstream.cache.CacheDataSource}s instances (with
     * {@link CacheFactory}).
     */
    private static SimpleCache cache;


    private final int progressiveLoadIntervalBytes;

    // Generic Data Source Factories (without or with cache)
    private final DataSource.Factory cachelessDataSourceFactory;
    private final CacheFactory cacheDataSourceFactory;
    private final DataSource.Factory youtubeCachelessHlsDataSourceFactory;
    private final DataSource.Factory youtubeCachelessDashDataSourceFactory;

    // YouTube-specific Data Source Factories (with cache)
    // They use YoutubeHttpDataSource.Factory, with different parameters each
    private final CacheFactory ytHlsCacheDataSourceFactory;
    private final CacheFactory ytDashCacheDataSourceFactory;
    private final CacheFactory ytProgressiveDashCacheDataSourceFactory;


    public PlayerDataSource(final Context context,
                            final TransferListener transferListener) {
        progressiveLoadIntervalBytes = PlayerHelper.getProgressiveLoadIntervalBytes(context);

        // make sure the static cache was created: needed by CacheFactories below
        instantiateCacheIfNeeded(context);

        // generic data source factories use DefaultHttpDataSource.Factory
        cachelessDataSourceFactory = new DefaultDataSource.Factory(context,
                new DefaultHttpDataSource.Factory().setUserAgent(DownloaderImpl.USER_AGENT))
                .setTransferListener(transferListener);
        cacheDataSourceFactory = new CacheFactory(context, transferListener, cache,
                new DefaultHttpDataSource.Factory().setUserAgent(DownloaderImpl.USER_AGENT));

        youtubeCachelessHlsDataSourceFactory = new DefaultDataSource.Factory(context,
                getYoutubeHttpDataSourceFactory(false, false))
                .setTransferListener(transferListener);
        youtubeCachelessDashDataSourceFactory = new DefaultDataSource.Factory(context,
                getYoutubeHttpDataSourceFactory(true, true))
                .setTransferListener(transferListener);

        // YouTube-specific data source factories use getYoutubeHttpDataSourceFactory()
        ytHlsCacheDataSourceFactory = new CacheFactory(context, transferListener, cache,
                getYoutubeHttpDataSourceFactory(false, false));
        ytDashCacheDataSourceFactory = new CacheFactory(context, transferListener, cache,
                getYoutubeHttpDataSourceFactory(true, true));
        ytProgressiveDashCacheDataSourceFactory = new CacheFactory(context, transferListener, cache,
                getYoutubeHttpDataSourceFactory(false, true));

        // set the maximum size to manifest creators
        YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(MAX_MANIFEST_CACHE_SIZE);
        YoutubeOtfDashManifestCreator.getCache().setMaximumSize(MAX_MANIFEST_CACHE_SIZE);
        YoutubePostLiveStreamDvrDashManifestCreator.getCache().setMaximumSize(
                MAX_MANIFEST_CACHE_SIZE);
    }


    //region Live media source factories
    public SsMediaSource.Factory getLiveSsMediaSourceFactory() {
        return getSSMediaSourceFactory().setLivePresentationDelayMs(LIVE_STREAM_EDGE_GAP_MILLIS);
    }

    public HlsMediaSource.Factory getLiveHlsMediaSourceFactory() {
        return new HlsMediaSource.Factory(cachelessDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setPlaylistTrackerFactory((dataSourceFactory, loadErrorHandlingPolicy,
                                            playlistParserFactory) ->
                        new DefaultHlsPlaylistTracker(dataSourceFactory, loadErrorHandlingPolicy,
                                playlistParserFactory,
                                PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT));
    }

    public HlsMediaSource.Factory getYoutubeLiveHlsMediaSourceFactory() {
        return new HlsMediaSource.Factory(youtubeCachelessHlsDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setPlaylistTrackerFactory((dataSourceFactory, loadErrorHandlingPolicy,
                                            playlistParserFactory) ->
                        new DefaultHlsPlaylistTracker(dataSourceFactory, loadErrorHandlingPolicy,
                                playlistParserFactory,
                                PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT));
    }

    public DashMediaSource.Factory getLiveDashMediaSourceFactory() {
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(cachelessDataSourceFactory),
                cachelessDataSourceFactory);
    }

    public DashMediaSource.Factory getYoutubeLiveDashMediaSourceFactory() {
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(youtubeCachelessDashDataSourceFactory),
                youtubeCachelessDashDataSourceFactory);
    }
    //endregion


    //region Generic media source factories
    public HlsMediaSource.Factory getHlsMediaSourceFactory(
            @Nullable final NonUriHlsDataSourceFactory.Builder hlsDataSourceFactoryBuilder) {
        if (hlsDataSourceFactoryBuilder != null) {
            hlsDataSourceFactoryBuilder.setDataSourceFactory(cacheDataSourceFactory);
            return new HlsMediaSource.Factory(hlsDataSourceFactoryBuilder.build());
        }

        return new HlsMediaSource.Factory(cacheDataSourceFactory);
    }

    public DashMediaSource.Factory getDashMediaSourceFactory() {
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(cacheDataSourceFactory),
                cacheDataSourceFactory);
    }

    public ProgressiveMediaSource.Factory getProgressiveMediaSourceFactory() {
        return new ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(progressiveLoadIntervalBytes);
    }

    public SsMediaSource.Factory getSSMediaSourceFactory() {
        return new SsMediaSource.Factory(
                new DefaultSsChunkSource.Factory(cachelessDataSourceFactory),
                cachelessDataSourceFactory);
    }

    public SingleSampleMediaSource.Factory getSingleSampleMediaSourceFactory() {
        return new SingleSampleMediaSource.Factory(cacheDataSourceFactory);
    }
    //endregion


    //region YouTube media source factories
    public HlsMediaSource.Factory getYoutubeHlsMediaSourceFactory() {
        return new HlsMediaSource.Factory(ytHlsCacheDataSourceFactory);
    }

    public DashMediaSource.Factory getYoutubeDashMediaSourceFactory() {
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(ytDashCacheDataSourceFactory),
                ytDashCacheDataSourceFactory);
    }

    public ProgressiveMediaSource.Factory getYoutubeProgressiveMediaSourceFactory() {
        return new ProgressiveMediaSource.Factory(ytProgressiveDashCacheDataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(progressiveLoadIntervalBytes);
    }
    //endregion

    /**
     * Builds the cache key actually used to store/read a stream on disk. For YouTube
     * (googlevideo.com) URLs the playback URL carries volatile {@code expire}/{@code sig} query
     * params that change on every reload, so keying the cache by the raw URL (ExoPlayer's default
     * for DASH segments) makes the cache miss across sessions and, worse, mismatches the stable
     * key the prefetch would otherwise use. We therefore derive a stable key from the immutable
     * {@code id} and {@code itag} params, so playback and the disk prefetch share one cache entry.
     *
     * @param uri         the stream URL being requested
     * @param fallbackKey the key to use when the URL is not a recognised YouTube media URL
     * @return a cache key that is stable across URL reloads for YouTube media
     */
    @NonNull
    public static String stableCacheKey(@NonNull final Uri uri,
                                        @Nullable final String fallbackKey) {
        final String host = uri.getHost();
        if (host != null && host.endsWith("googlevideo.com")) {
            final String id = uri.getQueryParameter("id");
            final String itag = uri.getQueryParameter("itag");
            if (id != null && itag != null) {
                return "yt:" + id + ":" + itag;
            }
        }
        return fallbackKey != null ? fallbackKey : uri.toString();
    }

    /**
     * @param info   the stream info
     * @param stream the stream to be prefetched/played
     * @return the on-disk cache key for the stream, matching what playback uses (see
     * {@link #stableCacheKey})
     */
    @NonNull
    public static String diskCacheKeyOf(@NonNull final StreamInfo info,
                                        @NonNull final Stream stream) {
        return stableCacheKey(Uri.parse(stream.getContent()),
                PlaybackResolver.cacheKeyOf(info, stream));
    }

    @Nullable
    public CacheWriter createDiskCacheWriter(@NonNull final StreamInfo info,
                                             @NonNull final Stream stream) {
        if (!stream.isUrl() || stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
            return null;
        }

        final CacheFactory factory = info.getService() == ServiceList.YouTube
                ? ytProgressiveDashCacheDataSourceFactory : cacheDataSourceFactory;
        final String key = PlaybackResolver.cacheKeyOf(info, stream);
        final DataSpec dataSpec = new DataSpec.Builder()
                .setUri(Uri.parse(stream.getContent()))
                .setKey(key)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build();

        // Disk-preload probe: log how far the whole-stream download has progressed. This is the
        // only visibility into the CacheWriter, which runs independently of ExoPlayer's loader (so
        // it never shows up in Player's "Load probe" logs). If bytesCached climbs toward
        // requestLength but Player's "Disk cache probe" contiguous-from-0 stays flat/drops, the
        // cache is too small and the LRU evictor is dropping the start to make room for the tail.
        final String streamKind = stream.getClass().getSimpleName();
        final CacheWriter.ProgressListener progressListener =
                new CacheWriter.ProgressListener() {
                    private long lastLoggedPercent = -1;

                    @Override
                    public void onProgress(final long requestLength, final long bytesCached,
                                           final long newBytesCached) {
                        if (requestLength <= 0) {
                            return;
                        }
                        final long percent = 100L * bytesCached / requestLength;
                        if (percent != lastLoggedPercent) {
                            lastLoggedPercent = percent;
                            Log.d(TAG, "Disk preload progress - " + streamKind + " " + percent
                                    + "% (" + bytesCached + "/" + requestLength + " bytes)"
                                    + " key=" + key);
                        }
                    }
                };
        return new CacheWriter(factory.createDataSource(), dataSpec, null, progressListener);
    }

    /**
     * Returns how much of the stream identified by {@code key} is already present in the on-disk
     * {@link SimpleCache}, as a fraction in [0, 1] of the contiguous bytes cached starting at the
     * beginning of the stream. This reflects the on-disk prefetch progress (see
     * {@link #createDiskCacheWriter}), which is independent from ExoPlayer's in-memory buffer that
     * {@code SimpleExoPlayer#getBufferedPercentage()} exposes.
     *
     * @param key the cache key of the stream (see {@code PlaybackResolver#cacheKeyOf})
     * @return the contiguously-cached fraction in [0, 1], or 0 if unknown / nothing cached
     */
    public static float getDiskCacheProgress(@NonNull final String key) {
        final SimpleCache currentCache = cache;
        if (currentCache == null) {
            return 0f;
        }
        final long contentLength =
                ContentMetadata.getContentLength(currentCache.getContentMetadata(key));
        if (contentLength <= 0) {
            return 0f;
        }
        // getCachedLength returns the length of contiguously cached data from position 0, or the
        // negated length of the hole if position 0 is not cached yet.
        final long contiguousFromStart = currentCache.getCachedLength(key, 0, contentLength);
        if (contiguousFromStart <= 0) {
            return 0f;
        }
        return Math.min(1f, (float) contiguousFromStart / contentLength);
    }

    /**
     * Builds a human-readable description of the on-disk cached byte ranges for {@code key},
     * mapped onto the playback timeline (assuming a constant byte-rate, which is good enough for a
     * progress probe). Produces e.g. {@code [0:00..5:12],[7:30..28:48] covered=81% ranges=2}.
     *
     * @param key        the cache key of the stream
     * @param durationMs the playback duration of the stream in milliseconds
     * @return a description of the cached time ranges, or a short status string if unavailable
     */
    @NonNull
    public static String describeDiskCacheRanges(@NonNull final String key, final long durationMs) {
        final SimpleCache currentCache = cache;
        if (currentCache == null) {
            return "cache=null";
        }
        final long len = ContentMetadata.getContentLength(currentCache.getContentMetadata(key));
        if (len <= 0 || durationMs <= 0) {
            return "len/duration=unknown";
        }
        final NavigableSet<CacheSpan> spans = currentCache.getCachedSpans(key);
        if (spans == null || spans.isEmpty()) {
            return "empty";
        }

        final StringBuilder sb = new StringBuilder();
        long cachedBytes = 0;
        long rangeStartByte = -1;
        long prevEndByte = -1;
        int rangeCount = 0;
        for (final CacheSpan span : spans) {
            if (span.position < 0 || span.length <= 0) {
                continue;
            }
            cachedBytes += span.length;
            if (rangeStartByte < 0) {
                rangeStartByte = span.position;
            } else if (span.position > prevEndByte) {
                appendByteRangeAsTime(sb, rangeStartByte, prevEndByte, len, durationMs);
                rangeCount++;
                rangeStartByte = span.position;
            }
            prevEndByte = span.position + span.length;
        }
        if (rangeStartByte >= 0) {
            appendByteRangeAsTime(sb, rangeStartByte, prevEndByte, len, durationMs);
            rangeCount++;
        }
        return sb + " covered=" + (100 * cachedBytes / len) + "% ranges=" + rangeCount;
    }

    private static void appendByteRangeAsTime(final StringBuilder sb, final long startByte,
                                              final long endByte, final long len,
                                              final long durationMs) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append('[')
                .append(formatTime(startByte * durationMs / len))
                .append("..")
                .append(formatTime(endByte * durationMs / len))
                .append(']');
    }

    private static String formatTime(final long ms) {
        final long totalSec = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }


    //region Static methods
    private static DefaultDashChunkSource.Factory getDefaultDashChunkSourceFactory(
            final DataSource.Factory dataSourceFactory) {
        return new DefaultDashChunkSource.Factory(dataSourceFactory);
    }

    private static YoutubeHttpDataSource.Factory getYoutubeHttpDataSourceFactory(
            final boolean rangeParameterEnabled,
            final boolean rnParameterEnabled) {
        return new YoutubeHttpDataSource.Factory()
                .setRangeParameterEnabled(rangeParameterEnabled)
                .setRnParameterEnabled(rnParameterEnabled);
    }

    public static void clearYoutubeManifestCaches() {
        YoutubeProgressiveDashManifestCreator.getCache().clear();
        YoutubeOtfDashManifestCreator.getCache().clear();
        YoutubePostLiveStreamDvrDashManifestCreator.getCache().clear();
    }

    private static void instantiateCacheIfNeeded(final Context context) {
        if (cache == null) {
            final File cacheDir = new File(context.getExternalCacheDir(), CACHE_FOLDER_NAME);
            if (DEBUG) {
                Log.d(TAG, "instantiateCacheIfNeeded: cacheDir = " + cacheDir.getAbsolutePath());
            }
            if (!cacheDir.exists() && !cacheDir.mkdir()) {
                Log.w(TAG, "instantiateCacheIfNeeded: could not create cache dir");
            }

            final LeastRecentlyUsedCacheEvictor evictor =
                    new LeastRecentlyUsedCacheEvictor(PlayerHelper.getPreferredCacheSize());
            cache = new SimpleCache(cacheDir, evictor, new StandaloneDatabaseProvider(context));
        }
    }
    //endregion
}
