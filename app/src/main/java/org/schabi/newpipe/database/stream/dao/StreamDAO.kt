package org.schabi.newpipe.database.stream.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import java.time.OffsetDateTime
import org.schabi.newpipe.database.BasicDAO
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity.Companion.STREAM_ID
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.StreamTypeUtil

@Dao
abstract class StreamDAO : BasicDAO<StreamEntity> {
    @Query("SELECT * FROM streams")
    abstract override fun getAll(): Flowable<List<StreamEntity>>

    @Query("DELETE FROM streams")
    abstract override fun deleteAll(): Int

    @Query("SELECT * FROM streams WHERE service_id = :serviceId")
    abstract override fun listByService(serviceId: Int): Flowable<List<StreamEntity>>

    @Query("SELECT * FROM streams WHERE url = :url AND service_id = :serviceId")
    abstract fun getStream(serviceId: Long, url: String): Flowable<List<StreamEntity>>

    @Query("UPDATE streams SET uploader_url = :uploaderUrl WHERE url = :url AND service_id = :serviceId")
    abstract fun setUploaderUrl(serviceId: Long, url: String, uploaderUrl: String): Completable

    /**
     * Fill in a stream's duration only if it is currently unknown (`< 0`). Used by the feed's
     * background duration enrichment: the fast RSS feed path carries no duration, so after the
     * refresh we look durations up and patch them in here. The `duration < 0` guard makes this a
     * no-op for streams that already have a real duration, so it never clobbers better data.
     *
     * @return the number of rows updated (0 or 1).
     */
    @Query(
        "UPDATE streams SET duration = :duration " +
            "WHERE url = :url AND service_id = :serviceId AND duration < 0"
    )
    abstract fun setDurationIfMissing(serviceId: Int, url: String, duration: Long): Int

    /**
     * Like [setDurationIfMissing], but also rewrites the stream type. Used by the feed's background
     * enrichment for past livestreams that are now available as recordings: the fast RSS path
     * stores every entry as a plain [StreamType.VIDEO_STREAM] with no duration, so once we discover
     * (via the channel's Livestreams tab) that an entry is actually an ended livestream, we patch in
     * both its duration and [StreamType.POST_LIVE_STREAM] so the feed shows the length overlay and
     * the "Livestream Recording" label. The `duration < 0` guard keeps this a no-op for entries
     * that already carry a real duration, so it never clobbers better data.
     *
     * @return the number of rows updated (0 or 1).
     */
    @Query(
        "UPDATE streams SET duration = :duration, stream_type = :streamType " +
            "WHERE url = :url AND service_id = :serviceId AND duration < 0"
    )
    abstract fun setDurationAndTypeIfMissing(
        serviceId: Int,
        url: String,
        duration: Long,
        streamType: StreamType
    ): Int

    /**
     * Of the given URLs, return those whose stored stream has an unknown duration (`< 0`). Used by
     * the feed's background duration enrichment to decide, per channel, whether a (costly) Videos-
     * tab fetch is even needed: once durations are filled in, this returns empty and no fetch
     * happens, so steady-state refreshes do no extra network work.
     */
    @Query(
        "SELECT url FROM streams " +
            "WHERE service_id = :serviceId AND duration < 0 AND url IN (:urls)"
    )
    abstract fun urlsWithMissingDuration(serviceId: Int, urls: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract fun silentInsertInternal(stream: StreamEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract fun silentInsertAllInternal(streams: List<StreamEntity>): List<Long>

    @Query("SELECT COUNT(*) != 0 FROM streams WHERE url = :url AND service_id = :serviceId")
    internal abstract fun exists(serviceId: Int, url: String): Boolean

    @Query(
        """
        SELECT uid, stream_type, textual_upload_date, upload_date, is_upload_date_approximation, duration 
        FROM streams WHERE url = :url AND service_id = :serviceId
        """
    )
    internal abstract fun getMinimalStreamForCompare(serviceId: Int, url: String): StreamCompareFeed?

    @Transaction
    open fun upsert(newerStream: StreamEntity): Long {
        val uid = silentInsertInternal(newerStream)

        if (uid != -1L) {
            newerStream.uid = uid
            return uid
        }

        compareAndUpdateStream(newerStream)

        update(newerStream)
        return newerStream.uid
    }

    @Transaction
    open fun upsertAll(streams: List<StreamEntity>): List<Long> {
        val insertUidList = silentInsertAllInternal(streams)

        val streamIds = ArrayList<Long>(streams.size)
        for ((index, uid) in insertUidList.withIndex()) {
            val newerStream = streams[index]
            if (uid != -1L) {
                streamIds.add(uid)
                newerStream.uid = uid
                continue
            }

            compareAndUpdateStream(newerStream)
            streamIds.add(newerStream.uid)
        }

        update(streams)
        return streamIds
    }

    private fun compareAndUpdateStream(newerStream: StreamEntity) {
        val existentMinimalStream = getMinimalStreamForCompare(newerStream.serviceId, newerStream.url)
            ?: throw IllegalStateException("Stream cannot be null just after insertion.")
        newerStream.uid = existentMinimalStream.uid

        if (!StreamTypeUtil.isLiveStream(newerStream.streamType)) {
            // Use the existent upload date if the newer stream does not have a better precision
            // (i.e. is an approximation). This is done to prevent unnecessary changes.
            val hasBetterPrecision =
                newerStream.uploadDate != null && newerStream.isUploadDateApproximation != true
            if (existentMinimalStream.uploadDate != null && !hasBetterPrecision) {
                newerStream.uploadDate = existentMinimalStream.uploadDate
                newerStream.textualUploadDate = existentMinimalStream.textualUploadDate
                newerStream.isUploadDateApproximation = existentMinimalStream.isUploadDateApproximation
            }

            if (existentMinimalStream.duration > 0 && newerStream.duration < 0) {
                newerStream.duration = existentMinimalStream.duration
            }
        }
    }

    @Query(
        """
        DELETE FROM streams WHERE

        NOT EXISTS (SELECT 1 FROM stream_history sh
        WHERE sh.stream_id = streams.uid)

        AND NOT EXISTS (SELECT 1 FROM playlist_stream_join ps
        WHERE ps.stream_id = streams.uid)

        AND NOT EXISTS (SELECT 1 FROM feed f
        WHERE f.stream_id = streams.uid)
        """
    )
    abstract fun deleteOrphans(): Int

    /**
     * Minimal entry class used when comparing/updating an existent stream.
     */
    internal data class StreamCompareFeed(
        @ColumnInfo(name = STREAM_ID)
        var uid: Long = 0,

        @ColumnInfo(name = StreamEntity.STREAM_TYPE)
        var streamType: StreamType,

        @ColumnInfo(name = StreamEntity.STREAM_TEXTUAL_UPLOAD_DATE)
        var textualUploadDate: String? = null,

        @ColumnInfo(name = StreamEntity.STREAM_UPLOAD_DATE)
        var uploadDate: OffsetDateTime? = null,

        @ColumnInfo(name = StreamEntity.STREAM_IS_UPLOAD_DATE_APPROXIMATION)
        var isUploadDateApproximation: Boolean? = null,

        @ColumnInfo(name = StreamEntity.STREAM_DURATION)
        var duration: Long
    )
}
