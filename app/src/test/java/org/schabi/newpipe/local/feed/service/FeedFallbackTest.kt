package org.schabi.newpipe.local.feed.service

import java.io.IOException
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotYetAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class FeedFallbackTest {
    @Test
    fun availableFeedKeepsFastPath() {
        assertEquals("rss", feedOrNullOnUnavailable { "rss" })
    }

    @Test
    fun unavailableFeedAllowsChannelFallback() {
        assertNull(feedOrNullOnUnavailable<String> { throw ContentNotAvailableException("RSS 404") })
    }

    @Test(expected = IOException::class)
    fun networkFailureIsNotMistakenForMissingFeed() {
        feedOrNullOnUnavailable<String> { throw IOException("offline") }
    }

    @Test(expected = ParsingException::class)
    fun malformedFeedStillReportsExtractionFailure() {
        feedOrNullOnUnavailable<String> { throw ParsingException("invalid RSS") }
    }

    @Test
    fun scheduledStreamProbeIsRecognized() {
        assertEquals(
            true,
            isUpcomingFeedError(ContentNotYetAvailableException("scheduled"))
        )
        assertEquals(false, isUpcomingFeedError(IOException("offline")))
    }

    @Test
    fun nearbyAvailableRecordingReplacesUpcomingPlaceholderWithSameTitle() {
        val placeholder = stream("PLACEHOLD01", "Same title", "2026-11-08T09:13:14Z")
        val recording = stream("RECORDING01", "Same title", "2026-11-08T09:16:42Z")
        val recordingFromRichTab = stream(
            "RECORDING01",
            "Same title",
            "2026-11-08T09:16:42Z",
            duration = 4111
        )

        assertEquals(
            recording,
            findRecentReplacementRecording(
                placeholder,
                listOf(placeholder, recording),
                mapOf(feedVideoKey(recording.url) to recordingFromRichTab)
            )
        )
    }

    @Test
    fun recurringSameTitleOutsideTransitionWindowIsKept() {
        val placeholder = stream("PLACEHOLD01", "Same title", "2026-11-08T09:13:14Z")
        val laterRecording = stream("RECORDING01", "Same title", "2026-11-15T09:16:42Z")
        laterRecording.duration = 4111

        assertNull(
            findRecentReplacementRecording(
                placeholder,
                listOf(placeholder, laterRecording),
                mapOf(feedVideoKey(laterRecording.url) to laterRecording)
            )
        )
    }

    @Test
    fun unverifiedSameTitleItemDoesNotReplaceUpcomingPlaceholder() {
        val placeholder = stream("PLACEHOLD01", "Same title", "2026-11-08T09:13:14Z")
        val candidate = stream("RECORDING01", "Same title", "2026-11-08T09:16:42Z")

        assertNull(
            findRecentReplacementRecording(
                placeholder,
                listOf(placeholder, candidate),
                emptyMap()
            )
        )
    }

    private fun stream(
        id: String,
        title: String,
        uploadDate: String,
        duration: Long = -1
    ) = StreamInfoItem(
        0,
        "https://www.youtube.com/watch?v=$id",
        title,
        StreamType.VIDEO_STREAM
    ).also {
        it.uploadDate = DateWrapper(OffsetDateTime.parse(uploadDate))
        it.duration = duration
        it.contentAvailability = ContentAvailability.AVAILABLE
    }
}
