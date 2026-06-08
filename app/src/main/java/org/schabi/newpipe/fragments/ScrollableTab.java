package org.schabi.newpipe.fragments;

/**
 * Implemented by the main page tab fragments that host a scrollable list. Re-selecting an
 * already-selected tab scrolls its content back to the top, mirroring the comments-tab behaviour
 * in the video detail page (see
 * {@link org.schabi.newpipe.fragments.detail.VideoDetailFragment#scrollSelectedTabToTop()}).
 */
public interface ScrollableTab {
    void scrollToTop();
}
