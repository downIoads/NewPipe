package org.schabi.newpipe.views;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.ViewPager;

import org.schabi.newpipe.BuildConfig;

/**
 * A {@link ViewPager} that never plays the horizontal slide animation when the current item is
 * changed programmatically (e.g. by tapping a tab in the main page's {@code TabLayout}).
 *
 * <p>{@code TabLayout.setupWithViewPager()} installs a listener that calls
 * {@code setCurrentItem(position, true)} — the {@code true} requests a smooth scroll, which
 * animates the page transition. With a {@code FragmentStatePagerAdapter} the destination fragment
 * performs its inflate/layout/data-load work <i>during</i> the animation, so the slide stutters and
 * feels slow. Forcing {@code smoothScroll = false} makes tab switches snap instantly, which is
 * what we want here.</p>
 */
public final class NoAnimationViewPager extends ViewPager {
    private static final String TAG = "TabSwitchTrace";

    public NoAnimationViewPager(@NonNull final Context context) {
        super(context);
    }

    public NoAnimationViewPager(@NonNull final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setCurrentItem(final int item, final boolean smoothScroll) {
        // Always snap instantly, ignoring any smoothScroll request from TabLayout etc.
        if (BuildConfig.DEBUG) {
            final long start = SystemClock.elapsedRealtime();
            super.setCurrentItem(item, false);
            Log.d(TAG, "setCurrentItem item=" + item + " (smoothScroll requested="
                    + smoothScroll + ", forced=false) tookMs="
                    + (SystemClock.elapsedRealtime() - start));
        } else {
            super.setCurrentItem(item, false);
        }
    }

    @Override
    public void setCurrentItem(final int item) {
        setCurrentItem(item, false);
    }
}
