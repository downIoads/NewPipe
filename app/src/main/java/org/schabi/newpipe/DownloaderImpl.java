package org.schabi.newpipe;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.error.ReCaptchaActivity;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.PersistentPlayerLogger;
import org.schabi.newpipe.util.potoken.PoTokenProviderImpl;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public final class DownloaderImpl extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE_KEY =
            "youtube_restricted_mode_key";
    public static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";
    public static final String YOUTUBE_DOMAIN = "youtube.com";
    private static final String YOUTUBE_PLAYER_ENDPOINT = "/youtubei/v1/player";

    private static DownloaderImpl instance;
    private final Map<String, String> mCookies;
    private final OkHttpClient client;

    /**
     * Per-subscription network tracing for the "What's new" feed refresh.
     *
     * <p>{@link org.schabi.newpipe.local.feed.service.FeedLoadManager} sets a short tag (e.g.
     * {@code sub#42}) on the current extraction thread before fetching a subscription, and clears
     * it afterwards. While a tag is set, every HTTP request issued from that thread is timed and
     * logged to the {@code FeedNet} tag, so we can see exactly which network call (browse / rss /
     * next / player) of which subscription is slow. Filter with {@code adb logcat -s FeedNet}.</p>
     *
     * <p>It is a {@link ThreadLocal} because feed extractions run blocking on a pool of parallel
     * RxJava IO threads (one subscription per thread at a time), so the tag of the thread uniquely
     * identifies the subscription whose request is in flight.</p>
     */
    public static final String FEED_NET_TAG = "FeedNet";
    private static final ThreadLocal<String> FEED_REQUEST_TAG = new ThreadLocal<>();
    private static final ThreadLocal<int[]> FEED_REQUEST_COUNT = new ThreadLocal<>();

    // Begin tracing network requests on the current thread under the given tag.
    public static void setFeedRequestTag(final String tag) {
        FEED_REQUEST_TAG.set(tag);
        FEED_REQUEST_COUNT.set(new int[]{0});
    }

    // Stop tracing network requests on the current thread.
    public static void clearFeedRequestTag() {
        FEED_REQUEST_TAG.remove();
        FEED_REQUEST_COUNT.remove();
    }

    // Number of traced HTTP requests issued on the current thread since the last tag was set.
    public static int feedRequestCount() {
        final int[] c = FEED_REQUEST_COUNT.get();
        return c == null ? 0 : c[0];
    }

    // Classify a YouTube/innertube URL into a short, greppable request kind so the FeedNet trace
    // is readable at a glance (rss / browse / next / player / search).
    private static String classifyUrl(final String url) {
        try {
            final java.net.URI u = java.net.URI.create(url);
            final String host = u.getHost() == null ? "?" : u.getHost();
            final String path = u.getPath() == null ? "" : u.getPath();
            final String kind;
            if (url.contains("feeds/videos.xml")) {
                kind = "rss";
            } else if (path.contains("/youtubei/v1/browse")) {
                kind = "browse";
            } else if (path.contains("/youtubei/v1/next")) {
                kind = "next";
            } else if (path.contains("/youtubei/v1/player")) {
                kind = "player";
            } else if (path.contains("/youtubei/v1/search")) {
                kind = "search";
            } else {
                kind = path.isEmpty() ? "/" : path;
            }
            return host + " " + kind;
        } catch (final Exception e) {
            return url;
        }
    }

    private DownloaderImpl(final OkHttpClient.Builder builder) {
        this.client = builder
                .readTimeout(30, TimeUnit.SECONDS)
//                .cache(new Cache(new File(context.getExternalCacheDir(), "okhttp"),
//                        16 * 1024 * 1024))
                .build();
        this.mCookies = new HashMap<>();
    }

    /**
     * It's recommended to call exactly once in the entire lifetime of the application.
     *
     * @param builder if null, default builder will be used
     * @return a new instance of {@link DownloaderImpl}
     */
    public static DownloaderImpl init(@Nullable final OkHttpClient.Builder builder) {
        instance = new DownloaderImpl(
                builder != null ? builder : new OkHttpClient.Builder());
        return instance;
    }

    public static DownloaderImpl getInstance() {
        return instance;
    }

    public String getCookies(final String url) {
        final String youtubeCookie = url.contains(YOUTUBE_DOMAIN)
                ? getCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY) : null;

        // Recaptcha cookie is always added TODO: not sure if this is necessary
        return Stream.of(youtubeCookie, getCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY))
                .filter(Objects::nonNull)
                .flatMap(cookies -> Arrays.stream(cookies.split("; *")))
                .distinct()
                .collect(Collectors.joining("; "));
    }

    public String getCookie(final String key) {
        return mCookies.get(key);
    }

    public void setCookie(final String key, final String cookie) {
        mCookies.put(key, cookie);
    }

    public void removeCookie(final String key) {
        mCookies.remove(key);
    }

    public void updateYoutubeRestrictedModeCookies(final Context context) {
        final String restrictedModeEnabledKey =
                context.getString(R.string.youtube_restricted_mode_enabled);
        final boolean restrictedModeEnabled = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(restrictedModeEnabledKey, false);
        updateYoutubeRestrictedModeCookies(restrictedModeEnabled);
    }

    public void updateYoutubeRestrictedModeCookies(final boolean youtubeRestrictedModeEnabled) {
        if (youtubeRestrictedModeEnabled) {
            setCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY,
                    YOUTUBE_RESTRICTED_MODE_COOKIE);
        } else {
            removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY);
        }
        InfoCache.getInstance().clearCache();
    }

    /**
     * Get the size of the content that the url is pointing by firing a HEAD request.
     *
     * @param url an url pointing to the content
     * @return the size of the content, in bytes
     */
    public long getContentLength(final String url) throws IOException {
        try {
            final Response response = head(url);
            return Long.parseLong(response.getHeader("Content-Length"));
        } catch (final NumberFormatException e) {
            throw new IOException("Invalid content length", e);
        } catch (final ReCaptchaException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Response execute(@NonNull final Request request)
            throws IOException, ReCaptchaException {
        final String httpMethod = request.httpMethod();
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        byte[] dataToSend = request.dataToSend();

        if (dataToSend != null && url.contains(YOUTUBE_PLAYER_ENDPOINT)) {
            if (MainActivity.DEBUG) {
                Log.d(DownloaderImpl.class.getSimpleName(),
                        "Intercepting player endpoint: " + url);
            }
            dataToSend = maybeInjectPoTokenIntoPlayerRequest(url, dataToSend);
        }

        RequestBody requestBody = null;
        if (dataToSend != null) {
            requestBody = RequestBody.create(dataToSend);
        }

        final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                .method(httpMethod, requestBody)
                .url(url)
                .addHeader("User-Agent", USER_AGENT);

        final String cookies = getCookies(url);
        if (!cookies.isEmpty()) {
            requestBuilder.addHeader("Cookie", cookies);
        }

        headers.forEach((headerName, headerValueList) -> {
            requestBuilder.removeHeader(headerName);
            headerValueList.forEach(headerValue ->
                    requestBuilder.addHeader(headerName, headerValue));
        });

        // Trace this request's wall time only while a feed refresh has tagged this thread, to keep
        // the FeedNet log focused on the "What's new" refresh (see FEED_REQUEST_TAG).
        final String feedTag = FEED_REQUEST_TAG.get();
        final long netStartMs = feedTag == null ? 0L : System.currentTimeMillis();

        try (
                okhttp3.Response response = client.newCall(requestBuilder.build()).execute()
        ) {
            if (response.code() == 429) {
                if (feedTag != null) {
                    Log.w(FEED_NET_TAG, "ms=" + (System.currentTimeMillis() - netStartMs)
                            + " code=429 kind=" + classifyUrl(url) + " tag=" + feedTag
                            + " -> RATE LIMITED");
                }
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }

            String responseBodyToReturn = null;
            try (ResponseBody body = response.body()) {
                responseBodyToReturn = body.string();
            }

            if (feedTag != null) {
                // includes connect + send + receive + full body read (the network-bound part)
                final long netMs = System.currentTimeMillis() - netStartMs;
                final int[] counter = FEED_REQUEST_COUNT.get();
                if (counter != null) {
                    counter[0]++;
                }
                Log.i(FEED_NET_TAG, "ms=" + netMs + " code=" + response.code()
                        + " reqBytes=" + (dataToSend == null ? 0 : dataToSend.length)
                        + " respChars=" + (responseBodyToReturn == null
                                ? 0 : responseBodyToReturn.length())
                        + " kind=" + classifyUrl(url) + " tag=" + feedTag);
            }

            if (responseBodyToReturn != null && url.contains(YOUTUBE_PLAYER_ENDPOINT)) {
                responseBodyToReturn = maybePatchYoutubePlayabilityStatus(
                        url,
                        responseBodyToReturn);
            }

            final String latestUrl = response.request().url().toString();
            return new Response(
                    response.code(),
                    response.message(),
                    response.headers().toMultimap(),
                    responseBodyToReturn,
                    latestUrl);
        }
    }

    private static byte[] maybeInjectPoTokenIntoPlayerRequest(
            final String url,
            final byte[] dataToSend
    ) {
        try {
            final String body = new String(dataToSend, StandardCharsets.UTF_8);
            final JsonObject json = JsonParser.object().from(body);

            final JsonObject context = json.getObject("context", null);
            final JsonObject client = context != null
                    ? context.getObject("client", null)
                    : null;
            final String clientName = client != null
                    ? client.getString("clientName", null)
                    : null;
            if (MainActivity.DEBUG) {
                Log.d(DownloaderImpl.class.getSimpleName(),
                        "Player request clientName=" + clientName + " url=" + url);
            }
            PersistentPlayerLogger.log(App.getApp(), "DownloaderImpl.playerRequest "
                    + "clientName=" + clientName
                    + " metadataOnly=" + isMetadataOnlyPlayerRequest(url)
                    + " hasServiceIntegrityDimensions="
                    + json.has("serviceIntegrityDimensions")
                    + " videoId=" + redactVideoId(json.getString("videoId", null))
                    + " url=" + url);

            if (isMetadataOnlyPlayerRequest(url)) {
                PersistentPlayerLogger.log(App.getApp(),
                        "DownloaderImpl.playerRequest.skipPoToken reason=metadataOnly");
                return dataToSend;
            }

            // ANDROID/ANDROID_VR/IOS player requests: let the extractor's poToken flow through.
            // ANDROID_VR does not need poTokens at all.
            if ("ANDROID".equalsIgnoreCase(clientName)
                    || "ANDROID_VR".equalsIgnoreCase(clientName)
                    || "IOS".equalsIgnoreCase(clientName)) {
                PersistentPlayerLogger.log(App.getApp(),
                        "DownloaderImpl.playerRequest.skipPoToken reason=nativeClient "
                                + "clientName=" + clientName);
                return dataToSend;
            }

            if (json.has("serviceIntegrityDimensions")) {
                PersistentPlayerLogger.log(App.getApp(),
                        "DownloaderImpl.playerRequest.skipPoToken reason=alreadyPresent");
                return dataToSend;
            }

            if (clientName == null
                    || (!"WEB".equalsIgnoreCase(clientName)
                    && !"WEB_EMBEDDED_PLAYER".equalsIgnoreCase(clientName))) {
                PersistentPlayerLogger.log(App.getApp(),
                        "DownloaderImpl.playerRequest.skipPoToken reason=unsupportedClient "
                                + "clientName=" + clientName);
                return dataToSend;
            }

            final String videoId = json.getString("videoId", null);
            if (videoId == null || videoId.isEmpty()) {
                PersistentPlayerLogger.log(App.getApp(),
                        "DownloaderImpl.playerRequest.skipPoToken reason=noVideoId");
                return dataToSend;
            }

            PersistentPlayerLogger.log(App.getApp(),
                    "DownloaderImpl.playerRequest.poToken.start videoId="
                            + redactVideoId(videoId));
            final PoTokenResult poToken =
                    PoTokenProviderImpl.INSTANCE.getWebClientPoToken(videoId);
            if (poToken == null || poToken.playerRequestPoToken == null) {
                PersistentPlayerLogger.log(App.getApp(),
                        "DownloaderImpl.playerRequest.poToken.unavailable videoId="
                                + redactVideoId(videoId));
                return dataToSend;
            }

            final JsonObject serviceIntegrityDimensions = new JsonObject();
            serviceIntegrityDimensions.put("poToken", poToken.playerRequestPoToken);
            json.put("serviceIntegrityDimensions", serviceIntegrityDimensions);

            if (client != null && poToken.visitorData != null) {
                client.put("visitorData", poToken.visitorData);
            }

            final String updatedBody = JsonWriter.string(json);
            if (MainActivity.DEBUG) {
                Log.d(
                        DownloaderImpl.class.getSimpleName(),
                        "Injected poToken into player request for " + videoId
                                + " (" + url + ")"
                );
            }
            PersistentPlayerLogger.log(App.getApp(),
                    "DownloaderImpl.playerRequest.poToken.injected videoId="
                            + redactVideoId(videoId)
                            + " playerPotLength=" + poToken.playerRequestPoToken.length()
                            + " visitorDataLength="
                            + (poToken.visitorData == null ? -1 : poToken.visitorData.length()));
            return updatedBody.getBytes(StandardCharsets.UTF_8);
        } catch (final Exception e) {
            PersistentPlayerLogger.log(App.getApp(),
                    "DownloaderImpl.playerRequest.poToken.failed "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (MainActivity.DEBUG) {
                Log.w(
                        DownloaderImpl.class.getSimpleName(),
                        "Failed to inject poToken into player request for " + url,
                        e
                );
            }
            return dataToSend;
        }
    }

    private static boolean isMetadataOnlyPlayerRequest(@NonNull final String url) {
        return url.contains("fields=microformat,videoDetails")
                || url.contains("%24fields=microformat%2CvideoDetails")
                || url.contains("$fields=microformat,videoDetails");
    }

    @NonNull
    private static String redactVideoId(@Nullable final String videoId) {
        if (videoId == null || videoId.length() <= 4) {
            return String.valueOf(videoId);
        }
        return videoId.substring(0, 4) + "...";
    }

    private static String maybePatchYoutubePlayabilityStatus(
            final String url,
            final String responseBody
    ) {
        try {
            final JsonObject json = JsonParser.object().from(responseBody);

            if (MainActivity.DEBUG) {
                final JsonObject vd = json.getObject("videoDetails", null);
                Log.d(DownloaderImpl.class.getSimpleName(),
                        "Player response videoDetails.videoId="
                        + (vd != null ? vd.getString("videoId", "(none)") : "(no videoDetails)")
                        + " url=" + url);
            }

            final JsonObject playabilityStatus = json.getObject("playabilityStatus", null);
            if (playabilityStatus == null) {
                if (MainActivity.DEBUG) {
                    Log.d(DownloaderImpl.class.getSimpleName(),
                            "No playabilityStatus in player response: " + url);
                }
                return responseBody;
            }

            final String status = playabilityStatus.getString("status", "");
            String reason = playabilityStatus.getString("reason", "");
            if (reason == null || reason.isEmpty()) {
                final JsonArray messages = playabilityStatus.getArray("messages", null);
                if (messages != null && !messages.isEmpty()) {
                    reason = messages.getString(0);
                }
            }

            final String reasonLower = reason == null
                    ? ""
                    : reason.toLowerCase(Locale.ROOT);
            final boolean shouldPatch = reasonLower.contains("page needs to be reloaded")
                    || reasonLower.contains("reload")
                    || ("UNPLAYABLE".equalsIgnoreCase(status)
                    && reasonLower.contains("video unavailable"));

            if (MainActivity.DEBUG) {
                Log.d(
                        DownloaderImpl.class.getSimpleName(),
                        "playabilityStatus status=" + status + " reason=" + reason
                                + " shouldPatch=" + shouldPatch + " url=" + url
                );
            }

            if (!shouldPatch) {
                return responseBody;
            }

            playabilityStatus.put("status", "OK");
            playabilityStatus.remove("reason");

            final String patched = JsonWriter.string(json);
            if (MainActivity.DEBUG) {
                Log.d(
                        DownloaderImpl.class.getSimpleName(),
                        "Patched playabilityStatus for " + url
                );
            }
            return patched;
        } catch (final Exception e) {
            if (MainActivity.DEBUG) {
                Log.w(
                        DownloaderImpl.class.getSimpleName(),
                        "Failed to patch playabilityStatus for " + url,
                        e
                );
            }
            return responseBody;
        }
    }
}
