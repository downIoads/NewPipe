package org.schabi.newpipe.error;

import android.content.Context;

import androidx.annotation.NonNull;

import org.acra.ReportField;
import org.acra.data.CrashReportData;
import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;
import org.json.JSONException;
import org.schabi.newpipe.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/*
 * Created by Christian Schabesberger  on 13.09.16.
 *
 * Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
 * AcraReportSender.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

public class AcraReportSender implements ReportSender {

    @Override
    public void send(@NonNull final Context context, @NonNull final CrashReportData report)
            throws ReportSenderException {
        // ACRA removes its queued copy when this sender returns, even if Android
        // blocked the background launch of ErrorActivity.
        try {
            saveReport(context, report);
        } catch (final IOException | JSONException e) {
            throw new ReportSenderException("Could not save the local crash report", e);
        }
        ErrorUtil.openActivity(context, new ErrorInfo(
                new String[]{report.getString(ReportField.STACK_TRACE)},
                UserAction.UI_ERROR,
                "ACRA report",
                null,
                R.string.app_ui_crash));
    }

    private static void saveReport(final Context context, final CrashReportData report)
            throws IOException, JSONException {
        final File directory = new File(context.getNoBackupFilesDir(), "crash-reports");
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create " + directory);
        }

        final byte[] json = report.toJSON().getBytes(StandardCharsets.UTF_8);
        final UUID id = reportId(report, json);
        final File destination = new File(directory, id + ".json");
        if (destination.isFile()) {
            return; // This report was already saved on a previous delivery attempt.
        }
        final File pending = File.createTempFile(id.toString(), ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(json);
                output.getFD().sync();
            }
            if (!pending.renameTo(destination)) {
                throw new IOException("Could not publish " + destination);
            }
        } finally {
            if (pending.exists()) {
                pending.delete();
            }
        }
    }

    private static UUID reportId(final CrashReportData report, final byte[] json) {
        try {
            return UUID.fromString(report.getString(ReportField.REPORT_ID));
        } catch (final IllegalArgumentException e) {
            // ACRA can omit fields when a collector fails during a crash.
            return UUID.nameUUIDFromBytes(json);
        }
    }
}
