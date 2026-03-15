package org.schabi.newpipe.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes debug logs to a file on the device so they can be retrieved later
 * via {@code adb pull /sdcard/Android/data/org.schabi.newpipe.debug/cache/debug_log.txt}
 * (or the equivalent for the release package).
 *
 * <p>The log file is kept in the app's external cache directory so no extra
 * permissions are needed. It is capped at ~512 KB and rotated automatically.</p>
 */
public final class DebugFileLog {

    private static final String TAG = "DebugFileLog";
    private static final String LOG_FILE_NAME = "debug_playback_log.txt";
    private static final String LOG_FILE_OLD = "debug_playback_log_old.txt";
    private static final long MAX_FILE_SIZE = 512 * 1024; // 512 KB

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private DebugFileLog() {
    }

    /**
     * Appends a timestamped line to the debug log file.
     *
     * @param tag     the log tag
     * @param message the log message
     */
    public static void log(@NonNull final String tag, @NonNull final String message) {
        try {
            final Context ctx = App.getApp();
            final File dir = ctx.getExternalCacheDir();
            if (dir == null) {
                return;
            }

            final File logFile = new File(dir, LOG_FILE_NAME);

            // Rotate if too large
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                final File old = new File(dir, LOG_FILE_OLD);
                if (old.exists()) {
                    old.delete();
                }
                logFile.renameTo(old);
            }

            final String timestamp = DATE_FORMAT.format(new Date());
            final String line = timestamp + " [" + tag + "] " + message + "\n";

            try (FileOutputStream fos = new FileOutputStream(logFile, true);
                 OutputStreamWriter writer =
                         new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(line);
                writer.flush();
            }
        } catch (final IOException e) {
            Log.w(TAG, "Failed to write debug log", e);
        }
    }

    /**
     * Convenience overload that also logs the throwable's message and first
     * few stack frames.
     *
     * @param tag       the log tag
     * @param message   the log message
     * @param throwable the throwable to log (may be null)
     */
    public static void log(@NonNull final String tag,
                           @NonNull final String message,
                           @Nullable final Throwable throwable) {
        final StringBuilder sb = new StringBuilder(message);
        if (throwable != null) {
            sb.append(" | exception=").append(throwable.getClass().getSimpleName())
                    .append(": ").append(throwable.getMessage());
            final StackTraceElement[] stack = throwable.getStackTrace();
            final int framesToLog = Math.min(stack.length, 5);
            for (int i = 0; i < framesToLog; i++) {
                sb.append("\n    at ").append(stack[i]);
            }
            if (throwable.getCause() != null) {
                sb.append("\n  caused by ")
                        .append(throwable.getCause().getClass().getSimpleName())
                        .append(": ").append(throwable.getCause().getMessage());
            }
        }
        log(tag, sb.toString());
    }

    /**
     * Returns the path to the log file, for display purposes.
     *
     * @return the absolute path, or null if unavailable
     */
    @Nullable
    public static String getLogFilePath() {
        try {
            final Context ctx = App.getApp();
            final File dir = ctx.getExternalCacheDir();
            if (dir == null) {
                return null;
            }
            return new File(dir, LOG_FILE_NAME).getAbsolutePath();
        } catch (final Exception e) {
            return null;
        }
    }
}
