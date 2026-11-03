package org.schabi.newpipe.local.playlist

import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.dao.PlaylistDAO
import org.schabi.newpipe.database.playlist.model.PlaylistEntity

class WatchLaterPlaylistTest {
    private val database = mock(AppDatabase::class.java)
    private val playlists = mock(PlaylistDAO::class.java)

    @Before
    fun setup() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        `when`(database.playlistDAO()).thenReturn(playlists)
    }

    @After
    fun cleanup() {
        RxJavaPlugins.reset()
    }

    @Test
    fun watchLaterPlaylistCannotBeRenamed() {
        val watchLater = PlaylistEntity(
            5,
            PlaylistEntity.WATCH_LATER_NAME,
            false,
            PlaylistEntity.DEFAULT_THUMBNAIL_ID,
            0
        )
        `when`(playlists.getPlaylist(5)).thenReturn(Flowable.just(mutableListOf(watchLater)))

        LocalPlaylistManager(database).renamePlaylist(5, "Renamed").test()
            .assertComplete()
            .assertNoValues()

        verify(playlists, never()).update(watchLater)
    }

    @Test
    fun anotherPlaylistCannotTakeReservedName() {
        LocalPlaylistManager(database).renamePlaylist(9, PlaylistEntity.WATCH_LATER_NAME).test()
            .assertComplete()
            .assertNoValues()

        verify(playlists, never()).getPlaylist(9)
    }
}
