/*
 * Created by Christian Schabesberger on 02.08.16.
 * <p>
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
 * DownloadActivity.java is part of NewPipe.
 * <p>
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.squareup.picasso.Callback;

import org.schabi.newpipe.database.feed.model.FeedGroupEntity;
import org.schabi.newpipe.database.stream.StreamWithState;
import org.schabi.newpipe.databinding.ActivityMainBinding;
import org.schabi.newpipe.databinding.ToolbarLayoutBinding;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.fragments.MainFragment;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.fragments.list.comments.CommentRepliesFragment;
import org.schabi.newpipe.fragments.list.playlist.PlaylistFragment;
import org.schabi.newpipe.fragments.list.search.SearchFragment;
import org.schabi.newpipe.local.feed.FeedDatabaseManager;
import org.schabi.newpipe.local.feed.notifications.NotificationWorker;
import org.schabi.newpipe.local.feed.service.FeedLoadService;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.event.OnKeyDownListener;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.settings.UpdateSettingsFragment;
import org.schabi.newpipe.settings.migration.MigrationManager;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PermissionHelper;
import org.schabi.newpipe.util.PersistentPlayerLogger;
import org.schabi.newpipe.util.ReleaseVersionUtil;
import org.schabi.newpipe.util.SerializedCache;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.StateSaver;
import org.schabi.newpipe.util.StreamPrefetcher;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.image.PicassoHelper;
import org.schabi.newpipe.views.FocusOverlayView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    @SuppressWarnings("ConstantConditions")
    public static final boolean DEBUG = !BuildConfig.BUILD_TYPE.equals("release");

    private ActivityMainBinding mainBinding;
    private ToolbarLayoutBinding toolbarLayoutBinding;

    private BroadcastReceiver broadcastReceiver;

    // DEBUG-only receiver used by scripts/trace_video_startup.py to warm the StreamInfo cache
    // before firing a VIEW intent, so the player-startup path can be measured as a cache hit
    // (the realistic in-app "item was visible in a list, then tapped" flow). See StreamPrefetcher.
    private BroadcastReceiver debugPrefetchReceiver;
    private BroadcastReceiver debugPlayerOrientationReceiver;
    private BroadcastReceiver debugBottomSheetReceiver;
    // DEBUG-only receiver used by scripts/trace_feed_refresh.py to trigger a "What's new" feed
    // refresh from adb and watch the per-subscription FeedDebug trace in real time.
    private BroadcastReceiver debugFeedRefreshReceiver;
    // DEBUG-only receiver to switch main-page tabs from adb, for profiling tab-switch performance.
    private BroadcastReceiver debugSwitchTabReceiver;

    // Keeps the launch/splash screen visible until the first feed thumbnails have been
    // prefetched into Picasso's memory cache, so the feed appears with thumbnails already
    // shown instead of grey placeholders.
    private final AtomicBoolean splashContentReady = new AtomicBoolean(false);
    private Disposable splashPrefetchDisposable;
    // private long splashStartNanos; // ytLog: uncomment with the logs below to time the hold
    /** How many of the newest feed thumbnails to prefetch before revealing the UI. */
    private static final int SPLASH_PREFETCH_COUNT = 12;
    /** Never hold the splash longer than this, even if prefetching stalls (ms). */
    private static final long SPLASH_MAX_HOLD_MS = 1500;

    public static final String KEY_IS_IN_BACKGROUND = "is_in_background";
    private static boolean webViewWarmUpDone;

    private SharedPreferences sharedPreferences;
    private SharedPreferences.Editor sharedPrefEditor;
    /*//////////////////////////////////////////////////////////////////////////
    // Activity's LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreate() called with: "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }

        Localization.migrateAppLanguageSettingIfNecessary(getApplicationContext());
        ThemeHelper.setDayNightMode(this);
        ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this));

        // Fixes text color turning black in dark/black mode:
        // https://github.com/TeamNewPipe/NewPipe/issues/12016
        // For further reference see: https://issuetracker.google.com/issues/37124582
        warmUpWebViewOnce();

        super.onCreate(savedInstanceState);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefEditor = sharedPreferences.edit();

        mainBinding = ActivityMainBinding.inflate(getLayoutInflater());
        toolbarLayoutBinding = mainBinding.toolbarLayout;
        setContentView(mainBinding.getRoot());

        // Only hold the splash for thumbnail prefetch on a fresh, top-level launch (not on
        // configuration changes or when restoring a back stack), so it never delays navigation.
        if (savedInstanceState == null
                && getSupportFragmentManager().getBackStackEntryCount() == 0) {
            holdSplashForThumbnails();
        }

        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            initFragments();
        }

        setSupportActionBar(toolbarLayoutBinding.toolbar);
        if (DeviceUtils.isTv(this)) {
            FocusOverlayView.setupFocusObserver(this);
        }
        openMiniPlayerUponPlayerStarted();

        if (PermissionHelper.checkPostNotificationsPermission(this,
                PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE)) {
            // Schedule worker for checking for new streams and creating corresponding notifications
            // if this is enabled by the user.
            NotificationWorker.initialize(this);
        }
        if (!UpdateSettingsFragment.wasUserAskedForConsent(this)
                && !App.getApp().isFirstRun()
                && ReleaseVersionUtil.INSTANCE.isReleaseApk()) {
            UpdateSettingsFragment.askForConsentToUpdateChecks(this);
        }

        MigrationManager.showUserInfoIfPresent(this);

        registerDebugPrefetchReceiver();
        registerDebugPlayerOrientationReceiver();
        registerDebugBottomSheetReceiver();
        registerDebugFeedRefreshReceiver();
        registerDebugSwitchTabReceiver();
    }

    /**
     * Registers a DEBUG-only broadcast receiver that warms the StreamInfo cache for a given url:
     * <pre>
     *   adb shell am broadcast -a org.schabi.newpipe.debug.PREFETCH \
     *       --es url '&lt;youtube-url&gt;' -p org.schabi.newpipe.debug
     * </pre>
     * This lets the trace tool measure the realistic in-app flow (item visible in a list →
     * prefetched → tapped) where extraction is already a cache hit. See {@link StreamPrefetcher}.
     */
    private void registerDebugPrefetchReceiver() {
        if (!DEBUG) {
            return;
        }
        debugPrefetchReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final String url = intent.getStringExtra("url");
                if (url == null || url.isEmpty()) {
                    return;
                }
                final int serviceId = intent.getIntExtra("serviceId",
                        ServiceList.YouTube.getServiceId());
                // Warm StreamInfo + resume position AND the first media chunk on disk, so the
                // traced "tap" measures the full pre-warmed in-app flow (extraction cache hit +
                // first frame served from disk). See StreamPrefetcher#prefetchMedia.
                StreamPrefetcher.prefetchMedia(serviceId, url);
            }
        };
        final IntentFilter filter = new IntentFilter("org.schabi.newpipe.debug.PREFETCH");
        ContextCompat.registerReceiver(this, debugPrefetchReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * DEBUG-only receiver for repeatable orientation/fullscreen transition profiling.
     * <pre>
     *   adb shell am broadcast -a org.schabi.newpipe.debug.PLAYER_ORIENTATION \
     *       --es orientation landscape -p org.schabi.newpipe.debug
     *   adb shell am broadcast -a org.schabi.newpipe.debug.PLAYER_ORIENTATION \
     *       --es orientation portrait -p org.schabi.newpipe.debug
     *   adb shell am broadcast -a org.schabi.newpipe.debug.PLAYER_ORIENTATION \
     *       --es orientation toggle -p org.schabi.newpipe.debug
     * </pre>
     */
    private void registerDebugPlayerOrientationReceiver() {
        if (!DEBUG) {
            return;
        }
        debugPlayerOrientationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final String orientation = intent.getStringExtra("orientation");
                final Fragment fragment = getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_player_holder);
                if (fragment instanceof VideoDetailFragment) {
                    ((VideoDetailFragment) fragment)
                            .debugRequestPlayerOrientation(orientation);
                } else {
                    PersistentPlayerLogger.log(MainActivity.this,
                            "OrientationSwitchTrace adb.noVideoDetailFragment orientation="
                                    + orientation);
                }
            }
        };
        final IntentFilter filter = new IntentFilter(
                "org.schabi.newpipe.debug.PLAYER_ORIENTATION");
        ContextCompat.registerReceiver(this, debugPlayerOrientationReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * DEBUG-only receiver to drive and inspect the bottom-sheet mini player from adb, so the
     * minimize gesture and the mini player's "X" can be reproduced deterministically:
     * <pre>
     *   adb shell am broadcast -a org.schabi.newpipe.debug.BOTTOM_SHEET \
     *       --es action minimize -p org.schabi.newpipe.debug
     *   adb shell am broadcast -a org.schabi.newpipe.debug.BOTTOM_SHEET \
     *       --es action close -p org.schabi.newpipe.debug
     *   adb shell am broadcast -a org.schabi.newpipe.debug.BOTTOM_SHEET \
     *       --es action state -p org.schabi.newpipe.debug
     * </pre>
     * Logs a {@code BottomSheetTrace} line (see {@code adb logcat}) describing whether a mini
     * player is currently shown and the player/play-queue state, before and after the action.
     */
    private void registerDebugBottomSheetReceiver() {
        if (!DEBUG) {
            return;
        }
        debugBottomSheetReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final String action = intent.getStringExtra("action");
                final Fragment fragment = getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_player_holder);
                if (fragment instanceof VideoDetailFragment) {
                    ((VideoDetailFragment) fragment).debugBottomSheetAction(action);
                } else {
                    PersistentPlayerLogger.log(MainActivity.this,
                            "BottomSheetTrace adb.noVideoDetailFragment action=" + action);
                }
            }
        };
        final IntentFilter filter = new IntentFilter("org.schabi.newpipe.debug.BOTTOM_SHEET");
        ContextCompat.registerReceiver(this, debugBottomSheetReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * DEBUG-only receiver to trigger a "What's new" feed refresh from adb, so the
     * "Not loaded: N" issue can be reproduced programmatically while watching the
     * per-subscription {@code FeedDebug} trace in real time:
     * <pre>
     *   # refresh all subscriptions (only outdated/never-loaded ones are fetched, exactly like
     *   # the manual refresh button):
     *   adb shell am broadcast -a org.schabi.newpipe.debug.REFRESH_FEED -p org.schabi.newpipe.debug
     *
     *   # force-refresh ALL subscriptions ignoring the update threshold:
     *   adb shell am broadcast -a org.schabi.newpipe.debug.REFRESH_FEED \
     *       --ez ignore_threshold true -p org.schabi.newpipe.debug
     *
     *   # refresh a single subscription group:
     *   adb shell am broadcast -a org.schabi.newpipe.debug.REFRESH_FEED \
     *       --el group_id &lt;id&gt; -p org.schabi.newpipe.debug
     * </pre>
     * The actual loading is performed by {@link FeedLoadService}/{@code FeedLoadManager}, which
     * emit the {@code FeedDebug} trace (see {@code adb logcat -s FeedDebug}). Driven by
     * {@code scripts/trace_feed_refresh.py}.
     */
    private void registerDebugFeedRefreshReceiver() {
        if (!DEBUG) {
            return;
        }
        debugFeedRefreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final long groupId = intent.getLongExtra("group_id",
                        FeedGroupEntity.GROUP_ALL_ID);
                final boolean ignoreThreshold =
                        intent.getBooleanExtra("ignore_threshold", false);
                Log.i("FeedDebug", "REFRESH_FEED broadcast received: groupId=" + groupId
                        + " ignoreThreshold=" + ignoreThreshold + " -> starting FeedLoadService");
                final Intent serviceIntent =
                        new Intent(MainActivity.this, FeedLoadService.class)
                                .putExtra(FeedLoadService.EXTRA_GROUP_ID, groupId)
                                .putExtra(FeedLoadService.EXTRA_IGNORE_THRESHOLD, ignoreThreshold);
                ContextCompat.startForegroundService(MainActivity.this, serviceIntent);
            }
        };
        final IntentFilter filter = new IntentFilter("org.schabi.newpipe.debug.REFRESH_FEED");
        ContextCompat.registerReceiver(this, debugFeedRefreshReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * DEBUG-only receiver to switch the main page's tabs from adb, so tab-switching performance
     * can be profiled deterministically without touching the screen:
     * <pre>
     *   adb shell am broadcast -a org.schabi.newpipe.debug.SWITCH_TAB \
     *       --ei index &lt;tab-index&gt; -p org.schabi.newpipe.debug
     * </pre>
     * Each switch logs {@code TabSwitchTrace} lines (see {@code adb logcat -s TabSwitchTrace})
     * reporting the forced instant switch and the main-thread stall until the next frame is drawn.
     */
    private void registerDebugSwitchTabReceiver() {
        if (!DEBUG) {
            return;
        }
        debugSwitchTabReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final int index = intent.getIntExtra("index", -1);
                final Fragment fragment = getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_holder);
                if (fragment instanceof MainFragment) {
                    ((MainFragment) fragment).debugSwitchToTab(index);
                } else {
                    Log.d("TabSwitchTrace", "SWITCH_TAB ignored: main fragment not in foreground");
                }
            }
        };
        final IntentFilter filter = new IntentFilter("org.schabi.newpipe.debug.SWITCH_TAB");
        ContextCompat.registerReceiver(this, debugSwitchTabReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * Suspends the first draw of the content view (keeping the launch/splash screen visible)
     * until the newest feed thumbnails have been prefetched into Picasso's memory cache, or a
     * safety timeout elapses. This way the feed is revealed with its thumbnails already loaded
     * instead of briefly showing grey placeholders.
     */
    private void holdSplashForThumbnails() {
        final View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        // splashStartNanos = System.nanoTime(); // ytLog: uncomment to time the splash hold

        content.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (splashContentReady.get()) {
                            content.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                        // suspend drawing -> the splash window background stays on screen
                        return false;
                    }
                });

        // Safety net: never hold the splash longer than SPLASH_MAX_HOLD_MS.
        content.postDelayed(this::revealContent, SPLASH_MAX_HOLD_MS);

        splashPrefetchDisposable = new FeedDatabaseManager(this)
                // permissive filters so we prefetch a superset of whatever the feed will show
                .getStreams(FeedGroupEntity.GROUP_ALL_ID, true, true, true)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::prefetchThumbnails, e -> revealContent(), this::revealContent);
    }

    private void prefetchThumbnails(final List<StreamWithState> streams) {
        final List<String> urls = new ArrayList<>();
        for (final StreamWithState s : streams) {
            final String url = s.getStream().getThumbnailUrl();
            if (url != null && !url.isEmpty()) {
                urls.add(url);
                if (urls.size() >= SPLASH_PREFETCH_COUNT) {
                    break;
                }
            }
        }

        if (urls.isEmpty()) {
            revealContent();
            return;
        }

        final AtomicInteger remaining = new AtomicInteger(urls.size());
        final Callback callback = new Callback() {
            @Override
            public void onSuccess() {
                if (remaining.decrementAndGet() <= 0) {
                    revealContent();
                }
            }

            @Override
            public void onError(final Exception e) {
                if (remaining.decrementAndGet() <= 0) {
                    revealContent();
                }
            }
        };

        for (final String url : urls) {
            PicassoHelper.loadThumbnail(url).fetch(callback);
        }
    }

    /** Lets the suspended first draw proceed, revealing the UI behind the splash. */
    private void revealContent() {
        if (splashContentReady.compareAndSet(false, true)) {
            // ytLog: uncomment (with the field above) to measure how long the splash was held
            // Log.d(TAG, "splash held "
            //         + ((System.nanoTime() - splashStartNanos) / 1_000_000) + "ms");
            final View content = findViewById(android.R.id.content);
            if (content != null) {
                // trigger a new draw pass so the OnPreDrawListener fires again and returns true
                content.invalidate();
            }
        }
    }

    private void warmUpWebViewOnce() {
        if (webViewWarmUpDone || !DeviceUtils.supportsWebView()) {
            return;
        }

        webViewWarmUpDone = true;
        WebView webView = null;
        try {
            webView = new WebView(this);
            PersistentPlayerLogger.log(this, "MainActivity.webViewWarmUp.created");
        } catch (final Throwable e) {
            PersistentPlayerLogger.log(this, "MainActivity.webViewWarmUp.failed "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (DEBUG) {
                Log.e(TAG, "Failed to create WebView", e);
            }
        } finally {
            if (webView != null) {
                webView.loadUrl("about:blank");
                webView.onPause();
                webView.removeAllViews();
                webView.destroy();
                PersistentPlayerLogger.log(this, "MainActivity.webViewWarmUp.destroyed");
            }
        }
    }

    @Override
    protected void onPostCreate(final Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        final App app = App.getApp();

        if (sharedPreferences.getBoolean(app.getString(R.string.update_app_key), false)
                && sharedPreferences
                .getBoolean(app.getString(R.string.update_check_consent_key), false)) {
            // Start the worker which is checking all conditions
            // and eventually searching for a new version.
            NewVersionWorker.enqueueNewVersionCheckingWork(app, false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        sharedPrefEditor.putBoolean(KEY_IS_IN_BACKGROUND, false).apply();
        Log.d(TAG, "App moved to foreground");
    }

    @Override
    protected void onStop() {
        super.onStop();
        sharedPrefEditor.putBoolean(KEY_IS_IN_BACKGROUND, true).apply();
        Log.d(TAG, "App moved to background");
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (splashPrefetchDisposable != null) {
            splashPrefetchDisposable.dispose();
        }
        if (!isChangingConfigurations()) {
            StateSaver.clearStateFiles();
        }
        if (broadcastReceiver != null) {
            unregisterReceiver(broadcastReceiver);
        }
        if (debugPrefetchReceiver != null) {
            unregisterReceiver(debugPrefetchReceiver);
            debugPrefetchReceiver = null;
        }
        if (debugBottomSheetReceiver != null) {
            unregisterReceiver(debugBottomSheetReceiver);
            debugBottomSheetReceiver = null;
        }
        if (debugPlayerOrientationReceiver != null) {
            unregisterReceiver(debugPlayerOrientationReceiver);
            debugPlayerOrientationReceiver = null;
        }
        if (debugFeedRefreshReceiver != null) {
            unregisterReceiver(debugFeedRefreshReceiver);
            debugFeedRefreshReceiver = null;
        }
        if (debugSwitchTabReceiver != null) {
            unregisterReceiver(debugSwitchTabReceiver);
            debugSwitchTabReceiver = null;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        final long startMs = SystemClock.elapsedRealtime();
        PersistentPlayerLogger.log(this,
                "OrientationSwitchTrace MainActivity.onConfigurationChanged.start"
                        + " orientation=" + newConfig.orientation
                        + " widthDp=" + newConfig.screenWidthDp
                        + " heightDp=" + newConfig.screenHeightDp);
        super.onConfigurationChanged(newConfig);

        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (fragment instanceof VideoDetailFragment) {
            ((VideoDetailFragment) fragment).onHostConfigurationChanged(newConfig);
        }
        PersistentPlayerLogger.log(this,
                "OrientationSwitchTrace MainActivity.onConfigurationChanged.end"
                        + " durationMs=" + (SystemClock.elapsedRealtime() - startMs));
    }

    @Override
    protected void onResume() {
        // Change the date format to match the selected language on resume
        Localization.initPrettyTime(Localization.resolvePrettyTime());
        super.onResume();

        if (sharedPreferences.getBoolean(Constants.KEY_THEME_CHANGE, false)) {
            if (DEBUG) {
                Log.d(TAG, "Theme has changed, recreating activity...");
            }
            sharedPrefEditor.putBoolean(Constants.KEY_THEME_CHANGE, false).apply();
            ActivityCompat.recreate(this);
        }

        if (sharedPreferences.getBoolean(Constants.KEY_MAIN_PAGE_CHANGE, false)) {
            if (DEBUG) {
                Log.d(TAG, "main page has changed, recreating main fragment...");
            }
            sharedPrefEditor.putBoolean(Constants.KEY_MAIN_PAGE_CHANGE, false).apply();
            NavigationHelper.openMainActivity(this);
        }
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onNewIntent() called with: intent = [" + intent + "]");
        }
        if (intent != null) {
            // Return if launched from a launcher (e.g. Nova Launcher, Pixel Launcher ...)
            // to not destroy the already created backstack
            final String action = intent.getAction();
            if ((action != null && action.equals(Intent.ACTION_MAIN))
                    && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                return;
            }
        }

        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (fragment instanceof OnKeyDownListener
                && !bottomSheetHiddenOrCollapsed()) {
            // Provide keyDown event to fragment which then sends this event
            // to the main player service
            return ((OnKeyDownListener) fragment).onKeyDown(keyCode)
                    || super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (DEBUG) {
            Log.d(TAG, "onBackPressed() called");
        }

        // In case bottomSheet is not visible on the screen or collapsed we can assume that the user
        // interacts with a fragment inside fragment_holder so all back presses should be
        // handled by it
        if (bottomSheetHiddenOrCollapsed()) {
            final FragmentManager fm = getSupportFragmentManager();
            final Fragment fragment = fm.findFragmentById(R.id.fragment_holder);
            // If current fragment implements BackPressable (i.e. can/wanna handle back press)
            // delegate the back press to it
            if (fragment instanceof BackPressable) {
                if (((BackPressable) fragment).onBackPressed()) {
                    return;
                }
            } else if (fragment instanceof CommentRepliesFragment) {
                // Expand DetailsFragment if CommentRepliesFragment was opened
                // and no other CommentRepliesFragments are on top of the back stack
                // to show the top level comments again. Pop the back stack here
                // and return so the swipe-back gesture matches the toolbar back
                // arrow's behaviour (otherwise super.onBackPressed() would pop an
                // additional entry, sending the user past the video detail).
                openDetailFragmentFromCommentReplies(fm, true);
                return;
            }

        } else {
            final Fragment fragmentPlayer = getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_player_holder);
            // If current fragment implements BackPressable (i.e. can/wanna handle back press)
            // delegate the back press to it
            if (fragmentPlayer instanceof VideoDetailFragment) {
                ((VideoDetailFragment) fragmentPlayer).minimizeOnBackPressed();
                return;
            } else if (fragmentPlayer instanceof BackPressable) {
                if (((BackPressable) fragmentPlayer).onBackPressed()) {
                    return;
                }

                BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder)
                        .setState(BottomSheetBehavior.STATE_COLLAPSED);
                return;
            }
        }

        if (getSupportFragmentManager().getBackStackEntryCount() == 1) {
            finish();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (final int i : grantResults) {
            if (i == PackageManager.PERMISSION_DENIED) {
                return;
            }
        }
        switch (requestCode) {
            case PermissionHelper.DOWNLOADS_REQUEST_CODE:
                NavigationHelper.openDownloads(this);
                break;
            case PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE:
                final Fragment fragment = getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_player_holder);
                if (fragment instanceof VideoDetailFragment) {
                    ((VideoDetailFragment) fragment).openDownloadDialog();
                }
                break;
            case PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE:
                NotificationWorker.initialize(this);
                break;
        }
    }

    /**
     * Implement the following diagram behavior for the up button:
     * <pre>
     *              +---------------+
     *              |  Main Screen  +----+
     *              +-------+-------+    |
     *                      |            |
     *                      ▲ Up         | Search Button
     *                      |            |
     *                 +----+-----+      |
     *    +------------+  Search  |◄-----+
     *    |            +----+-----+
     *    |   Open          |
     *    |  something      ▲ Up
     *    |                 |
     *    |    +------------+-------------+
     *    |    |                          |
     *    |    |  Video    <->  Channel   |
     *    +---►|  Channel  <->  Playlist  |
     *         |  Video    <->  ....      |
     *         |                          |
     *         +--------------------------+
     * </pre>
     */
    private void onHomeButtonPressed() {
        final FragmentManager fm = getSupportFragmentManager();
        final Fragment fragment = fm.findFragmentById(R.id.fragment_holder);

        if (fragment instanceof CommentRepliesFragment) {
            // Expand DetailsFragment if CommentRepliesFragment was opened
            // and no other CommentRepliesFragments are on top of the back stack
            // to show the top level comments again.
            openDetailFragmentFromCommentReplies(fm, true);
        } else if (fragment instanceof PlaylistFragment
                && fm.getBackStackEntryCount() > 2) {
            // A playlist was opened from another non-main fragment (e.g. the
            // channel page). The up button should bring the user back one
            // level (matching the swipe-back gesture) instead of jumping
            // straight to the main fragment.
            fm.popBackStackImmediate();
        } else if (!NavigationHelper.tryGotoSearchFragment(fm)) {
            // If search fragment wasn't found in the backstack go to the main fragment
            NavigationHelper.gotoMainFragment(fm);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: menu = [" + menu + "]");
        }
        super.onCreateOptionsMenu(menu);

        final Fragment fragment =
                getSupportFragmentManager().findFragmentById(R.id.fragment_holder);
        if (!(fragment instanceof SearchFragment)) {
            toolbarLayoutBinding.toolbarSearchContainer.getRoot().setVisibility(View.GONE);
        }

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
        }

        updateToolbarNavigation();

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (DEBUG) {
            Log.d(TAG, "onOptionsItemSelected() called with: item = [" + item + "]");
        }

        if (item.getItemId() == android.R.id.home) {
            onHomeButtonPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    private void initFragments() {
        if (DEBUG) {
            Log.d(TAG, "initFragments() called");
        }
        StateSaver.clearStateFiles();
        if (getIntent() != null && getIntent().hasExtra(Constants.KEY_LINK_TYPE)) {
            // When user watch a video inside popup and then tries to open the video in main player
            // while the app is closed he will see a blank fragment on place of kiosk.
            // Let's open it first
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                NavigationHelper.openMainFragment(getSupportFragmentManager());
            }

            handleIntent(getIntent());
        } else {
            NavigationHelper.gotoMainFragment(getSupportFragmentManager());
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private void updateToolbarNavigation() {
        if (getSupportActionBar() == null) {
            return;
        }

        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_holder);
        if (fragment instanceof MainFragment) {
            // On the main page the toolbar icon is a direct shortcut to the settings
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            toolbarLayoutBinding.toolbar.setNavigationIcon(R.drawable.ic_settings);
            toolbarLayoutBinding.toolbar.setNavigationContentDescription(R.string.settings);
            toolbarLayoutBinding.toolbar.setNavigationOnClickListener(v ->
                    NavigationHelper.openSettings(this));
        } else {
            // Everywhere else it is the usual "up" affordance
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbarLayoutBinding.toolbar.setNavigationOnClickListener(v -> onHomeButtonPressed());
        }
    }

    private void handleIntent(final Intent intent) {
        try {
            if (DEBUG) {
                Log.d(TAG, "handleIntent() called with: intent = [" + intent + "]");
            }

            if (intent.hasExtra(Constants.KEY_LINK_TYPE)) {
                final String url = intent.getStringExtra(Constants.KEY_URL);
                final int serviceId = intent.getIntExtra(Constants.KEY_SERVICE_ID, 0);
                String title = intent.getStringExtra(Constants.KEY_TITLE);
                if (title == null) {
                    title = "";
                }

                final StreamingService.LinkType linkType = ((StreamingService.LinkType) intent
                        .getSerializableExtra(Constants.KEY_LINK_TYPE));
                assert linkType != null;
                switch (linkType) {
                    case STREAM:
                        final String intentCacheKey = intent.getStringExtra(
                                Player.PLAY_QUEUE_KEY);
                        final PlayQueue playQueue = intentCacheKey != null
                                ? SerializedCache.getInstance()
                                .take(intentCacheKey, PlayQueue.class)
                                : null;

                        final boolean switchingPlayers = intent.getBooleanExtra(
                                VideoDetailFragment.KEY_SWITCHING_PLAYERS, false);
                        NavigationHelper.openVideoDetailFragment(
                                getApplicationContext(), getSupportFragmentManager(),
                                serviceId, url, title, playQueue, switchingPlayers);
                        break;
                    case CHANNEL:
                        NavigationHelper.openChannelFragment(getSupportFragmentManager(),
                                serviceId, url, title);
                        break;
                    case PLAYLIST:
                        NavigationHelper.openPlaylistFragment(getSupportFragmentManager(),
                                serviceId, url, title);
                        break;
                }
            } else if (intent.hasExtra(Constants.KEY_OPEN_SEARCH)) {
                String searchString = intent.getStringExtra(Constants.KEY_SEARCH_STRING);
                if (searchString == null) {
                    searchString = "";
                }
                final int serviceId = intent.getIntExtra(Constants.KEY_SERVICE_ID, 0);
                NavigationHelper.openSearchFragment(
                        getSupportFragmentManager(),
                        serviceId,
                        searchString);

            } else {
                NavigationHelper.gotoMainFragment(getSupportFragmentManager());
            }
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Handling intent", e);
        }
    }

    private void openMiniPlayerIfMissing() {
        final Fragment fragmentPlayer = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (fragmentPlayer == null) {
            // We still don't have a fragment attached to the activity. It can happen when a user
            // started popup or background players without opening a stream inside the fragment.
            // Adding it in a collapsed state (only mini player will be visible).
            NavigationHelper.showMiniPlayer(getSupportFragmentManager());
        }
    }

    private void openMiniPlayerUponPlayerStarted() {
        if (getIntent().getSerializableExtra(Constants.KEY_LINK_TYPE)
                == StreamingService.LinkType.STREAM) {
            // handleIntent() already takes care of opening video detail fragment
            // due to an intent containing a STREAM link
            return;
        }

        if (PlayerHolder.getInstance().isPlayerOpen()) {
            // if the player is already open, no need for a broadcast receiver
            openMiniPlayerIfMissing();
        } else {
            // listen for player start intent being sent around
            broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    if (Objects.equals(intent.getAction(),
                            VideoDetailFragment.ACTION_PLAYER_STARTED)
                            && PlayerHolder.getInstance().isPlayerOpen()) {
                        openMiniPlayerIfMissing();
                        // At this point the player is added 100%, we can unregister. Other actions
                        // are useless since the fragment will not be removed after that.
                        unregisterReceiver(broadcastReceiver);
                        broadcastReceiver = null;
                    }
                }
            };
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(VideoDetailFragment.ACTION_PLAYER_STARTED);
            ContextCompat.registerReceiver(this, broadcastReceiver, intentFilter,
                    ContextCompat.RECEIVER_EXPORTED);

            // If the PlayerHolder is not bound yet, but the service is running, try to bind to it.
            // Once the connection is established, the ACTION_PLAYER_STARTED will be sent.
            PlayerHolder.getInstance().tryBindIfNeeded(this);
        }
    }

    public void openDetailFragmentFromCommentReplies(
            @NonNull final FragmentManager fm,
            final boolean popBackStack
    ) {
        // obtain the name of the fragment under the replies fragment that's going to be popped
        @Nullable final String fragmentUnderEntryName;
        if (fm.getBackStackEntryCount() < 2) {
            fragmentUnderEntryName = null;
        } else {
            fragmentUnderEntryName = fm.getBackStackEntryAt(fm.getBackStackEntryCount() - 2)
                    .getName();
        }

        // the root comment is the comment for which the user opened the replies page
        @Nullable final CommentRepliesFragment repliesFragment =
                (CommentRepliesFragment) fm.findFragmentByTag(CommentRepliesFragment.TAG);
        @Nullable final CommentsInfoItem rootComment =
                repliesFragment == null ? null : repliesFragment.getCommentsInfoItem();

        Log.d(TAG, "openDetailFragmentFromCommentReplies: popBackStack=" + popBackStack
                + ", fragmentUnder=" + fragmentUnderEntryName
                + ", rootComment=" + (rootComment == null ? "null" : rootComment.getName()));

        // sometimes this function pops the backstack, other times it's handled by the system
        if (popBackStack) {
            fm.popBackStackImmediate();
        }

        // only expand the bottom sheet back if there are no more nested comment replies fragments
        // stacked under the one that is currently being popped
        if (CommentRepliesFragment.TAG.equals(fragmentUnderEntryName)) {
            Log.d(TAG, "openDetailFragmentFromCommentReplies: nested replies remain, "
                    + "not re-expanding bottom sheet");
            return;
        }

        final BottomSheetBehavior<FragmentContainerView> behavior = BottomSheetBehavior
                .from(mainBinding.fragmentPlayerHolder);
        // do not return to the comment if the details fragment was closed
        if (behavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
            Log.d(TAG, "openDetailFragmentFromCommentReplies: detail bottom sheet hidden, "
                    + "not returning to comment");
            return;
        }

        // scroll to the root comment once the bottom sheet expansion animation is finished
        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull final View bottomSheet,
                                       final int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    final Fragment detailFragment = fm.findFragmentById(
                            R.id.fragment_player_holder);
                    // detailFragment is normally a VideoDetailFragment here, but the tab it
                    // hosts may not be re-attached yet by the time the expand animation
                    // finishes; VideoDetailFragment.scrollToComment handles that safely.
                    if (detailFragment instanceof VideoDetailFragment && rootComment != null) {
                        Log.d(TAG, "bottom sheet expanded, scrolling back to root comment: "
                                + rootComment.getName());
                        ((VideoDetailFragment) detailFragment).scrollToComment(rootComment);
                    } else {
                        Log.d(TAG, "bottom sheet expanded but cannot scroll to comment "
                                + "(detailFragment=" + detailFragment
                                + ", rootComment=" + rootComment + ")");
                    }
                    behavior.removeBottomSheetCallback(this);
                }
            }

            @Override
            public void onSlide(@NonNull final View bottomSheet, final float slideOffset) {
                // not needed, listener is removed once the sheet is expanded
            }
        });

        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private boolean bottomSheetHiddenOrCollapsed() {
        final BottomSheetBehavior<FrameLayout> bottomSheetBehavior =
                BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder);

        final int sheetState = bottomSheetBehavior.getState();
        return sheetState == BottomSheetBehavior.STATE_HIDDEN
                || sheetState == BottomSheetBehavior.STATE_COLLAPSED;
    }

}
