package org.schabi.newpipe.local.playlist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity

class WatchLaterPlaylistDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).build()
    }

    @After
    @Throws(IOException::class)
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun watchLaterIsCreatedEmptyAndProtectedFromDeletion() {
        LocalPlaylistManager(database).ensureWatchLaterPlaylist().blockingAwait()

        val playlistId = database.playlistDAO().getPlaylistIdByName(
            PlaylistEntity.WATCH_LATER_NAME
        )
        assertNotNull(playlistId)
        assertEquals(0, database.playlistDAO().deletePlaylist(playlistId!!))
        assertEquals(1, database.playlistDAO().getAll().blockingFirst().size)
    }

    @Test
    fun ordinaryPlaylistCanStillBeDeleted() {
        LocalPlaylistManager(database).ensureWatchLaterPlaylist().blockingAwait()
        val playlistId = database.playlistDAO().insert(
            PlaylistEntity(0, "Ordinary playlist", false, PlaylistEntity.DEFAULT_THUMBNAIL_ID, 0)
        )

        assertEquals(1, database.playlistDAO().deletePlaylist(playlistId))
        assertEquals(1, database.playlistDAO().getAll().blockingFirst().size)
    }
}
