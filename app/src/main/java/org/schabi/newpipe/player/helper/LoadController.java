package org.schabi.newpipe.player.helper;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.upstream.DefaultAllocator;

public class LoadController extends DefaultLoadControl {

    public static final String TAG = "LoadController";

    private static final int MAX_CONFIGURED_SEEK_MS = 30 * 1000;
    private static final int SEEK_MEMORY_MARGIN_MS = 1000;
    private static final int SMOOTH_PLAYBACK_AHEAD_MS = 60 * 1000;

    // Keep a rolling in-memory window around the playhead. The full stream is
    // cached separately on disk; ExoPlayer sample queues only retain enough for
    // instant double-tap seeks and smooth playback.
    private static final int SEEK_RETAIN_MS = MAX_CONFIGURED_SEEK_MS + SEEK_MEMORY_MARGIN_MS;
    private static final int PRELOAD_MIN_BUFFER_MS = SEEK_RETAIN_MS + SMOOTH_PLAYBACK_AHEAD_MS;
    private static final int PRELOAD_MAX_BUFFER_MS = SEEK_RETAIN_MS + SMOOTH_PLAYBACK_AHEAD_MS;
    private static final int PRELOAD_TARGET_BUFFER_BYTES = 96 * 1024 * 1024;
    private static final int PRELOAD_BUFFER_FOR_PLAYBACK_MS = 2500;
    private static final int PRELOAD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5000;

    private boolean preloadingEnabled = true;

    public LoadController() {
        super(new DefaultAllocator(true, DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE),
                PRELOAD_MIN_BUFFER_MS,
                PRELOAD_MAX_BUFFER_MS,
                PRELOAD_BUFFER_FOR_PLAYBACK_MS,
                PRELOAD_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                PRELOAD_TARGET_BUFFER_BYTES,
                /* prioritizeTimeOverSizeThresholds = */ false,
                SEEK_RETAIN_MS,
                /* retainBackBufferFromKeyframe = */ false);
    }

    @Override
    public void onPrepared() {
        preloadingEnabled = true;
        super.onPrepared();
    }

    @Override
    public void onStopped() {
        preloadingEnabled = true;
        super.onStopped();
    }

    @Override
    public void onReleased() {
        preloadingEnabled = true;
        super.onReleased();
    }

    @Override
    public boolean shouldContinueLoading(final long playbackPositionUs,
                                         final long bufferedDurationUs,
                                         final float playbackSpeed) {
        if (!preloadingEnabled) {
            return false;
        }
        return super.shouldContinueLoading(
                playbackPositionUs, bufferedDurationUs, playbackSpeed);
    }

    public void disablePreloadingOfCurrentTrack() {
        preloadingEnabled = false;
    }

    /**
     * @return the amount of back-buffer (in ms) ExoPlayer is asked to retain behind the playhead,
     * used to approximate the start of the in-memory window for logging.
     */
    public static int getSeekRetainMs() {
        return SEEK_RETAIN_MS;
    }
}
