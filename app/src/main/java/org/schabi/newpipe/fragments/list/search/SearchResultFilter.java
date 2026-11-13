package org.schabi.newpipe.fragments.list.search;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.List;

final class SearchResultFilter {
    private SearchResultFilter() {
    }

    @NonNull
    static <T extends InfoItem> List<T> filter(@NonNull final List<T> items,
                                              final boolean showLive,
                                              final boolean showShorts) {
        if (showLive && showShorts) {
            return items;
        }

        final List<T> filtered = new ArrayList<>(items.size());
        for (final T item : items) {
            if (item instanceof StreamInfoItem) {
                final StreamInfoItem stream = (StreamInfoItem) item;
                final StreamType streamType = stream.getStreamType();
                if (!showLive && (streamType == StreamType.LIVE_STREAM
                        || streamType == StreamType.AUDIO_LIVE_STREAM)) {
                    continue;
                }
                if (!showShorts && stream.isShortFormContent()) {
                    continue;
                }
            }
            filtered.add(item);
        }
        return filtered;
    }
}
