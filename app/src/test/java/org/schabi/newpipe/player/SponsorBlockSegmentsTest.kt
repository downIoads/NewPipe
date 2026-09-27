package org.schabi.newpipe.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.player.SponsorBlockSegments.Segment

class SponsorBlockSegmentsTest {
    @Test
    fun `lookup sends only four hash characters and requests skip actions`() {
        val request = SponsorBlockSegments.request("dQw4w9WgXcQ")
        assertEquals("/api/skipSegments/5f6b", request.url.encodedPath)
        assertFalse(request.url.toString().contains("dQw4w9WgXcQ"))
        assertEquals("[\"skip\"]", request.url.queryParameter("actionTypes"))
        assertEquals("[\"sponsor\"]", request.url.queryParameter("categories"))
    }

    @Test
    fun `parse selects exact video and valid skip segments sorted by start`() {
        val body = """[
          {"videoID":"other","segments":[
            {"category":"sponsor","actionType":"skip","segment":[0,90]}]},
          {"videoID":"wanted","segments":[
            {"category":"sponsor","actionType":"skip","segment":[20,30.0001]},
            {"category":"sponsor","actionType":"skip","segment":[0,10]},
            {"category":"sponsor","actionType":"mute","segment":[1,2]},
            {"category":"poi_highlight","actionType":"poi","segment":[1,2]},
            {"category":"unknown","actionType":"skip","segment":[1,2]},
            {"category":"sponsor","actionType":"skip","segment":[-1,2]},
            {"category":"sponsor","actionType":"skip","segment":[5,2]},
            {"category":"sponsor","actionType":"skip","segment":[5,5]},
            {"category":"sponsor","actionType":"skip","segment":["bad",5]},
            {"category":"sponsor","actionType":"skip","segment":[1]}]}
        ]"""
        assertEquals(listOf(Segment(0, 10000), Segment(20000, 30001)), SponsorBlockSegments.parse(body, "wanted"))
        assertEquals(emptyList<Segment>(), SponsorBlockSegments.parse(body, "missing"))
    }

    @Test
    fun `intro outro and other non sponsor categories are neither marked nor skipped`() {
        val body = """[{"videoID":"wanted","segments":[
            {"category":"intro","actionType":"skip","segment":[0,20]},
            {"category":"sponsor","actionType":"skip","segment":[420,450]},
            {"category":"outro","actionType":"skip","segment":[580,600]},
            {"category":"selfpromo","actionType":"skip","segment":[30,40]},
            {"category":"interaction","actionType":"skip","segment":[50,60]},
            {"category":"preview","actionType":"skip","segment":[70,80]},
            {"category":"filler","actionType":"skip","segment":[90,100]},
            {"category":"music_offtopic","actionType":"skip","segment":[110,120]}
        ]}]"""
        val segments = SponsorBlockSegments.parse(body, "wanted")
        assertEquals(listOf(Segment(420000, 450000)), segments)
        assertNull(SponsorBlockSegments.skipTarget(segments, 0, 600000))
        assertEquals(450000L, SponsorBlockSegments.skipTarget(segments, 420000, 600000))
        assertNull(SponsorBlockSegments.skipTarget(segments, 590000, 600000))
    }

    @Test
    fun `skip merges overlaps and touching segments without skipping gaps`() {
        val segments = listOf(Segment(1000, 3000), Segment(2000, 4000), Segment(4000, 5000), Segment(7000, 9000))
        assertNull(SponsorBlockSegments.skipTarget(segments, 999, 10000))
        assertEquals(5000L, SponsorBlockSegments.skipTarget(segments, 1000, 10000))
        assertEquals(5000L, SponsorBlockSegments.skipTarget(segments, 4500, 10000))
        assertNull(SponsorBlockSegments.skipTarget(segments, 5000, 10000))
        assertNull(SponsorBlockSegments.skipTarget(segments, 6000, 10000))
        assertEquals(9000L, SponsorBlockSegments.skipTarget(segments, 7000, 10000))
        // Rewinding into a segment still skips it.
        assertEquals(5000L, SponsorBlockSegments.skipTarget(segments, 1500, 10000))
    }

    @Test
    fun `skip handles stream end and unknown duration`() {
        val segments = listOf(Segment(0, 12000))
        assertEquals(10000L, SponsorBlockSegments.skipTarget(segments, 0, 10000))
        assertNull(SponsorBlockSegments.skipTarget(segments, 10000, 10000))
        assertNull(SponsorBlockSegments.skipTarget(segments, 0, -1))
        assertNull(SponsorBlockSegments.skipTarget(emptyList(), 0, 10000))
    }
}
