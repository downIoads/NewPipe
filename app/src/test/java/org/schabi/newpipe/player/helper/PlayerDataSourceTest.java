package org.schabi.newpipe.player.helper;

import android.net.Uri;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PlayerDataSourceTest {
    @Test
    public void archivedLivestreamSegmentsDoNotShareCachedBytes() {
        final String first = key("d_w0066nddA.1", "299", "0");
        assertNotEquals(first, key("d_w0066nddA.1", "299", "1"));
        assertNotEquals(first, key("d_w0066nddA.1", "299", "43"));
        // Do not reuse the old entry polluted by caching every segment under one key.
        assertNotEquals(first, "yt:d_w0066nddA.1:299");
    }

    @Test
    public void segmentIdentitySurvivesUrlRefreshAndCustomKeyChanges() {
        final Uri original = mediaUri("d_w0066nddA.1", "299", "43");
        final Uri refreshed = mediaUri("d_w0066nddA.1", "299", "43");
        when(original.toString()).thenReturn("https://old.googlevideo.com/?expire=1&sq=43");
        when(refreshed.toString()).thenReturn("https://new.googlevideo.com/?expire=2&sq=43");
        assertEquals(PlayerDataSource.stableCacheKey(original, "old-custom-key"),
                PlayerDataSource.stableCacheKey(refreshed, "new-custom-key"));
    }

    @Test
    public void audioAndVideoSegmentsRemainSeparate() {
        assertNotEquals(key("d_w0066nddA.1", "299", "43"),
                key("d_w0066nddA.1", "140", "43"));
    }

    @Test
    public void progressiveStreamsKeepTheirExistingPrefetchKey() {
        assertEquals("yt:jNQXAC9IVRw:137", key("jNQXAC9IVRw", "137", null));
        assertNotEquals(key("jNQXAC9IVRw", "137", null),
                key("jNQXAC9IVRw", "137", "0"));
    }

    @Test
    public void otherServicesKeepTheirFallbackKey() {
        final Uri uri = mock(Uri.class);
        when(uri.getHost()).thenReturn("example.com");
        when(uri.toString()).thenReturn("https://example.com/video");
        assertEquals("custom-key", PlayerDataSource.stableCacheKey(uri, "custom-key"));
        assertEquals("https://example.com/video", PlayerDataSource.stableCacheKey(uri, null));
    }

    private static String key(final String id, final String itag, final String sequence) {
        return PlayerDataSource.stableCacheKey(mediaUri(id, itag, sequence), "shared-key");
    }

    private static Uri mediaUri(final String id, final String itag, final String sequence) {
        final Uri uri = mock(Uri.class);
        when(uri.getHost()).thenReturn("rr2---sn-4g5e6nzz.googlevideo.com");
        when(uri.getQueryParameter("id")).thenReturn(id);
        when(uri.getQueryParameter("itag")).thenReturn(itag);
        when(uri.getQueryParameter("sq")).thenReturn(sequence);
        return uri;
    }
}
