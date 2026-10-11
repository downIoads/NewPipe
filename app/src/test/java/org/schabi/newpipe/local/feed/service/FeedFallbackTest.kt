package org.schabi.newpipe.local.feed.service

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException

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
}
