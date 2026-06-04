package org.schabi.newpipe.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class PersistentPlayerLogger {
    private static final String TAG = PersistentPlayerLogger.class.getSimpleName();
    private static final String LOG_FILE_NAME = "player-ui-events.log";
    private static final String OLD_LOG_FILE_NAME = "player-ui-events.log.1";
    private static final long MAX_LOG_SIZE_BYTES = 64 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US);

    private PersistentPlayerLogger() {
    }

    public static void log(@Nullable final Context context, @NonNull final String event) {
        Log.d(TAG, event);
        if (context == null) {
            return;
        }

        synchronized (LOCK) {
            final File logFile = new File(context.getApplicationContext().getFilesDir(),
                    LOG_FILE_NAME);
            rotateIfNeeded(logFile);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(DATE_FORMAT.format(new Date()));
                writer.write(" ");
                writer.write(event);
                writer.newLine();
            } catch (final IOException e) {
                Log.e(TAG, "Could not write persistent player log", e);
            }
        }
    }

    private static void rotateIfNeeded(@NonNull final File logFile) {
        if (logFile.length() <= MAX_LOG_SIZE_BYTES) {
            return;
        }

        final File oldLogFile = new File(logFile.getParentFile(), OLD_LOG_FILE_NAME);
        if (oldLogFile.exists() && !oldLogFile.delete()) {
            Log.w(TAG, "Could not delete old persistent player log");
        }
        if (!logFile.renameTo(oldLogFile)) {
            Log.w(TAG, "Could not rotate persistent player log");
        }
    }
}
