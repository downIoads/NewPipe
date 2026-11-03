package org.schabi.newpipe.database.stream

import androidx.room.ColumnInfo
import androidx.room.Embedded
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity

data class StreamWithState(
    @Embedded
    val stream: StreamEntity,

    @ColumnInfo(name = StreamStateEntity.STREAM_PROGRESS_MILLIS)
    val stateProgressMillis: Long?,

    @ColumnInfo(name = STREAM_IS_IN_WATCH_LATER)
    val isInWatchLater: Boolean
) {
    companion object {
        const val STREAM_IS_IN_WATCH_LATER = "is_in_watch_later"
    }
}
