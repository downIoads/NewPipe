package org.schabi.newpipe.player.helper;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.upstream.DefaultAllocator;

public class LoadController extends DefaultLoadControl {

    public static final String TAG = "LoadController";

    // Keep more media ready than ExoPlayer's defaults, but do not let a single
    // playing item reserve unbounded memory and push the UI into memory pressure.
    private static final int PRELOAD_MIN_BUFFER_MS = 10 * 60 * 1000;
    private static final int PRELOAD_MAX_BUFFER_MS = 10 * 60 * 1000;
    private static final int PRELOAD_TARGET_BUFFER_BYTES = 256 * 1024 * 1024;
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
                DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS,
                DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME);
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
}
