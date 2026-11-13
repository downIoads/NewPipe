package org.schabi.newpipe.fragments.list.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;

public class SearchResultFilterTest {
    private static final StreamInfoItem VIDEO = stream("video", StreamType.VIDEO_STREAM, false);
    private static final StreamInfoItem SHORT = stream("short", StreamType.VIDEO_STREAM, true);
    private static final StreamInfoItem LIVE = stream("live", StreamType.LIVE_STREAM, false);
    private static final List<InfoItem> RESULTS = List.of(VIDEO, SHORT, LIVE);

    @Test
    public void showShortsDisabledRemovesOnlyShortFormContent() {
        assertEquals(List.of(VIDEO, LIVE), SearchResultFilter.filter(RESULTS, true, false));
    }

    @Test
    public void showShortsEnabledPreservesOriginalBehavior() {
        assertSame(RESULTS, SearchResultFilter.filter(RESULTS, true, true));
    }

    @Test
    public void liveAndShortPreferencesAreAppliedIndependently() {
        assertEquals(List.of(VIDEO, SHORT), SearchResultFilter.filter(RESULTS, false, true));
        assertEquals(List.of(VIDEO), SearchResultFilter.filter(RESULTS, false, false));
    }

    private static StreamInfoItem stream(final String name,
                                         final StreamType streamType,
                                         final boolean shortFormContent) {
        final StreamInfoItem item = new StreamInfoItem(0, "https://example.com/" + name,
                name, streamType);
        item.setShortFormContent(shortFormContent);
        return item;
    }
}
