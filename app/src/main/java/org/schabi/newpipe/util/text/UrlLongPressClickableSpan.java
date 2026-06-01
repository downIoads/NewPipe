package org.schabi.newpipe.util.text;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.external_communication.ShareUtils;

final class UrlLongPressClickableSpan extends LongPressClickableSpan {

    @NonNull
    private final Context context;
    @NonNull
    private final String url;

    UrlLongPressClickableSpan(@NonNull final Context context,
                              @NonNull final String url) {
        this.context = context;
        this.url = url;
    }

    @Override
    public void onClick(@NonNull final View view) {
        // Internal timestamp links (e.g. YouTube "&t=") are handled in-app, so they don't
        // leave NewPipe and don't need a confirmation dialog.
        if (InternalUrlsHandler.handleUrlDescriptionTimestamp(context, url)) {
            return;
        }

        // A tap on a link inside a description or comment is easy to trigger accidentally and
        // would otherwise immediately leave the app to open the (potentially malicious) URL.
        // Ask the user for confirmation first, showing the full URL.
        new AlertDialog.Builder(context)
                .setTitle(R.string.open_url_confirmation_title)
                .setMessage(url)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open,
                        (dialog, which) -> ShareUtils.openUrlInApp(context, url))
                .show();
    }

    @Override
    public void onLongClick(@NonNull final View view) {
        ShareUtils.copyToClipboard(context, url);
    }
}
