package org.schabi.newpipe.translation;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-level entry point for translating short pieces of text via the
 * llama.cpp-backed TranslateGemma model.
 *
 * The model is loaded lazily on the first translation request and kept resident
 * until {@link #shutdown()} is called or the process exits. Translation runs on
 * a dedicated single-thread executor so concurrent requests are serialised
 * (the underlying {@code llama_context} is not thread-safe).
 */
public final class TranslationManager {

    private static final String TAG = "TranslationManager";

    private static final int N_CTX = 2048;
    private static final int MAX_NEW_TOKENS = 512;
    private static final int MAX_AUTO_TRANSLATIONS = 10;

    private static volatile TranslationManager instance;

    private final Context appContext;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "TranslationManager");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private final AtomicBoolean loaded = new AtomicBoolean(false);

    /**
     * Cached translations per comment key (e.g. comment URL). Lets us avoid
     * retranslating the same comment when the user toggles back and forth or
     * the RecyclerView re-binds the holder.
     */
    private final Map<String, String> translations =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * Comment keys that are currently showing the translated text. Used to
     * restore the toggle state after RecyclerView rebinds the holder.
     */
    private final Set<String> showingTranslation =
            Collections.synchronizedSet(new HashSet<>());

    /** Comment keys with a translation currently being computed. */
    private final Set<String> inFlight =
            Collections.synchronizedSet(new HashSet<>());

    /**
     * Active auto-translate session id (typically the parent comment id) and a
     * counter, so we can hard-cap how many comments we auto-translate when the
     * user opens replies of a translated comment.
     */
    @Nullable private volatile String autoTranslateSession;
    private int autoTranslateCount;

    private TranslationManager(final Context context) {
        this.appContext = context.getApplicationContext();
        if (LlamaTranslator.isLibAvailable()) {
            LlamaTranslator.nativeBackendInit();
        }
    }

    public static TranslationManager getInstance(@NonNull final Context context) {
        if (instance == null) {
            synchronized (TranslationManager.class) {
                if (instance == null) {
                    instance = new TranslationManager(context);
                }
            }
        }
        return instance;
    }

    public static boolean isEnabled(@NonNull final Context context) {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(
                context.getString(R.string.translation_enabled_key), false);
    }

    public static String getTargetLanguage(@NonNull final Context context) {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(
                context.getString(R.string.translation_target_language_key),
                context.getString(R.string.translation_target_language_default));
    }

    @Nullable
    public static String getModelUriString(@NonNull final Context context) {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(context);
        // Prefer the resolved real filesystem path; fall back to the SAF URI.
        final String path = prefs.getString(
                context.getString(R.string.translation_model_path_key), null);
        if (path != null && !path.isEmpty()) {
            return path;
        }
        return prefs.getString(
                context.getString(R.string.translation_model_uri_key), null);
    }

    public boolean isModelLoaded() {
        return loaded.get() && LlamaTranslator.nativeIsLoaded();
    }

    /** Cancel any in-flight translation. Returns immediately. */
    public void cancel() {
        LlamaTranslator.nativeCancel();
    }

    /** Forget cached translations and toggle state for all comments. */
    public void clearCache() {
        translations.clear();
        showingTranslation.clear();
    }

    @Nullable
    public String getCachedTranslation(@NonNull final String key) {
        return translations.get(key);
    }

    public void rememberTranslation(@NonNull final String key,
                                    @NonNull final String translated) {
        translations.put(key, translated);
    }

    public boolean isShowingTranslation(@NonNull final String key) {
        return showingTranslation.contains(key);
    }

    public void setShowingTranslation(@NonNull final String key, final boolean showing) {
        if (showing) {
            showingTranslation.add(key);
        } else {
            showingTranslation.remove(key);
        }
    }

    public boolean isInFlight(@NonNull final String key) {
        return inFlight.contains(key);
    }

    public void markInFlight(@NonNull final String key) {
        inFlight.add(key);
    }

    public void markDone(@NonNull final String key) {
        inFlight.remove(key);
    }

    public synchronized void beginAutoTranslateSession(@NonNull final String sessionId) {
        autoTranslateSession = sessionId;
        autoTranslateCount = 0;
    }

    public synchronized void endAutoTranslateSession(@NonNull final String sessionId) {
        if (sessionId.equals(autoTranslateSession)) {
            autoTranslateSession = null;
            autoTranslateCount = 0;
        }
    }

    public boolean isAutoTranslateActive() {
        return autoTranslateSession != null;
    }

    public synchronized boolean tryConsumeAutoTranslateSlot() {
        if (autoTranslateSession == null) {
            return false;
        }
        if (autoTranslateCount >= MAX_AUTO_TRANSLATIONS) {
            return false;
        }
        autoTranslateCount++;
        return true;
    }

    @MainThread
    public void translateAsync(@NonNull final String text,
                               @NonNull final Callback callback) {
        final String target = getTargetLanguage(appContext);
        final String src = text;

        executor.execute(() -> {
            try {
                ensureLoadedBlocking();
            } catch (final Exception e) {
                Log.e(TAG, "model load failed", e);
                postFailure(callback, e.getMessage() != null ? e.getMessage()
                        : "Failed to load translation model");
                return;
            }

            try {
                Log.i(TAG, "translateAsync: calling nativeTranslate, src len="
                        + src.length() + " target=" + target);
                final long t0 = System.currentTimeMillis();
                final String out = LlamaTranslator.nativeTranslate(
                        src, target, MAX_NEW_TOKENS);
                final long elapsed = System.currentTimeMillis() - t0;
                Log.i(TAG, "translateAsync: nativeTranslate returned in " + elapsed
                        + "ms, out=" + (out == null ? "<null>"
                        : "len=" + out.length() + " text=" + truncate(out, 120)));
                if (out == null) {
                    postFailure(callback, "Translation failed");
                } else {
                    postSuccess(callback, out);
                }
            } catch (final Throwable t) {
                Log.e(TAG, "native translate threw", t);
                postFailure(callback, t.getMessage() != null
                        ? t.getMessage() : "Translation failed");
            }
        });
    }

    private void ensureLoadedBlocking() throws IOException {
        if (!LlamaTranslator.isLibAvailable()) {
            throw new IOException(
                    "Translation native library not available on this device");
        }
        if (loaded.get() && LlamaTranslator.nativeIsLoaded()) {
            return;
        }
        synchronized (this) {
            if (loaded.get() && LlamaTranslator.nativeIsLoaded()) {
                return;
            }
            final String uriStr = getModelUriString(appContext);
            if (TextUtils.isEmpty(uriStr)) {
                throw new IOException("No translation model selected in settings");
            }
            final String path;
            // If the stored value already looks like a real filesystem path,
            // use it directly (preferred — llama.cpp can mmap a real file).
            if (uriStr.startsWith("/")) {
                path = uriStr;
                Log.i(TAG, "ensureLoaded: using direct path=" + path);
            } else {
                path = resolvePath(Uri.parse(uriStr));
            }

            final int threads = Math.min(
                    4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

            final boolean ok = LlamaTranslator.nativeLoad(path, N_CTX, threads);
            if (!ok) {
                throw new IOException("Failed to load model from " + path);
            }
            loaded.set(true);
        }
    }

    private String resolvePath(@NonNull final Uri uri) throws IOException {
        Log.i(TAG, "resolvePath: uri=" + uri + " scheme=" + uri.getScheme());
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            Log.i(TAG, "resolvePath: returning direct path=" + uri.getPath());
            return uri.getPath();
        }
        final ParcelFileDescriptor pfd =
                appContext.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) {
            throw new IOException("Could not open model URI: " + uri);
        }
        modelFd = pfd;
        final long size = pfd.getStatSize();
        final String path = "/proc/self/fd/" + pfd.getFd();
        Log.i(TAG, "resolvePath: opened pfd fd=" + pfd.getFd()
                + " size=" + size + " path=" + path);
        return path;
    }

    @Nullable
    private ParcelFileDescriptor modelFd;

    /** Free native resources. Safe to call from any thread. */
    public void shutdown() {
        executor.execute(() -> {
            LlamaTranslator.nativeRelease();
            loaded.set(false);
            if (modelFd != null) {
                try {
                    modelFd.close();
                } catch (final IOException ignored) {
                    // ignored
                }
                modelFd = null;
            }
        });
    }

    private void postSuccess(@NonNull final Callback cb, @NonNull final String out) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> {
                    Log.i(TAG, "postSuccess: dispatching onSuccess");
                    cb.onSuccess(out);
                });
    }

    private void postFailure(@NonNull final Callback cb, @NonNull final String err) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> {
                    Log.w(TAG, "postFailure: dispatching onFailure: " + err);
                    cb.onFailure(err);
                });
    }

    private static String truncate(final String s, final int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    public interface Callback {
        @MainThread void onSuccess(@NonNull String translated);
        @MainThread void onFailure(@NonNull String error);
    }
}
