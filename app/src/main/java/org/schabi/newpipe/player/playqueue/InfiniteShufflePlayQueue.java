package org.schabi.newpipe.player.playqueue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A play queue that continuously appends a random item from its source list.
 */
public final class InfiniteShufflePlayQueue extends PlayQueue {
    private final List<PlayQueueItem> sourceItems;
    private final Random random = new Random();

    public InfiniteShufflePlayQueue(@NonNull final List<StreamInfoItem> items) {
        super(0, initialPlayQueueItemsOf(items));
        sourceItems = playQueueItemsOf(items);
    }

    private static List<PlayQueueItem> initialPlayQueueItemsOf(
            @NonNull final List<StreamInfoItem> items) {
        final List<PlayQueueItem> sourceItems = playQueueItemsOf(items);
        final List<PlayQueueItem> initialItems = new ArrayList<>();
        final Random random = new Random();

        final PlayQueueItem firstItem = randomItemDifferentFrom(sourceItems, null, random);
        if (firstItem == null) {
            return initialItems;
        }

        initialItems.add(firstItem);

        final PlayQueueItem secondItem = randomItemDifferentFrom(sourceItems, firstItem, random);
        if (secondItem != null) {
            initialItems.add(secondItem);
        }

        return initialItems;
    }

    private static List<PlayQueueItem> playQueueItemsOf(
            @NonNull final List<StreamInfoItem> items) {
        final List<PlayQueueItem> playQueueItems = new ArrayList<>(items.size());
        for (final StreamInfoItem item : items) {
            playQueueItems.add(new PlayQueueItem(item));
        }
        return playQueueItems;
    }

    @Nullable
    private static PlayQueueItem randomItemDifferentFrom(
            @NonNull final List<PlayQueueItem> items,
            @Nullable final PlayQueueItem previousItem,
            @NonNull final Random random) {
        if (items.isEmpty()) {
            return null;
        }

        final List<PlayQueueItem> candidates = new ArrayList<>(items.size());
        for (final PlayQueueItem item : items) {
            if (!item.isSameItem(previousItem)) {
                candidates.add(item);
            }
        }

        final List<PlayQueueItem> itemsToPickFrom = candidates.isEmpty() ? items : candidates;
        return new PlayQueueItem(itemsToPickFrom.get(random.nextInt(itemsToPickFrom.size())));
    }

    @Override
    public boolean isComplete() {
        return sourceItems.isEmpty();
    }

    @Override
    public synchronized void offsetIndex(final int offset) {
        if (offset == 1) {
            appendRandomItemAfterCurrent();
        }
        super.offsetIndex(offset);
    }

    @Override
    public void fetch() {
        appendRandomItemAtEnd();
    }

    private void appendRandomItemAfterCurrent() {
        final PlayQueueItem nextItem = randomItemDifferentFrom(
                sourceItems,
                getItem(),
                random
        );
        if (nextItem != null) {
            append(List.of(nextItem));
            move(size() - 1, getIndex() + 1);
        }
    }

    private void appendRandomItemAtEnd() {
        final PlayQueueItem nextItem = randomItemDifferentFrom(
                sourceItems,
                getItem(size() - 1),
                random
        );
        if (nextItem != null) {
            append(List.of(nextItem));
        }
    }
}
