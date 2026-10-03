package org.schabi.newpipe.local.playlist

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.dao.PlaylistStreamDAO
import org.schabi.newpipe.database.stream.dao.StreamDAO
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.ExtractorHelper

class LivePlaylistRefreshTest {
    private val database = mock(AppDatabase::class.java)
    private val streams = mock(StreamDAO::class.java)
    private val playlist = mock(PlaylistStreamDAO::class.java)

    @Before
    fun setup() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        `when`(database.streamDAO()).thenReturn(streams)
        `when`(database.playlistStreamDAO()).thenReturn(playlist)
    }

    @After
    fun cleanup() {
        RxJavaPlugins.reset()
    }

    @Test
    fun largeNonLivePlaylistDoesNotFetchMetadata() {
        val entries = (1..1000).map { entry(it.toLong(), StreamType.VIDEO_STREAM) } +
            entry(1001, StreamType.POST_LIVE_STREAM) +
            entry(1002, StreamType.VIDEO_STREAM).also {
                it.streamEntity.contentAvailability = ContentAvailability.UPCOMING
            }
        `when`(playlist.getStreamsWithoutDuplicates(1)).thenReturn(Flowable.just(entries.toMutableList()))
        mockStatic(ExtractorHelper::class.java).use { extractor ->
            LocalPlaylistManager(database).refreshLiveStreams(1).test().assertComplete()
            extractor.verifyNoInteractions()
            verifyNoInteractions(streams)
        }
    }

    @Test
    fun updatesViewerCountAndDetectsEndedStreamsDespiteAnotherFetchFailing() {
        val entries = listOf(
            entry(1, StreamType.LIVE_STREAM),
            entry(2, StreamType.AUDIO_LIVE_STREAM),
            entry(3, StreamType.LIVE_STREAM)
        )
        `when`(playlist.getStreamsWithoutDuplicates(1)).thenReturn(Flowable.just(entries.toMutableList()))
        mockStatic(ExtractorHelper::class.java).use { extractor ->
            entries.forEach { entry ->
                val info = mock(StreamInfo::class.java)
                val type = if (entry.streamId == 1L) StreamType.LIVE_STREAM else StreamType.POST_LIVE_AUDIO_STREAM
                `when`(info.streamType).thenReturn(type)
                `when`(info.viewCount).thenReturn(1234)
                `when`(info.duration).thenReturn(if (entry.streamId == 1L) -1 else 3600)
                `when`(info.contentAvailability).thenReturn(ContentAvailability.AVAILABLE)
                extractor.`when`<Single<StreamInfo>> {
                    ExtractorHelper.getStreamInfo(0, entry.streamEntity.url, true)
                }.thenReturn(if (entry.streamId == 3L) Single.error(Exception("offline")) else Single.just(info))
            }
            LocalPlaylistManager(database).refreshLiveStreams(1).test().assertComplete()
            verify(streams).updateLiveMetadata(1, StreamType.LIVE_STREAM, 1234, -1, ContentAvailability.AVAILABLE)
            verify(streams).updateLiveMetadata(2, StreamType.POST_LIVE_AUDIO_STREAM, 1234, 3600, ContentAvailability.AVAILABLE)
        }
    }

    private fun entry(uid: Long, type: StreamType) = PlaylistStreamEntry(
        StreamEntity(uid, 0, "https://example.com/$uid", "title", type, -1, "channel"),
        0,
        uid,
        uid.toInt()
    )
}
