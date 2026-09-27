package org.schabi.newpipe.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.player.SponsorBlockSegments.Segment

class SponsorBlockReplayTest {
    private val segments = listOf(Segment(10000, 20000), Segment(18000, 22000), Segment(40000, 45000))
    private val replay = SponsorBlockReplay()

    @Test
    fun `rewind before a skipped segment lets it play and later segments still skip`() {
        replay.onManualSeek(22500, 5000, segments, 60000)
        assertFalse(replay.shouldSkip(5000))
        assertFalse(replay.shouldSkip(10000))
        assertFalse(replay.shouldSkip(21000))
        assertTrue(replay.shouldSkip(22500))
        assertTrue(replay.shouldSkip(40000))
    }

    @Test
    fun `double tap rewind into skipped segment allows replay through its end`() {
        replay.onManualSeek(23000, 13000, segments, 60000)
        assertFalse(replay.shouldSkip(13000))
        assertFalse(replay.shouldSkip(21999))
        assertTrue(replay.shouldSkip(23000))
    }

    @Test
    fun `repeated rewinds preserve original replay boundary`() {
        replay.onManualSeek(23000, 13000, segments, 60000)
        replay.onManualSeek(13000, 3000, segments, 60000)
        assertFalse(replay.shouldSkip(21000))
        assertTrue(replay.shouldSkip(23000))
    }

    @Test
    fun `manual forward seek inside segment allows entire overlapping group`() {
        replay.onManualSeek(0, 12000, segments, 60000)
        assertFalse(replay.shouldSkip(12000))
        assertFalse(replay.shouldSkip(21999))
        assertTrue(replay.shouldSkip(22000))
    }

    @Test
    fun `reset and manual seek beyond replay interval restore skipping`() {
        replay.onManualSeek(23000, 5000, segments, 60000)
        replay.reset()
        assertTrue(replay.shouldSkip(10000))
        replay.onManualSeek(23000, 5000, segments, 60000)
        replay.onManualSeek(5000, 30000, segments, 60000)
        assertTrue(replay.shouldSkip(40000))
    }
}
