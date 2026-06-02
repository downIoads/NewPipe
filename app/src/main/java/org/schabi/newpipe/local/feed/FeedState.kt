package org.schabi.newpipe.local.feed

import androidx.annotation.StringRes
import java.time.OffsetDateTime
import org.schabi.newpipe.local.feed.item.StreamItem

sealed class FeedState {
    data class ProgressState(
        val currentProgress: Int = -1,
        val maxProgress: Int = -1,
        @StringRes val progressMessage: Int = 0
    ) : FeedState()

    data class LoadedState(
        val items: List<StreamItem>,
        val oldestUpdate: OffsetDateTime?,
        val notLoadedCount: Long,
        val itemsErrors: List<Throwable>,
        /**
         * True only when this state was produced by a just-finished feed load
         * (a `SuccessResultEvent`), as opposed to the idle/initial state or a re-emission
         * caused by filter changes. Used to drive the not-loaded auto-retry exactly once per
         * completed load.
         */
        val loadJustCompleted: Boolean = false
    ) : FeedState()

    data class ErrorState(
        val error: Throwable? = null
    ) : FeedState()
}
