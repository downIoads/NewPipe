package org.schabi.newpipe.util.image;

import static org.schabi.newpipe.MainActivity.DEBUG;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;
import static org.schabi.newpipe.util.image.ImageStrategy.choosePreferredImage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.BitmapCompat;

import com.squareup.picasso.Cache;
import com.squareup.picasso.LruCache;
import com.squareup.picasso.OkHttp3Downloader;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.RequestCreator;
import com.squareup.picasso.Transformation;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.Image;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.CacheControl;
import okhttp3.OkHttpClient;

public final class PicassoHelper {
    private static final String TAG = PicassoHelper.class.getSimpleName();
    private static final String PLAYER_THUMBNAIL_TRANSFORMATION_KEY =
            "PICASSO_PLAYER_THUMBNAIL_TRANSFORMATION_KEY";

    private PicassoHelper() {
    }

    private static Cache picassoCache;
    private static OkHttpClient picassoDownloaderClient;

    // suppress because terminate() is called in App.onTerminate(), preventing leaks
    @SuppressLint("StaticFieldLeak")
    private static Picasso picassoInstance;


    public static void init(final Context context) {
        picassoCache = new LruCache(10 * 1024 * 1024);
        picassoDownloaderClient = new OkHttpClient.Builder()
                // Use internal cache storage (fast UFS) instead of getExternalCacheDir(),
                // which is FUSE-emulated and slow: with >2000 cached thumbnails OkHttp
                // rebuilds its DiskLruCache journal on every cold start, and on external
                // storage that rebuild blocks the first thumbnail reads for ~1.5s (the
                // grey-placeholder flash). On internal storage the same init is ~10x faster.
                .cache(new okhttp3.Cache(new File(context.getCacheDir(), "picasso"),
                        50L * 1024L * 1024L))
                // Thumbnails/avatars are immutable per-URL, but YouTube serves them with
                // "cache-control: public, max-age=300" (only 5 minutes). After that, every
                // disk-cached image is considered stale, so on each cold start OkHttp
                // revalidates it over the network (conditional GET -> 304) *before* showing
                // it. Dozens of these round-trips are what cause the ~1s grey-placeholder
                // flash even though the image bytes are already on disk. Relax the request
                // freshness so a stored image is served instantly regardless of age; images
                // that aren't cached yet (e.g. new results from a manual refresh) are still
                // fetched from the network as usual.
                .addInterceptor(chain -> {
                    final okhttp3.Response response = chain.proceed(chain.request().newBuilder()
                            .cacheControl(new CacheControl.Builder()
                                    .maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS)
                                    .build())
                            .build());
                    // ytLog: uncomment to diagnose the grey-placeholder flash. Logs the
                    // source/timing of each image load: CACHE (served from disk, no network),
                    // NETWORK (full download), or REVALIDATED (304 round-trip then disk body).
                    // final String source = response.networkResponse() == null ? "CACHE"
                    //         : (response.cacheResponse() != null ? "REVALIDATED" : "NETWORK");
                    // Log.d(TAG, "thumb " + source + " " + chain.request().url());
                    return response;
                })
                // this should already be the default timeout in OkHttp3, but just to be sure...
                .callTimeout(15, TimeUnit.SECONDS)
                .build();

        picassoInstance = new Picasso.Builder(context)
                .memoryCache(picassoCache) // memory cache
                .downloader(new OkHttp3Downloader(picassoDownloaderClient)) // disk cache
                .defaultBitmapConfig(Bitmap.Config.RGB_565)
                .build();

