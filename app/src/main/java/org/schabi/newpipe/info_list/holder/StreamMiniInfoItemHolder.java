package org.schabi.newpipe.info_list.holder;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.image.PicassoHelper;
import org.schabi.newpipe.util.StreamTypeUtil;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

public class StreamMiniInfoItemHolder extends InfoItemHolder {
    public final ImageView itemThumbnailView;
    public final TextView itemVideoTitleView;
    public final TextView itemUploaderView;
    public final TextView itemDurationView;
    private final AnimatedProgressBar itemProgressView;
    private Disposable streamStateDisposable;
    private String boundStreamStateKey;

    StreamMiniInfoItemHolder(final InfoItemBuilder infoItemBuilder, final int layoutId,
                             final ViewGroup parent) {
        super(infoItemBuilder, layoutId, parent);

        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemVideoTitleView = itemView.findViewById(R.id.itemVideoTitleView);
        itemUploaderView = itemView.findViewById(R.id.itemUploaderView);
        itemDurationView = itemView.findViewById(R.id.itemDurationView);
        itemProgressView = itemView.findViewById(R.id.itemProgressView);
    }

    public StreamMiniInfoItemHolder(final InfoItemBuilder infoItemBuilder, final ViewGroup parent) {
        this(infoItemBuilder, R.layout.list_stream_mini_item, parent);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        if (!(infoItem instanceof StreamInfoItem)) {
            return;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;

        itemVideoTitleView.setText(item.getName());
        final String uploadDate = Localization.relativeTimeOrTextualShortWithDate(
                itemBuilder.getContext(),
                item.getUploadDate(),
                item.getTextualUploadDate());
        final String uploaderName = Localization.truncateChannelName(item.getUploaderName());
        if (TextUtils.isEmpty(uploadDate)) {
            itemUploaderView.setText(uploaderName);
        } else {
            itemUploaderView.setText(Localization.highlightScheduled(
                    itemBuilder.getContext(), uploaderName + "\n" + uploadDate));
        }

        if (item.getDuration() > 0) {
            itemDurationView.setText(Localization.getDurationString(item.getDuration()));
            itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.getContext(),
                    R.color.duration_background_color));
            itemDurationView.setVisibility(View.VISIBLE);

            loadStreamState(infoItem, item, historyRecordManager, false);
        } else if (StreamTypeUtil.isLiveStream(item.getStreamType())) {
            clearStreamStateLoad();
            itemDurationView.setText(R.string.duration_live);
            itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.getContext(),
                    R.color.live_duration_background_color));
            itemDurationView.setVisibility(View.VISIBLE);
            itemProgressView.setVisibility(View.GONE);
        } else {
            clearStreamStateLoad();
            itemDurationView.setVisibility(View.GONE);
            itemProgressView.setVisibility(View.GONE);
        }

        // Default thumbnail is shown on error, while loading and if the url is empty
        PicassoHelper.loadThumbnail(item.getThumbnails()).into(itemThumbnailView);

        itemView.setOnClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().selected(item);
            }
        });

        switch (item.getStreamType()) {
            case AUDIO_STREAM:
            case VIDEO_STREAM:
            case LIVE_STREAM:
            case AUDIO_LIVE_STREAM:
            case POST_LIVE_STREAM:
            case POST_LIVE_AUDIO_STREAM:
                enableLongClick(item);
                break;
            case NONE:
            default:
                disableLongClick();
                break;
        }
    }

    @Override
    public void updateState(final InfoItem infoItem,
                            final HistoryRecordManager historyRecordManager) {
        final StreamInfoItem item = (StreamInfoItem) infoItem;
        loadStreamState(infoItem, item, historyRecordManager, true);
    }

    @Override
    public void clear() {
        clearStreamStateLoad();
    }

    private void loadStreamState(final InfoItem infoItem,
                                 final StreamInfoItem item,
                                 final HistoryRecordManager historyRecordManager,
                                 final boolean animate) {
        clearStreamStateLoad();
        if (!DependentPreferenceHelper.getPositionsInListsEnabled(itemProgressView.getContext())
                || item.getDuration() <= 0
                || StreamTypeUtil.isLiveStream(item.getStreamType())) {
            updateProgress(item, null, animate);
            return;
        }

        boundStreamStateKey = streamStateKey(infoItem);
        streamStateDisposable = historyRecordManager.loadStreamState(infoItem)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(states -> {
                    if (!streamStateKey(infoItem).equals(boundStreamStateKey)) {
                        return;
                    }
                    updateProgress(item, states[0], animate);
                }, throwable -> updateProgress(item, null, animate));
    }

    private void updateProgress(final StreamInfoItem item,
                                final StreamStateEntity state,
                                final boolean animate) {
        if (state != null && item.getDuration() > 0
                && !StreamTypeUtil.isLiveStream(item.getStreamType())) {
            itemProgressView.setMax((int) item.getDuration());
            if (animate && itemProgressView.getVisibility() == View.VISIBLE) {
                itemProgressView.setProgressAnimated((int) TimeUnit.MILLISECONDS
                        .toSeconds(state.getProgressMillis()));
            } else {
                itemProgressView.setProgress((int) TimeUnit.MILLISECONDS
                        .toSeconds(state.getProgressMillis()));
                if (animate) {
                    ViewUtils.animate(itemProgressView, true, 500);
                } else {
                    itemProgressView.setVisibility(View.VISIBLE);
                }
            }
        } else if (animate && itemProgressView.getVisibility() == View.VISIBLE) {
            ViewUtils.animate(itemProgressView, false, 500);
        } else {
            itemProgressView.setVisibility(View.GONE);
        }
    }

    private void clearStreamStateLoad() {
        if (streamStateDisposable != null) {
            streamStateDisposable.dispose();
            streamStateDisposable = null;
        }
        boundStreamStateKey = null;
    }

    private String streamStateKey(final InfoItem item) {
        return item.getServiceId() + ":" + item.getUrl();
    }

    private void enableLongClick(final StreamInfoItem item) {
        itemView.setLongClickable(true);
        itemView.setOnLongClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().held(item);
            }
            return true;
        });
    }

    private void disableLongClick() {
        itemView.setLongClickable(false);
        itemView.setOnLongClickListener(null);
    }
}
