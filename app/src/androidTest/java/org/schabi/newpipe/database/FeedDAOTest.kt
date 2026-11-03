package org.schabi.newpipe.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.reactivex.rxjava3.core.Single
import java.io.IOException
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.feed.dao.FeedDAO
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.dao.StreamDAO
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionDAO
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.stream.StreamType

class FeedDAOTest {
    private lateinit var db: AppDatabase
    private lateinit var feedDAO: FeedDAO
    private lateinit var streamDAO: StreamDAO
    private lateinit var subscriptionDAO: SubscriptionDAO

    private val serviceId = ServiceList.YouTube.serviceId

    private val stream1 = stream(1, "2023-01-01T00:00:00Z")
    private val stream2 = stream(2, "2023-01-02T00:00:00Z")
    private val stream3 = stream(3, "2023-01-03T00:00:00Z", StreamType.LIVE_STREAM)
    private val stream4 = stream(4, "2023-08-10T00:00:00Z", uploaderNumber = 2)
    private val stream5 = stream(5, "2023-08-20T00:00:00Z", uploaderNumber = 2)
    private val stream6 = stream(6, "2023-09-01T00:00:00Z", uploaderNumber = 3)
    private val stream7 = stream(7, "2023-08-10T00:00:00Z", uploaderNumber = 4)

    private val allStreams = listOf(
        stream1,
        stream2,
        stream3,
        stream4,
        stream5,
        stream6,
        stream7
    )

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).build()
        feedDAO = db.feedDAO()
        streamDAO = db.streamDAO()
        subscriptionDAO = db.subscriptionDAO()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun testUnlinkStreamsOlderThan_KeepOne() {
        setupUnlinkDelete("2023-08-15T00:00:00Z")
        val streams = feedDAO.getStreams(
            FeedGroupEntity.GROUP_ALL_ID,
            includePlayed = true,
            includePartiallyPlayed = true,
            uploadDateBefore = null,
            uploadDateAfter = null
        )
            .blockingGet()
        val allowedStreams = listOf(stream3, stream5, stream6, stream7)
        assertEqual(streams, allowedStreams)
    }

    @Test
    fun testUnlinkStreamsOlderThan_KeepMultiple() {
        setupUnlinkDelete("2023-08-01T00:00:00Z")
        val streams = feedDAO.getStreams(
            FeedGroupEntity.GROUP_ALL_ID,
            includePlayed = true,
            includePartiallyPlayed = true,
            uploadDateBefore = null,
            uploadDateAfter = null
        )
            .blockingGet()
        val allowedStreams = listOf(stream3, stream4, stream5, stream6, stream7)
        assertEqual(streams, allowedStreams)
    }

    @Test
    fun getStreamsMarksWatchLaterMembership() {
        clearAndFillTables()
        val playlistId = db.playlistDAO().insert(
            PlaylistEntity(
                0,
                PlaylistEntity.WATCH_LATER_NAME,
                false,
                PlaylistEntity.DEFAULT_THUMBNAIL_ID,
                0
            )
        )
        db.playlistStreamDAO().insert(PlaylistStreamEntity(playlistId, stream1.uid, 0))

        val streams = feedDAO.getStreams(
            FeedGroupEntity.GROUP_ALL_ID,
            includePlayed = true,
            includePartiallyPlayed = true,
            uploadDateBefore = null,
            uploadDateAfter = null
        ).blockingGet(emptyList()).associateBy { it.stream.uid }

        assertTrue(streams.getValue(stream1.uid).isInWatchLater)
        assertFalse(streams.getValue(stream2.uid).isInWatchLater)
    }

    private fun stream(
        uid: Long,
        uploadDate: String,
        type: StreamType = StreamType.VIDEO_STREAM,
        uploaderNumber: Int = 1
    ) = StreamEntity(
        uid,
        serviceId,
        "https://example.invalid/stream/$uid",
        "stream $uid",
        type,
        1000,
        "uploader-$uploaderNumber",
        "https://example.invalid/uploader/$uploaderNumber",
        "https://example.invalid/thumbnail/$uid",
        100,
        uploadDate.substringBefore('T'),
        OffsetDateTime.parse(uploadDate)
    )

    private fun assertEqual(streams: List<StreamWithState>?, allowedStreams: List<StreamEntity>) {
        assertNotNull(streams)
        assertEquals(
            allowedStreams,
            streams!!
                .map { it.stream }
                .sortedBy { it.uid }
                .toList()
        )
    }

    private fun setupUnlinkDelete(time: String) {
        clearAndFillTables()
        Single.fromCallable {
            feedDAO.unlinkStreamsOlderThan(OffsetDateTime.parse(time))
        }.blockingSubscribe()
        Single.fromCallable {
            streamDAO.deleteOrphans()
        }.blockingSubscribe()
    }

    private fun clearAndFillTables() {
        db.clearAllTables()
        streamDAO.insertAll(allStreams)
        subscriptionDAO.insertAll(
            listOf(
                SubscriptionEntity.from(ChannelInfo(serviceId, "1", "https://example.invalid/uploader/1", "https://example.invalid/uploader/1", "uploader-1")),
                SubscriptionEntity.from(ChannelInfo(serviceId, "2", "https://example.invalid/uploader/2", "https://example.invalid/uploader/2", "uploader-2")),
                SubscriptionEntity.from(ChannelInfo(serviceId, "3", "https://example.invalid/uploader/3", "https://example.invalid/uploader/3", "uploader-3")),
                SubscriptionEntity.from(ChannelInfo(serviceId, "4", "https://example.invalid/uploader/4", "https://example.invalid/uploader/4", "uploader-4"))
            )
        )
        feedDAO.insertAll(
            listOf(
                FeedEntity(1, 1),
                FeedEntity(2, 1),
                FeedEntity(3, 1),
                FeedEntity(4, 2),
                FeedEntity(5, 2),
                FeedEntity(6, 3),
                FeedEntity(7, 4)
            )
        )
    }
}
