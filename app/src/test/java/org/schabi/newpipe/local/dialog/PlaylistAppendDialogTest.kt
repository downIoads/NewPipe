package org.schabi.newpipe.local.dialog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.database.playlist.PlaylistDuplicatesEntry
import org.schabi.newpipe.database.playlist.model.PlaylistEntity

class PlaylistAppendDialogTest {
    @Test
    fun watchLaterIsMovedToTopWithoutReorderingOtherPlaylists() {
        val playlists = mutableListOf(
            playlist(1, "First"),
            playlist(2, "Second"),
            playlist(3, PlaylistEntity.WATCH_LATER_NAME),
            playlist(4, "Third")
        )

        PlaylistAppendDialog.moveWatchLaterToTop(playlists)

        assertEquals(
            listOf(PlaylistEntity.WATCH_LATER_NAME, "First", "Second", "Third"),
            playlists.map { it.orderingName }
        )
    }

    private fun playlist(uid: Long, name: String) = PlaylistDuplicatesEntry(
        uid = uid,
        thumbnailUrl = null,
        isThumbnailPermanent = false,
        thumbnailStreamId = PlaylistEntity.DEFAULT_THUMBNAIL_ID,
        displayIndex = uid,
        streamCount = 0,
        orderingName = name,
        timesStreamIsContained = 0
    )
}