        // Warm up the OkHttp disk-cache index on a background thread. The first cache
        // access after process start has to open (and sometimes rebuild) the DiskLruCache
        // journal; for our large image cache that blocks the requesting thread for ~1s.
        // Because the feed's thumbnails are loaded on Picasso's worker threads as soon as
        // the list binds, that one-time cost lands right on the visible thumbnails and is
        // exactly the remaining "grey placeholder for ~1s" flash. Triggering the init here,
        // in parallel with the rest of app startup, means the index is ready by the time
        // the feed renders, so cached thumbnails show up immediately.
        final okhttp3.Cache diskCache = picassoDownloaderClient.cache();
        if (diskCache != null) {
            new Thread(() -> {
                try {
                    // final long start = System.nanoTime();
                    diskCache.size(); // forces DiskLruCache.initialize()
                    // ytLog: uncomment to measure disk-cache index init time
                    // Log.d(TAG, "Picasso disk-cache index warmed in "
                    //         + ((System.nanoTime() - start) / 1_000_000) + "ms");
                } catch (final IOException e) {
                    Log.w(TAG, "Failed to warm up Picasso disk cache", e);
                }
            }, "picasso-cache-warmup").start();
        }
    }

    public static void terminate() {
        picassoCache = null;
        picassoDownloaderClient = null;

        if (picassoInstance != null) {
            picassoInstance.shutdown();
            picassoInstance = null;
        }
    }

    public static void clearCache(final Context context) throws IOException {
        picassoInstance.shutdown();
        picassoCache.clear(); // clear memory cache
        final okhttp3.Cache diskCache = picassoDownloaderClient.cache();
        if (diskCache != null) {
            diskCache.delete(); // clear disk cache
        }
        init(context);
    }

    public static void cancelTag(final Object tag) {
        picassoInstance.cancelTag(tag);
    }

    public static void setIndicatorsEnabled(final boolean enabled) {
        picassoInstance.setIndicatorsEnabled(enabled); // useful for debugging
    }


    public static RequestCreator loadAvatar(@NonNull final List<Image> images) {
        return loadImageDefault(images, R.drawable.placeholder_person);
    }

    public static RequestCreator loadAvatar(@Nullable final String url) {
        return loadImageDefault(url, R.drawable.placeholder_person);
    }

    public static RequestCreator loadThumbnail(@NonNull final List<Image> images) {
        return loadImageDefault(images, R.drawable.placeholder_thumbnail_video);
    }

    public static RequestCreator loadThumbnail(@Nullable final String url) {
        return loadImageDefault(url, R.drawable.placeholder_thumbnail_video);
    }

    public static RequestCreator loadDetailsThumbnail(@NonNull final List<Image> images) {
        return loadImageDefault(choosePreferredImage(images),
                R.drawable.placeholder_thumbnail_video, false);
    }

    public static RequestCreator loadBanner(@NonNull final List<Image> images) {
        return loadImageDefault(images, R.drawable.placeholder_channel_banner);
    }

    public static RequestCreator loadPlaylistThumbnail(@NonNull final List<Image> images) {
        return loadImageDefault(images, R.drawable.placeholder_thumbnail_playlist);
    }

    public static RequestCreator loadPlaylistThumbnail(@Nullable final String url) {
        return loadImageDefault(url, R.drawable.placeholder_thumbnail_playlist);
    }

    public static RequestCreator loadSeekbarThumbnailPreview(@Nullable final String url) {
        return picassoInstance.load(url);
    }

    public static RequestCreator loadNotificationIcon(@Nullable final String url) {
        return loadImageDefault(url, R.drawable.ic_newpipe_triangle_white);
    }


    public static RequestCreator loadScaledDownThumbnail(final Context context,
                                                         @NonNull final List<Image> images) {
        // scale down the notification thumbnail for performance
        return PicassoHelper.loadThumbnail(images)
                .transform(new Transformation() {
                    @Override
                    public Bitmap transform(final Bitmap source) {
                        if (DEBUG) {
                            Log.d(TAG, "Thumbnail - transform() called");
                        }

                        final float notificationThumbnailWidth = Math.min(
                                context.getResources()
                                        .getDimension(R.dimen.player_notification_thumbnail_width),
                                source.getWidth());

                        final Bitmap result = BitmapCompat.createScaledBitmap(
                                source,
                                (int) notificationThumbnailWidth,
                                (int) (source.getHeight()
                                        / (source.getWidth() / notificationThumbnailWidth)),
                                null,
                                true);

                        if (result == source || !result.isMutable()) {
                            // create a new mutable bitmap to prevent strange crashes on some
                            // devices (see #4638)
                            final Bitmap copied = BitmapCompat.createScaledBitmap(
                                    source,
                                    (int) notificationThumbnailWidth - 1,
                                    (int) (source.getHeight() / (source.getWidth()
                                            / (notificationThumbnailWidth - 1))),
                                    null,
                                    true);
                            source.recycle();
                            return copied;
                        } else {
                            source.recycle();
                            return result;
                        }
                    }

                    @Override
                    public String key() {
                        return PLAYER_THUMBNAIL_TRANSFORMATION_KEY;
                    }
                });
    }

    @Nullable
    public static Bitmap getImageFromCacheIfPresent(@NonNull final String imageUrl) {
        // URLs in the internal cache finish with \n so we need to add \n to image URLs
        return picassoCache.get(imageUrl + "\n");
    }


    private static RequestCreator loadImageDefault(@NonNull final List<Image> images,
                                                   @DrawableRes final int placeholderResId) {
        return loadImageDefault(choosePreferredImage(images), placeholderResId);
    }

    private static RequestCreator loadImageDefault(@Nullable final String url,
                                                   @DrawableRes final int placeholderResId) {
        return loadImageDefault(url, placeholderResId, true);
    }

    private static RequestCreator loadImageDefault(@Nullable final String url,
                                                   @DrawableRes final int placeholderResId,
                                                   final boolean showPlaceholderWhileLoading) {
        // if the URL was chosen with `choosePreferredImage` it will be null, but check again
        // `shouldLoadImages` in case the URL was chosen with `imageListToDbUrl` (which is the case
        // for URLs stored in the database)
        if (isNullOrEmpty(url) || !ImageStrategy.shouldLoadImages()) {
            return picassoInstance
                    .load((String) null)
                    .placeholder(placeholderResId) // show placeholder when no image should load
                    .error(placeholderResId);
        } else {
            final RequestCreator requestCreator = picassoInstance
                    .load(url)
                    .error(placeholderResId);
            if (showPlaceholderWhileLoading) {
                requestCreator.placeholder(placeholderResId);
            }
            return requestCreator;
        }
    }
}
