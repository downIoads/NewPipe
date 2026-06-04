package org.schabi.newpipe.player;

import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_NO_PERMISSION;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_TIMEOUT;
import static com.google.android.exoplayer2.PlaybackException.ERROR_CODE_UNSPECIFIED;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_INTERNAL;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_REMOVE;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_SKIP;
import static com.google.android.exoplayer2.Player.DiscontinuityReason;
import static com.google.android.exoplayer2.Player.Listener;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ONE;
import static com.google.android.exoplayer2.Player.RepeatMode;
import static org.schabi.newpipe.extractor.ServiceList.YouTube;
import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;
import static org.schabi.newpipe.player.helper.PlayerHelper.retrievePlaybackParametersFromPrefs;
import static org.schabi.newpipe.player.helper.PlayerHelper.retrieveSeekDurationFromPreferences;
import static org.schabi.newpipe.player.helper.PlayerHelper.savePlaybackParametersToPrefs;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_CLOSE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_FAST_FORWARD;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_FAST_REWIND;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_NEXT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PAUSE;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_PLAY_PREVIOUS;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_RECREATE_NOTIFICATION;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_REPEAT;
import static org.schabi.newpipe.player.notification.NotificationConstants.ACTION_SHUFFLE;
import static org.schabi.newpipe.util.ListHelper.getPopupResolutionIndex;
import static org.schabi.newpipe.util.ListHelper.getResolutionIndex;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.SystemClock;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.core.math.MathUtils;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player.PositionInfo;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.cache.CacheWriter;
import com.google.android.exoplayer2.video.VideoSize;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.databinding.PlayerBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.event.PlayerEventListener;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;
import org.schabi.newpipe.player.helper.AudioReactor;
import org.schabi.newpipe.player.helper.CustomRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;
import org.schabi.newpipe.player.mediasession.MediaSessionPlayerUi;
import org.schabi.newpipe.player.notification.NotificationPlayerUi;
import org.schabi.newpipe.player.playback.MediaSourceManager;
import org.schabi.newpipe.player.playback.PlaybackListener;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueueItem;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.player.resolver.AudioPlaybackResolver;
import org.schabi.newpipe.player.resolver.PlaybackResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver.SourceType;
import org.schabi.newpipe.player.ui.MainPlayerUi;
import org.schabi.newpipe.player.ui.PlayerUi;
import org.schabi.newpipe.player.ui.PlayerUiList;
import org.schabi.newpipe.player.ui.PopupPlayerUi;
import org.schabi.newpipe.player.ui.VideoPlayerUi;
import org.schabi.newpipe.util.DebugFileLog;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.ListHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PersistentPlayerLogger;
import org.schabi.newpipe.util.SerializedCache;
import org.schabi.newpipe.util.StreamStateCache;
import org.schabi.newpipe.util.StreamTypeUtil;
import org.schabi.newpipe.util.image.PicassoHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class Player implements PlaybackListener, Listener {
    public static final boolean DEBUG = MainActivity.DEBUG;
    public static final String TAG = Player.class.getSimpleName();

    /*//////////////////////////////////////////////////////////////////////////
    // States
    //////////////////////////////////////////////////////////////////////////*/

    public static final int STATE_PREFLIGHT = -1;
    public static final int STATE_BLOCKED = 123;
    public static final int STATE_PLAYING = 124;
    public static final int STATE_BUFFERING = 125;
    public static final int STATE_PAUSED = 126;
    public static final int STATE_PAUSED_SEEK = 127;
    public static final int STATE_COMPLETED = 128;

    /*//////////////////////////////////////////////////////////////////////////
    // Intent
    //////////////////////////////////////////////////////////////////////////*/

    public static final String PLAYBACK_QUALITY = "playback_quality";
    public static final String PLAY_QUEUE_KEY = "play_queue_key";
    public static final String RESUME_PLAYBACK = "resume_playback";
    public static final String PLAY_WHEN_READY = "play_when_ready";
    public static final String PLAYER_TYPE = "player_type";
    public static final String PLAYER_INTENT_TYPE = "player_intent_type";
    public static final String PLAYER_INTENT_DATA = "player_intent_data";
    public static final String PLAYBACK_START_TRACE_ID = "playback_start_trace_id";
    public static final String PLAYBACK_START_TRACE_ELAPSED_REALTIME_MS =
            "playback_start_trace_elapsed_realtime_ms";

    /*//////////////////////////////////////////////////////////////////////////
    // Time constants
    //////////////////////////////////////////////////////////////////////////*/

    public static final int PLAY_PREV_ACTIVATION_LIMIT_MILLIS = 5000; // 5 seconds
    public static final int PROGRESS_LOOP_INTERVAL_MILLIS = 1000; // 1 second

    /*//////////////////////////////////////////////////////////////////////////
    // Other constants
    //////////////////////////////////////////////////////////////////////////*/

    public static final int RENDERER_UNAVAILABLE = -1;
    private static final String PICASSO_PLAYER_THUMBNAIL_TAG = "PICASSO_PLAYER_THUMBNAIL_TAG";
    private static final int LIVE_END_MAX_RETRIES = 8;
    private static final long LIVE_END_RETRY_RESET_MILLIS = 60_000L;

    /*//////////////////////////////////////////////////////////////////////////
    // Playback
    //////////////////////////////////////////////////////////////////////////*/

    // play queue might be null e.g. while player is starting
    @Nullable
    private PlayQueue playQueue;

    @Nullable
    private MediaSourceManager playQueueManager;

    @Nullable
    private PlayQueueItem currentItem;
    @Nullable
    private MediaItemTag currentMetadata;
    @Nullable
    private Bitmap currentThumbnail;

    /*//////////////////////////////////////////////////////////////////////////
    // Player
    //////////////////////////////////////////////////////////////////////////*/

    private ExoPlayer simpleExoPlayer;
    private AudioReactor audioReactor;

    @NonNull
    private final DefaultTrackSelector trackSelector;
    @NonNull
    private final LoadController loadController;
    @NonNull
    private final DefaultRenderersFactory renderFactory;

    @NonNull
    private final PlayerDataSource dataSource;
    @NonNull
    private final VideoPlaybackResolver videoResolver;
    @NonNull
    private final AudioPlaybackResolver audioResolver;

    private final PlayerService service; //TODO try to remove and replace everything with context

    /*//////////////////////////////////////////////////////////////////////////
    // Player states
    //////////////////////////////////////////////////////////////////////////*/

    private PlayerType playerType = PlayerType.MAIN;
    private int currentState = STATE_PREFLIGHT;

    // audio only mode does not mean that player type is background, but that the player was
    // minimized to background but will resume automatically to the original player type
    private boolean isAudioOnly = false;
    private boolean isPrepared = false;
    @Nullable
    private String liveEndRetryUrl = null;
    private int liveEndRetryCount = 0;
    private long liveEndRetryElapsed = 0L;
    private long bufferingStartElapsedRealtimeMs = -1L;
    private int bufferingStartPositionMs = C.INDEX_UNSET;
    private boolean mediaTunnelingDisabledByDecoderProbe = false;
    private long playbackStartTraceId = -1L;
    private long playbackStartTraceStartMs = -1L;
    private long playbackStartTraceLastMs = -1L;
    private boolean playbackStartFirstFrameLogged = false;
    // True while a single handleIntent() call has just (re)initialized playback via initPlayback().
    // Lets handleIntentPost() skip a redundant reloadPlayQueueManager() (the freshly built manager
    // is already correct for the current playerType). See OPTIMIZATIONS.md.
    private boolean initPlaybackRanThisIntent = false;

    /*//////////////////////////////////////////////////////////////////////////
    // UIs, listeners and disposables
    //////////////////////////////////////////////////////////////////////////*/

    @SuppressWarnings({"MemberName", "java:S116"}) // keep the unusual member name
    private final PlayerUiList UIs;

    private BroadcastReceiver broadcastReceiver;
    private IntentFilter intentFilter;
    @Nullable
    private PlayerServiceEventListener fragmentListener = null;
    @Nullable
    private PlayerEventListener activityListener = null;

    @NonNull
    private final SerialDisposable progressUpdateDisposable = new SerialDisposable();
    @NonNull
    private final SerialDisposable diskCachePreloadDisposable = new SerialDisposable();
    // Cache key of the stream backing the seekbar timeline (the video stream, or audio for
    // audio-only playback). Used to surface on-disk prefetch progress as the secondary seekbar.
    @Nullable
    private String diskPreloadProgressKey;
    // elapsedRealtime when the last user seek was issued, to measure perceived seek latency.
    private long seekProbeStartMs = -1L;
    // elapsedRealtime of the last "State probe" log, to throttle it to ~1 Hz.
    private long lastStateProbeMs = 0L;

    // adb-triggerable debug control actions (only registered/handled in DEBUG builds).
    private static final String DEBUG_ACTION_SEEK_TO = "org.schabi.newpipe.debug.player.SEEK_TO";
    private static final String DEBUG_ACTION_SEEK_BY = "org.schabi.newpipe.debug.player.SEEK_BY";
    private static final String DEBUG_ACTION_PLAY = "org.schabi.newpipe.debug.player.PLAY";
    private static final String DEBUG_ACTION_PAUSE = "org.schabi.newpipe.debug.player.PAUSE";
    private static final String DEBUG_ACTION_STATE = "org.schabi.newpipe.debug.player.STATE";
    @NonNull
    private final CompositeDisposable databaseUpdateDisposable = new CompositeDisposable();
    @NonNull
    private final CompositeDisposable streamItemDisposable = new CompositeDisposable();

    // This is the only listener we need for thumbnail loading, since there is always at most only
    // one thumbnail being loaded at a time. This field is also here to maintain a strong reference,
    // which would otherwise be garbage collected since Picasso holds weak references to targets.
    @NonNull
    private final Target currentThumbnailTarget;

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull
    private final Context context;
    @NonNull
    private final SharedPreferences prefs;
    @NonNull
    private final HistoryRecordManager recordManager;
    @NonNull
    private final AnalyticsListener lagProbeAnalyticsListener = new AnalyticsListener() {
        @Override
        public void onVideoDecoderInitialized(@NonNull final EventTime eventTime,
                                              @NonNull final String decoderName,
                                              final long initializedTimestampMs,
                                              final long initializationDurationMs) {
            Log.i(TAG, "Playback codec - video decoder initialized: " + decoderName
                    + " (" + classifyDecoder(decoderName) + ")"
                    + " in " + initializationDurationMs + "ms"
                    + ", tunneling=" + trackSelector.getParameters().tunnelingEnabled);
            maybeDisableTunnelingForDecoder(decoderName);
        }

        @Override
        public void onVideoInputFormatChanged(@NonNull final EventTime eventTime,
                                              @NonNull final Format format,
                                              @Nullable final DecoderReuseEvaluation
                                                      decoderReuseEvaluation) {
            Log.i(TAG, "Playback codec - video input format: mime=" + format.sampleMimeType
                    + ", codecs=" + format.codecs
                    + ", width=" + format.width
                    + ", height=" + format.height
                    + ", frameRate=" + format.frameRate
                    + ", bitrate=" + format.bitrate);
        }

        @Override
        public void onAudioDecoderInitialized(@NonNull final EventTime eventTime,
                                              @NonNull final String decoderName,
                                              final long initializedTimestampMs,
                                              final long initializationDurationMs) {
            Log.i(TAG, "Playback codec - audio decoder initialized: " + decoderName
                    + " (" + classifyDecoder(decoderName) + ")"
                    + " in " + initializationDurationMs + "ms");
        }

        @Override
        public void onAudioInputFormatChanged(@NonNull final EventTime eventTime,
                                              @NonNull final Format format,
                                              @Nullable final DecoderReuseEvaluation
                                                      decoderReuseEvaluation) {
            Log.i(TAG, "Playback codec - audio input format: mime=" + format.sampleMimeType
                    + ", codecs=" + format.codecs
                    + ", channelCount=" + format.channelCount
                    + ", sampleRate=" + format.sampleRate
                    + ", bitrate=" + format.bitrate);
        }

        @Override
        public void onDroppedVideoFrames(@NonNull final EventTime eventTime,
                                         final int droppedFrames,
                                         final long elapsedMs) {
            if (droppedFrames >= 12 || elapsedMs >= 1000L) {
                Log.w(TAG, "Lag probe - dropped frames=" + droppedFrames
                        + ", elapsedMs=" + elapsedMs
                        + ", state=" + currentState
                        + ", url=" + getVideoUrl());
            }
        }

        @Override
        public void onVideoCodecError(@NonNull final EventTime eventTime,
                                      @NonNull final Exception videoCodecError) {
            Log.e(TAG, "Lag probe - video codec error", videoCodecError);
        }

        @Override
        public void onVideoFrameProcessingOffset(@NonNull final EventTime eventTime,
                                                 final long totalProcessingOffsetUs,
                                                 final int frameCount) {
            if (frameCount <= 0) {
                return;
            }
            final long averageOffsetUs = totalProcessingOffsetUs / frameCount;
            if (averageOffsetUs >= 40_000L && frameCount >= 20) {
                Log.w(TAG, "Lag probe - slow frame processing avgOffsetUs=" + averageOffsetUs
                        + ", frameCount=" + frameCount
                        + ", state=" + currentState
                        + ", url=" + getVideoUrl());
            }
        }

        @Override
        public void onLoadStarted(@NonNull final EventTime eventTime,
                                  @NonNull final LoadEventInfo loadEventInfo,
                                  @NonNull final MediaLoadData mediaLoadData) {
            // A load started: the requested range was NOT already resident in ExoPlayer's sample
            // queue and had to be fetched (from the on-disk cache and/or network - see the
            // "Cache probe" logs in CacheFactory to tell which). If this fires right after a seek,
            // the seek target was not in memory.
            final long sinceSeek = seekProbeStartMs < 0 ? -1
                    : SystemClock.elapsedRealtime() - seekProbeStartMs;
            Log.d(TAG, "Load probe - LOAD STARTED bytePos=" + loadEventInfo.dataSpec.position
                    + " byteLen=" + loadEventInfo.dataSpec.length
                    + " trackType=" + mediaLoadData.trackType
                    + " mediaStartMs=" + mediaLoadData.mediaStartTimeMs
                    + " msSinceLastSeek=" + sinceSeek
                    // If this dataSpec key differs from the preload key ("Disk preload progress"
                    // log), playback and preload cache under different keys -> playback never hits
                    // the prefetched bytes and the file gets cached twice.
                    + " dataSpecKey=" + loadEventInfo.dataSpec.key);
        }

        @Override
        public void onLoadCompleted(@NonNull final EventTime eventTime,
                                    @NonNull final LoadEventInfo loadEventInfo,
                                    @NonNull final MediaLoadData mediaLoadData) {
            // Throughput alone cannot tell disk from network here: YouTube serves the first MB or
            // so of each range request unthrottled, so a small DASH chunk fetched from the network
            // arrives as fast as one read from disk. The authoritative cache-hit signal is
            // CacheFactory's "served from DISK cache" (onCachedBytesRead) line.
            final long ms = Math.max(1, loadEventInfo.loadDurationMs);
            final long mbPerSec = loadEventInfo.bytesLoaded * 1000L / ms / (1024 * 1024);
            Log.d(TAG, "Load probe - load completed bytesLoaded=" + loadEventInfo.bytesLoaded
                    + " loadDurationMs=" + loadEventInfo.loadDurationMs
                    + " ~" + mbPerSec + "MB/s"
                    + " bytePos=" + loadEventInfo.dataSpec.position);
        }

        @Override
        public void onRenderedFirstFrame(@NonNull final EventTime eventTime,
                                         @NonNull final Object output,
                                         final long renderTimeMs) {
            if (seekProbeStartMs >= 0) {
                Log.d(TAG, "Seek probe - first frame rendered "
                        + (SystemClock.elapsedRealtime() - seekProbeStartMs)
                        + "ms after seek (this is the perceived double-tap wait)");
                seekProbeStartMs = -1L;
            }
        }
    };

    private static final int AUDIO_PREAMP_MIN_DB = -50;
    private static final int AUDIO_PREAMP_MAX_DB = 0;
    private static final int AUDIO_PREAMP_DEFAULT_DB = -20;

    private float baseVolume = 1.0f;
    private float audioPreampGain = 1.0f;
    private SharedPreferences.OnSharedPreferenceChangeListener audioPreampListener;


    /*//////////////////////////////////////////////////////////////////////////
    // Constructor
    //////////////////////////////////////////////////////////////////////////*/
    //region Constructor

    /**
     * @param service the service this player resides in
     * @param mediaSession used to build the {@link MediaSessionPlayerUi}, lives in the service and
     *                     could possibly be reused with multiple player instances
     * @param sessionConnector used to build the {@link MediaSessionPlayerUi}, lives in the service
     *                         and could possibly be reused with multiple player instances
     */
    public Player(@NonNull final PlayerService service,
                  @NonNull final MediaSessionCompat mediaSession,
                  @NonNull final MediaSessionConnector sessionConnector) {
        this.service = service;
        context = service;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        recordManager = new HistoryRecordManager(context);

        setupBroadcastReceiver();

        audioPreampListener = (sharedPreferences, key) -> {
            if (key == null || key.equals(context.getString(R.string.audio_preamp_key))) {
                updateAudioPreampFromPrefs();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(audioPreampListener);
        updateAudioPreampFromPrefs();

        trackSelector = new DefaultTrackSelector(context, PlayerHelper.getQualitySelector());
        dataSource = new PlayerDataSource(context,
                new DefaultBandwidthMeter.Builder(context).build());
        loadController = new LoadController();

        renderFactory = prefs.getBoolean(
                context.getString(
                        R.string.always_use_exoplayer_set_output_surface_workaround_key), false)
                ? new CustomRenderersFactory(context) : new DefaultRenderersFactory(context);

        renderFactory.setEnableDecoderFallback(
                prefs.getBoolean(
                        context.getString(
                                R.string.use_exoplayer_decoder_fallback_key), false));
        renderFactory.setMediaCodecSelector(MediaCodecSelector.DEFAULT);

        if (DeviceUtils.isTensorG4()) {
            // Tensor G4 devices may suffer periodic codec callback/reclaim stalls with async
            // queueing; force synchronous queueing to avoid stale callback spikes.
            renderFactory.forceDisableMediaCodecAsynchronousQueueing();
            Log.i(TAG, "Lag probe - applied Tensor G4 codec queueing workaround");
        }

        videoResolver = new VideoPlaybackResolver(context, dataSource, getQualityResolver());
        audioResolver = new AudioPlaybackResolver(context, dataSource);

        currentThumbnailTarget = getCurrentThumbnailTarget();

        // The UIs added here should always be present. They will be initialized when the player
        // reaches the initialization step. Make sure the media session ui is before the
        // notification ui in the UIs list, since the notification depends on the media session in
        // PlayerUi#initPlayer(), and UIs.call() guarantees UI order is preserved.
        UIs = new PlayerUiList(
                new MediaSessionPlayerUi(this, mediaSession, sessionConnector),
                new NotificationPlayerUi(this)
        );
    }

    private VideoPlaybackResolver.QualityResolver getQualityResolver() {
        return new VideoPlaybackResolver.QualityResolver() {
            @Override
            public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
                return videoPlayerSelected()
                        ? ListHelper.getDefaultResolutionIndex(context, sortedVideos)
                        : ListHelper.getPopupDefaultResolutionIndex(context, sortedVideos);
            }

            @Override
            public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                                  final String playbackQuality) {
                return videoPlayerSelected()
                        ? getResolutionIndex(context, sortedVideos, playbackQuality)
                        : getPopupResolutionIndex(context, sortedVideos, playbackQuality);
            }
        };
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback initialization via intent
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback initialization via intent

    @SuppressWarnings("MethodLength")
    public void handleIntent(@NonNull final Intent intent) {
        updatePlaybackStartTrace(intent);
        initPlaybackRanThisIntent = false;
        logPlaybackStartTrace("handleIntent.start");
        PersistentPlayerLogger.log(context, "Player.handleIntent.start "
                + "playerType=" + playerType
                + " playQueueNull=" + (playQueue == null)
                + " currentState=" + currentState
                + " playWhenReadyExtra=" + intent.getBooleanExtra(PLAY_WHEN_READY, false)
                + " resumeExtra=" + intent.getBooleanExtra(RESUME_PLAYBACK, false));
        final var playerIntentType = IntentCompat.getSerializableExtra(intent, PLAYER_INTENT_TYPE,
                PlayerIntentType.class);
        if (playerIntentType == null) {
            PersistentPlayerLogger.log(context, "Player.handleIntent.skipped noIntentType");
            return;
        }
        // TODO: this should be in the second switch below, but I’m not sure whether I
        // can move the initUIs stuff without breaking the setup for edge cases somehow.
        // when playing from a timestamp, keep the current player as-is.
        if (playerIntentType != PlayerIntentType.TimestampChange) {
            playerType = IntentCompat.getSerializableExtra(intent, PLAYER_TYPE, PlayerType.class);
        }
        initUIsForCurrentPlayerType();
        isAudioOnly = audioPlayerSelected();

        if (intent.hasExtra(PLAYBACK_QUALITY)) {
            videoResolver.setPlaybackQuality(intent.getStringExtra(PLAYBACK_QUALITY));
        }

        final boolean playWhenReady = intent.getBooleanExtra(PLAY_WHEN_READY, true);

        switch (playerIntentType) {
            case Enqueue -> {
                if (playQueue != null) {
                    final PlayQueue newQueue = getPlayQueueFromCache(intent);
                    if (newQueue == null) {
                        return;
                    }
                    playQueue.append(newQueue.getStreams());
                    return;
                }

                // TODO: This falls through to the old logic, there was no playQueue
                // yet so we should start the player and add the new video
                break;
            }
            case EnqueueNext -> {
                if (playQueue != null) {
                    final PlayQueue newQueue = getPlayQueueFromCache(intent);
                    if (newQueue == null) {
                        return;
                    }
                    final PlayQueueItem newItem = newQueue.getStreams().get(0);
                    playQueue.enqueueNext(newItem, false);
                    return;
                }

                // TODO: This falls through to the old logic, there was no playQueue
                // yet so we should start the player and add the new video
                break;
            }
            case TimestampChange -> {
                final var data = Objects.requireNonNull(IntentCompat.getParcelableExtra(intent,
                        PLAYER_INTENT_DATA, TimestampChangeData.class));
                final Single<StreamInfo> single =
                        ExtractorHelper.getStreamInfo(data.getServiceId(), data.getUrl(), false);
                streamItemDisposable.add(single.subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(info -> {
                            final @Nullable PlayQueue oldPlayQueue = playQueue;
                            info.setStartPosition(data.getSeconds());
                            final PlayQueueItem playQueueItem = new PlayQueueItem(info);

                            // If the stream is already playing,
                            // we can just seek to the appropriate timestamp
                            if (oldPlayQueue != null
                                    && playQueueItem.isSameItem(oldPlayQueue.getItem())) {
                                // Player can have state = IDLE when playback is stopped or failed
                                // and we should retry in this case
                                if (simpleExoPlayer.getPlaybackState()
                                        == com.google.android.exoplayer2.Player.STATE_IDLE) {
                                    simpleExoPlayer.prepare();
                                }
                                simpleExoPlayer.seekTo(oldPlayQueue.getIndex(),
                                        data.getSeconds() * 1000L);
                                simpleExoPlayer.setPlayWhenReady(playWhenReady);

                            } else {
                                final PlayQueue newPlayQueue;

                                // If there is no queue yet, just add our item
                                if (oldPlayQueue == null) {
                                    newPlayQueue = new SinglePlayQueue(playQueueItem);

                                // else we add the timestamped stream behind the current video
                                // and start playing it.
                                } else {
                                    oldPlayQueue.enqueueNext(playQueueItem, true);
                                    oldPlayQueue.offsetIndex(1);
                                    newPlayQueue = oldPlayQueue;
                                }
                                initPlayback(newPlayQueue, playWhenReady);
                            }

                        }, throwable -> {
                            // This will only show a snackbar if the passed context has a root view:
                            // otherwise it will resort to showing a notification, so we are safe
                            // here.
                            final var info = new ErrorInfo(throwable, UserAction.PLAY_ON_POPUP,
                                    data.getUrl(), null, data.getUrl());
                            ErrorUtil.createNotification(context, info);
                        }));
                return;
            }
            case AllOthers -> {
                // fallthrough; TODO: put other intent data in separate cases
            }
        }

        final PlayQueue newQueue = getPlayQueueFromCache(intent);
        if (newQueue == null) {
            logPlaybackStartTrace("handleIntent.noQueue");
            return;
        }
        logPlaybackStartTrace("handleIntent.queueReady "
                + "newQueueSize=" + newQueue.size()
                + " currentQueueSize=" + (playQueue == null ? -1 : playQueue.size()));

        // branching parameters for below
        final boolean samePlayQueue = playQueue != null && playQueue.equalStreamsAndIndex(newQueue);

        /*
         * TODO As seen in #7427 this does not work:
         * There are 3 situations when playback shouldn't be started from scratch (zero timestamp):
         * 1. User pressed on a timestamp link and the same video should be rewound to the timestamp
         * 2. User changed a player from, for example. main to popup, or from audio to main, etc
         * 3. User chose to resume a video based on a saved timestamp from history of played videos
         * In those cases time will be saved because re-init of the play queue is a not an instant
         *  task and requires network calls
         * */
        // seek to timestamp if stream is already playing
        if (!exoPlayerIsNull()
                && newQueue.size() == 1 && newQueue.getItem() != null
                && playQueue != null && playQueue.size() == 1 && playQueue.getItem() != null
                && newQueue.getItem().isSameItem(playQueue.getItem())
                && newQueue.getItem().getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET) {
            // Player can have state = IDLE when playback is stopped or failed
            // and we should retry in this case
            if (simpleExoPlayer.getPlaybackState()
                    == com.google.android.exoplayer2.Player.STATE_IDLE) {
                logPlaybackStartTrace("handleIntent.prepareSameTimestamp");
                simpleExoPlayer.prepare();
            }
            logPlaybackStartTrace("handleIntent.seekSameTimestamp");
            simpleExoPlayer.seekTo(playQueue.getIndex(), newQueue.getItem().getRecoveryPosition());
            simpleExoPlayer.setPlayWhenReady(playWhenReady);

        } else if (!exoPlayerIsNull()
                && samePlayQueue
                && playQueue != null
                && !playQueue.isDisposed()) {
            // Do not re-init the same PlayQueue. Save time
            // Player can have state = IDLE when playback is stopped or failed
            // and we should retry in this case
            if (simpleExoPlayer.getPlaybackState()
                    == com.google.android.exoplayer2.Player.STATE_IDLE) {
                logPlaybackStartTrace("handleIntent.prepareSameQueue");
                simpleExoPlayer.prepare();
            }
            logPlaybackStartTrace("handleIntent.sameQueuePlayWhenReady");
            simpleExoPlayer.setPlayWhenReady(playWhenReady);

        } else if (intent.getBooleanExtra(RESUME_PLAYBACK, false)
                && DependentPreferenceHelper.getResumePlaybackEnabled(context)
                // !samePlayQueue
                && (playQueue == null || !playQueue.equalStreamsAndIndex(newQueue))
                && !newQueue.isEmpty()
                && newQueue.getItem() != null
                && newQueue.getItem().getRecoveryPosition() == PlayQueueItem.RECOVERY_UNSET) {
            // Fast path: if the resume position was warmed into StreamStateCache during prefetch,
            // use it synchronously and call initPlayback() inline — before the detail fragment's
            // handleResult() rendering claims the main thread. The async DB callback below is
            // otherwise queued behind that rendering, delaying media-source resolve + first-segment
            // buffering by ~400ms. See StreamStateCache / OPTIMIZATIONS.md.
            final StreamStateEntity[] cachedState =
                    StreamStateCache.consume(newQueue.getItem().getUrl());
            if (cachedState != null) {
                final StreamStateEntity state = cachedState[0];
                if (state != null && !state.isFinished(newQueue.getItem().getDuration())) {
                    newQueue.setRecovery(newQueue.getIndex(), state.getProgressMillis());
                }
                logPlaybackStartTrace("handleIntent.resumePlayback.cachedState hasState="
                        + (state != null));
                initPlayback(newQueue, playWhenReady);
                return;
            }

            logPlaybackStartTrace("handleIntent.resumePlayback.dbStart");
            databaseUpdateDisposable.add(recordManager.loadStreamState(newQueue.getItem())
                    .observeOn(AndroidSchedulers.mainThread())
                    // Do not place initPlayback() in doFinally() because
                    // it restarts playback after destroy()
                    //.doFinally()
                    .subscribe(
                            state -> {
                                if (!state.isFinished(newQueue.getItem().getDuration())) {
                                    // resume playback only if the stream was not played to the end
                                    newQueue.setRecovery(newQueue.getIndex(),
                                            state.getProgressMillis());
                                }
                                logPlaybackStartTrace("handleIntent.resumePlayback.stateLoaded");
                                initPlayback(newQueue, playWhenReady);
                            },
                            error -> {
                                if (DEBUG) {
                                    Log.w(TAG, "Failed to start playback", error);
                                }
                                // In case any error we can start playback without history
                                logPlaybackStartTrace("handleIntent.resumePlayback.stateFailed "
                                        + error.getClass().getSimpleName());
                                initPlayback(newQueue, playWhenReady);
                            },
                            () -> {
                                // Completed but not found in history
                                logPlaybackStartTrace("handleIntent.resumePlayback.noHistory");
                                initPlayback(newQueue, playWhenReady);
                            }
                    ));
        } else {
            // Good to go...
            // In a case of equal PlayQueues we can re-init old one but only when it is disposed
            logPlaybackStartTrace("handleIntent.initPlayback samePlayQueue=" + samePlayQueue);
            initPlayback(samePlayQueue ? playQueue : newQueue, playWhenReady);
        }

    }


    public void handleIntentPost(final PlayerType oldPlayerType) {
        // When initPlayback() ran during this intent it already built a fresh playQueueManager for
        // the current playerType, so reloading here would be pure waste (~300ms of media-source
        // re-resolution on the critical path). Only reload when the player type actually changed on
        // an already-existing queue that handleIntent did NOT reinitialize.
        if (oldPlayerType != playerType && playQueue != null && !initPlaybackRanThisIntent) {
            // If playerType changes from one to another we should reload the player
            // (to disable/enable video stream or to set quality)
            reloadPlayQueueManager();
        }

        UIs.call(PlayerUi::setupAfterIntent);

        // Only announce "a player has started" when there is actual content to show (a play queue).
        // A content-less start happens when the service is merely (re)warmed for performance via
        // PlayerHolder.warmServiceForStartup() (e.g. list prefetch right after the user closed the
        // mini player with X): onStartCommand() builds a bare prewarmed player and still funnels
        // the warm intent through here. Broadcasting the started event in that case makes
        // VideoDetailFragment pop the (empty) mini player back up, producing a close/respawn loop.
        if (playQueue != null) {
            NavigationHelper.sendPlayerStartedEvent(context);
        }
    }

    @Nullable
    private static PlayQueue getPlayQueueFromCache(@NonNull final Intent intent) {
        final String queueCache = intent.getStringExtra(PLAY_QUEUE_KEY);
        if (queueCache == null) {
            return null;
        }
        final PlayQueue newQueue = SerializedCache.getInstance().take(queueCache, PlayQueue.class);
        if (newQueue == null) {
            return null;
        }
        return newQueue;
    }

    private void initUIsForCurrentPlayerType() {
        if ((UIs.get(MainPlayerUi.class).isPresent() && playerType == PlayerType.MAIN)
                || (UIs.get(PopupPlayerUi.class).isPresent() && playerType == PlayerType.POPUP)) {
            // correct UI already in place
            return;
        }

        // try to reuse binding if possible
        final PlayerBinding binding = UIs.get(VideoPlayerUi.class).map(VideoPlayerUi::getBinding)
                .orElseGet(() -> {
                    if (playerType == PlayerType.AUDIO) {
                        return null;
                    } else {
                        return PlayerBinding.inflate(LayoutInflater.from(context));
                    }
                });

        switch (playerType) {
            case MAIN:
                UIs.destroyAll(PopupPlayerUi.class);
                UIs.addAndPrepare(new MainPlayerUi(this, binding));
                break;
            case POPUP:
                UIs.destroyAll(MainPlayerUi.class);
                UIs.addAndPrepare(new PopupPlayerUi(this, binding));
                break;
            case AUDIO:
                UIs.destroyAll(VideoPlayerUi.class);
                break;
        }
    }

    private void initPlayback(@NonNull final PlayQueue queue,
                              final boolean playOnReady) {
        initPlaybackRanThisIntent = true;
        logPlaybackStartTrace("initPlayback.start queueSize=" + queue.size()
                + " playOnReady=" + playOnReady);
        if (canReusePrewarmedPlayer()) {
            logPlaybackStartTrace("initPlayback.reusePrewarmedPlayer");
            PersistentPlayerLogger.log(context, "Player.initPlayback.reusePrewarmedPlayer");
            simpleExoPlayer.stop();
            simpleExoPlayer.setPlayWhenReady(playOnReady);
        } else {
            destroyPlayer();
            initPlayer(playOnReady);
        }
        final boolean playbackSkipSilence = getPrefs().getBoolean(getContext().getString(
                R.string.playback_skip_silence_key), getPlaybackSkipSilence());
        final PlaybackParameters savedParameters = retrievePlaybackParametersFromPrefs(this);
        setPlaybackParameters(savedParameters.speed, savedParameters.pitch, playbackSkipSilence);

        playQueue = queue;
        playQueue.init();
        reloadPlayQueueManager();

        UIs.call(PlayerUi::initPlayback);

        applyVolume();
        notifyQueueUpdateToListeners();
        logPlaybackStartTrace("initPlayback.end");
    }

    private boolean canReusePrewarmedPlayer() {
        return !exoPlayerIsNull()
                && playQueue == null
                && playQueueManager == null
                && currentItem == null
                && currentMetadata == null;
    }

    /**
     * Builds the ExoPlayer instance during the service warmup window so a later tap can reuse it
     * instead of paying the builder/media-session setup cost inside the tap-to-first-frame path.
     */
    public void prewarmPlayer() {
        if (!exoPlayerIsNull()) {
            return;
        }
        PersistentPlayerLogger.log(context, "Player.prewarmPlayer.start");
        initPlayer(false);
        PersistentPlayerLogger.log(context, "Player.prewarmPlayer.end");
    }

    private void initPlayer(final boolean playOnReady) {
        if (DEBUG) {
            Log.d(TAG, "initPlayer() called with: playOnReady = [" + playOnReady + "]");
        }
        PersistentPlayerLogger.log(context, "Player.initPlayer "
                + "playOnReady=" + playOnReady
                + " playerType=" + playerType
                + " playQueueNull=" + (playQueue == null)
                + " playQueueSize=" + (playQueue == null ? -1 : playQueue.size()));
        logPlaybackStartTrace("initPlayer.start playOnReady=" + playOnReady);

        simpleExoPlayer = new ExoPlayer.Builder(context, renderFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadController)
                .setUsePlatformDiagnostics(false)
                .build();
        simpleExoPlayer.addListener(this);
        simpleExoPlayer.addAnalyticsListener(lagProbeAnalyticsListener);
        simpleExoPlayer.setPlayWhenReady(playOnReady);
        simpleExoPlayer.setSeekParameters(PlayerHelper.getSeekParameters(context));
        simpleExoPlayer.setWakeMode(C.WAKE_MODE_NETWORK);
        simpleExoPlayer.setHandleAudioBecomingNoisy(true);

        audioReactor = new AudioReactor(context, simpleExoPlayer, this::setBaseVolume);

        registerBroadcastReceiver();

        // Setup UIs
        UIs.call(PlayerUi::initPlayer);

        // Disable media tunneling if requested by the user from ExoPlayer settings
        final boolean disableMediaTunneling = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.disable_media_tunneling_key), false);
        if (!disableMediaTunneling) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTunnelingEnabled(true));
        }
        Log.i(TAG, "Lag probe - media tunneling enabled=" + !disableMediaTunneling);
        logPlaybackStartTrace("initPlayer.end tunnelingEnabled=" + !disableMediaTunneling);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Destroy and recovery
    //////////////////////////////////////////////////////////////////////////*/
    //region Destroy and recovery

    private void destroyPlayer() {
        if (DEBUG) {
            Log.d(TAG, "destroyPlayer() called");
        }
        PersistentPlayerLogger.log(context, "Player.destroyPlayer "
                + "exoPlayerNull=" + exoPlayerIsNull()
                + " currentState=" + currentState);
        UIs.call(PlayerUi::destroyPlayer);
        diskCachePreloadDisposable.set(null);

        if (!exoPlayerIsNull()) {
            simpleExoPlayer.removeAnalyticsListener(lagProbeAnalyticsListener);
            simpleExoPlayer.removeListener(this);
            simpleExoPlayer.stop();
            simpleExoPlayer.release();
        }
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }
        if (playQueue != null) {
            playQueue.dispose();
        }
        if (audioReactor != null) {
            audioReactor.dispose();
        }
        if (playQueueManager != null) {
            playQueueManager.dispose();
        }
    }

    public void destroy() {
        if (DEBUG) {
            Log.d(TAG, "destroy() called");
        }

        if (audioPreampListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(audioPreampListener);
            audioPreampListener = null;
        }

        saveStreamProgressState();
        setRecovery();
        stopActivityBinding();

        destroyPlayer();
        unregisterBroadcastReceiver();

        databaseUpdateDisposable.clear();
        progressUpdateDisposable.set(null);
        diskCachePreloadDisposable.set(null);
        streamItemDisposable.clear();
        cancelLoadingCurrentThumbnail();

        UIs.destroyAll(Object.class); // destroy every UI: obviously every UI extends Object
    }

    public void setRecovery() {
        if (playQueue == null || exoPlayerIsNull()) {
            return;
        }

        final int queuePos = playQueue.getIndex();
        final long windowPos = simpleExoPlayer.getCurrentPosition();
        final long duration = simpleExoPlayer.getDuration();

        // No checks due to https://github.com/TeamNewPipe/NewPipe/pull/7195#issuecomment-962624380
        setRecovery(queuePos, MathUtils.clamp(windowPos, 0, duration));
    }

    private void setRecovery(final int queuePos, final long windowPos) {
        if (playQueue == null || playQueue.size() <= queuePos) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Setting recovery, queue: " + queuePos + ", pos: " + windowPos);
        }
        playQueue.setRecovery(queuePos, windowPos);
    }

    public void reloadPlayQueueManager() {
        if (playQueueManager != null) {
            playQueueManager.dispose();
        }

        if (playQueue != null) {
            logPlaybackStartTrace("reloadPlayQueueManager.create queueSize=" + playQueue.size());
            playQueueManager = new MediaSourceManager(this, playQueue);
        }
    }

    @Override // own playback listener
    public void onPlaybackShutdown() {
        if (DEBUG) {
            Log.d(TAG, "onPlaybackShutdown() called");
        }
        // destroys the service, which in turn will destroy the player
        service.destroyPlayerAndStopService();
    }

    public void smoothStopForImmediateReusing() {
        // Pausing would make transition from one stream to a new stream not smooth, so only stop
        simpleExoPlayer.stop();
        setRecovery();
        UIs.call(PlayerUi::smoothStopForImmediateReusing);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Broadcast receiver
    //////////////////////////////////////////////////////////////////////////*/
    //region Broadcast receiver

    /**
     * This function prepares the broadcast receiver and is called only in the constructor.
     * Therefore if you want any PlayerUi to receive a broadcast action, you should add it here,
     * even if that player ui might never be added to the player. In that case the received
     * broadcast would not do anything.
     */
    private void setupBroadcastReceiver() {
        if (DEBUG) {
            Log.d(TAG, "setupBroadcastReceiver() called");
        }

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context ctx, final Intent intent) {
                onBroadcastReceived(intent);
            }
        };
        intentFilter = new IntentFilter();

        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

        intentFilter.addAction(ACTION_CLOSE);
        intentFilter.addAction(ACTION_PLAY_PAUSE);
        intentFilter.addAction(ACTION_PLAY_PREVIOUS);
        intentFilter.addAction(ACTION_PLAY_NEXT);
        intentFilter.addAction(ACTION_FAST_REWIND);
        intentFilter.addAction(ACTION_FAST_FORWARD);
        intentFilter.addAction(ACTION_REPEAT);
        intentFilter.addAction(ACTION_SHUFFLE);
        intentFilter.addAction(ACTION_RECREATE_NOTIFICATION);

        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED);
        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED);

        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);

        if (DEBUG) {
            // adb-triggerable debug controls, e.g.:
            //   adb shell am broadcast -a org.schabi.newpipe.debug.player.SEEK_TO --el ms 90000
            //   adb shell am broadcast -a org.schabi.newpipe.debug.player.SEEK_BY --el ms -10000
            //   adb shell am broadcast -a org.schabi.newpipe.debug.player.PLAY
            //   adb shell am broadcast -a org.schabi.newpipe.debug.player.PAUSE
            //   adb shell am broadcast -a org.schabi.newpipe.debug.player.STATE
            intentFilter.addAction(DEBUG_ACTION_SEEK_TO);
            intentFilter.addAction(DEBUG_ACTION_SEEK_BY);
            intentFilter.addAction(DEBUG_ACTION_PLAY);
            intentFilter.addAction(DEBUG_ACTION_PAUSE);
            intentFilter.addAction(DEBUG_ACTION_STATE);
        }
    }

    private void onBroadcastReceived(final Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "onBroadcastReceived() called with: intent = [" + intent + "]");
        }

        switch (intent.getAction()) {
            case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                pause();
                break;
            case ACTION_CLOSE:
                service.destroyPlayerAndStopService();
                break;
            case ACTION_PLAY_PAUSE:
                playPause();
                break;
            case ACTION_PLAY_PREVIOUS:
                playPrevious();
                break;
            case ACTION_PLAY_NEXT:
                playNext();
                break;
            case ACTION_FAST_REWIND:
                fastRewind();
                break;
            case ACTION_FAST_FORWARD:
                fastForward();
                break;
            case ACTION_REPEAT:
                cycleNextRepeatMode();
                break;
            case ACTION_SHUFFLE:
                toggleShuffleModeEnabled();
                break;
            case Intent.ACTION_CONFIGURATION_CHANGED:
                if (DEBUG) {
                    Log.d(TAG, "ACTION_CONFIGURATION_CHANGED received");
                }
                break;
            case DEBUG_ACTION_SEEK_TO:
                if (DEBUG && !exoPlayerIsNull()) {
                    seekTo(intent.getLongExtra("ms", 0L));
                }
                break;
            case DEBUG_ACTION_SEEK_BY:
                if (DEBUG && !exoPlayerIsNull()) {
                    seekBy(intent.getLongExtra("ms", 0L));
                }
                break;
            case DEBUG_ACTION_PLAY:
                if (DEBUG) {
                    play();
                }
                break;
            case DEBUG_ACTION_PAUSE:
                if (DEBUG) {
                    pause();
                }
                break;
            case DEBUG_ACTION_STATE:
                if (DEBUG && !exoPlayerIsNull()) {
                    // Bypass the 1 Hz throttle so the state is logged immediately on request.
                    lastStateProbeMs = 0L;
                    final String stateKey = diskPreloadProgressKey;
                    if (stateKey != null) {
                        logPlaybackStateProbe(stateKey);
                    } else {
                        Log.d(TAG, "State probe - no disk preload key yet (playhead="
                                + formatProbeTime(simpleExoPlayer.getCurrentPosition()) + ")");
                    }
                }
                break;
        }

        UIs.call(playerUi -> playerUi.onBroadcastReceived(intent));
    }

    private void registerBroadcastReceiver() {
        // Try to unregister current first
        unregisterBroadcastReceiver();
        ContextCompat.registerReceiver(context, broadcastReceiver, intentFilter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    private void unregisterBroadcastReceiver() {
        try {
            context.unregisterReceiver(broadcastReceiver);
        } catch (final IllegalArgumentException unregisteredException) {
            Log.w(TAG, "Broadcast receiver already unregistered: "
                    + unregisteredException.getMessage());
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Thumbnail loading
    //////////////////////////////////////////////////////////////////////////*/
    //region Thumbnail loading

    private Target getCurrentThumbnailTarget() {
        // a Picasso target is just a listener for thumbnail loading events
        return new Target() {
            @Override
            public void onBitmapLoaded(final Bitmap bitmap, final Picasso.LoadedFrom from) {
                if (DEBUG) {
                    Log.d(TAG, "Thumbnail - onBitmapLoaded() called with: bitmap = [" + bitmap
                            + " -> " + bitmap.getWidth() + "x" + bitmap.getHeight() + "], from = ["
                            + from + "]");
                }
                // there is a new thumbnail, so e.g. the end screen thumbnail needs to change, too.
                onThumbnailLoaded(bitmap);
            }

            @Override
            public void onBitmapFailed(final Exception e, final Drawable errorDrawable) {
                Log.e(TAG, "Thumbnail - onBitmapFailed() called", e);
                // there is a new thumbnail, so e.g. the end screen thumbnail needs to change, too.
                onThumbnailLoaded(null);
            }

            @Override
            public void onPrepareLoad(final Drawable placeHolderDrawable) {
                if (DEBUG) {
                    Log.d(TAG, "Thumbnail - onPrepareLoad() called");
                }
            }
        };
    }

    private void loadCurrentThumbnail(final List<Image> thumbnails) {
        if (DEBUG) {
            Log.d(TAG, "Thumbnail - loadCurrentThumbnail() called with thumbnails = ["
                    + thumbnails.size() + "]");
        }

        // first cancel any previous loading
        cancelLoadingCurrentThumbnail();

        // Unset currentThumbnail, since it is now outdated. This ensures it is not used in media
        // session metadata while the new thumbnail is being loaded by Picasso.
        onThumbnailLoaded(null);
        if (thumbnails.isEmpty()) {
            return;
        }

        // scale down the notification thumbnail for performance
        PicassoHelper.loadScaledDownThumbnail(context, thumbnails)
                .tag(PICASSO_PLAYER_THUMBNAIL_TAG)
                .into(currentThumbnailTarget);
    }

    private void cancelLoadingCurrentThumbnail() {
        // cancel the Picasso job associated with the player thumbnail, if any
        PicassoHelper.cancelTag(PICASSO_PLAYER_THUMBNAIL_TAG);
    }

    private void onThumbnailLoaded(@Nullable final Bitmap bitmap) {
        // Avoid useless thumbnail updates, if the thumbnail has not actually changed. Based on the
        // thumbnail loading code, this if would be skipped only when both bitmaps are `null`, since
        // onThumbnailLoaded won't be called twice with the same nonnull bitmap by Picasso's target.
        if (currentThumbnail != bitmap) {
            currentThumbnail = bitmap;
            UIs.call(playerUi -> playerUi.onThumbnailLoaded(bitmap));
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback parameters
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback parameters

    public float getPlaybackSpeed() {
        return getPlaybackParameters().speed;
    }

    public void setPlaybackSpeed(final float speed) {
        setPlaybackParameters(speed, getPlaybackPitch(), getPlaybackSkipSilence());
    }

    public float getPlaybackPitch() {
        return getPlaybackParameters().pitch;
    }

    public boolean getPlaybackSkipSilence() {
        return !exoPlayerIsNull() && simpleExoPlayer.getSkipSilenceEnabled();
    }

    public PlaybackParameters getPlaybackParameters() {
        if (exoPlayerIsNull()) {
            return PlaybackParameters.DEFAULT;
        }
        return simpleExoPlayer.getPlaybackParameters();
    }

    /**
     * Sets the playback parameters of the player, and also saves them to shared preferences.
     * Speed and pitch are rounded up to 2 decimal places before being used or saved.
     *
     * @param speed       the playback speed, will be rounded to up to 2 decimal places
     * @param pitch       the playback pitch, will be rounded to up to 2 decimal places
     * @param skipSilence skip silence during playback
     */
    public void setPlaybackParameters(final float speed, final float pitch,
                                      final boolean skipSilence) {
        final float roundedSpeed = Math.round(speed * 100.0f) / 100.0f;
        final float roundedPitch = Math.round(pitch * 100.0f) / 100.0f;

        savePlaybackParametersToPrefs(this, roundedSpeed, roundedPitch, skipSilence);
        simpleExoPlayer.setPlaybackParameters(
                new PlaybackParameters(roundedSpeed, roundedPitch));
        simpleExoPlayer.setSkipSilenceEnabled(skipSilence);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Progress loop and updates
    //////////////////////////////////////////////////////////////////////////*/
    //region Progress loop and updates

    private void onUpdateProgress(final int currentProgress,
                                  final int duration,
                                  final int bufferPercent) {
        if (isPrepared) {
            UIs.call(ui -> ui.onUpdateProgress(currentProgress, duration, bufferPercent));
            notifyProgressUpdateToListeners(currentProgress, duration, bufferPercent);
        }
    }

    public void startProgressLoop() {
        progressUpdateDisposable.set(getProgressUpdateDisposable());
    }

    private void stopProgressLoop() {
        progressUpdateDisposable.set(null);
    }

    public boolean isProgressLoopRunning() {
        return progressUpdateDisposable.get() != null;
    }

    public void triggerProgressUpdate() {
        if (exoPlayerIsNull()) {
            return;
        }

        // The secondary (red) seekbar should reflect everything that can be played back instantly:
        // ExoPlayer's in-memory buffer AND the bytes already prefetched to the on-disk cache. The
        // in-memory buffer is capped by LoadController (~90s ahead), so on a long video only the
        // on-disk progress can grow the bar to 100%.
        int bufferPercent = simpleExoPlayer.getBufferedPercentage();
        final String key = diskPreloadProgressKey;
        if (key != null) {
            final int diskPercent = Math.round(PlayerDataSource.getDiskCacheProgress(key) * 100);
            logPlaybackStateProbe(key);
            if (diskPercent > bufferPercent) {
                bufferPercent = diskPercent;
            }
        }

        onUpdateProgress(Math.max((int) simpleExoPlayer.getCurrentPosition(), 0),
                (int) simpleExoPlayer.getDuration(), bufferPercent);

        // Heartbeat while stuck buffering: if we've been buffering for over 5 seconds and every
        // 5 seconds thereafter, log a probe so a stuck spinner is diagnosable from logcat.
        if (bufferingStartElapsedRealtimeMs != -1L) {
            final long elapsed =
                    SystemClock.elapsedRealtime() - bufferingStartElapsedRealtimeMs;
            if (elapsed >= 5000L && (elapsed / 1000L) % 5L == 0L) {
                logBufferingProbe("stuck buffering " + elapsed + "ms");
            }
        }
    }

    private Disposable getProgressUpdateDisposable() {
        return Observable.interval(PROGRESS_LOOP_INTERVAL_MILLIS, MILLISECONDS,
                        AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> triggerProgressUpdate(),
                        error -> Log.e(TAG, "Progress update failure: ", error));
    }

    /**
     * Emits, at most once per second, a single line summarising the whole cache picture so it can
     * be verified from logcat:
     * <ul>
     *     <li>the current player position (playhead),</li>
     *     <li>the in-memory buffered window (what can be seeked to without any load),</li>
     *     <li>the on-disk cached time ranges (what the prefetch holds).</li>
     * </ul>
     * Watching consecutive lines shows the in-memory window slide forward as playback advances and
     * as samples are read from the on-disk cache (paired with the "Cache probe" lines from
     * CacheFactory, which fire when a load is actually served from disk rather than the network).
     *
     * @param key the cache key of the stream backing the seekbar timeline
     */
    private void logPlaybackStateProbe(@NonNull final String key) {
        final long now = SystemClock.elapsedRealtime();
        if (now - lastStateProbeMs < 1000L) {
            return;
        }
        lastStateProbeMs = now;

        final long durationMs = simpleExoPlayer.getDuration();
        final long playheadMs = Math.max(0, simpleExoPlayer.getCurrentPosition());
        final long memoryEndMs = simpleExoPlayer.getBufferedPosition();
        // ExoPlayer does not expose the start of the retained sample queue; it keeps roughly
        // LoadController.SEEK_RETAIN_MS of back-buffer, so approximate the window start with that.
        final long memoryStartMs = Math.max(0, playheadMs - LoadController.getSeekRetainMs());

        Log.d(TAG, "State probe - playhead=" + formatProbeTime(playheadMs)
                + " | " + exoPlaybackStateName(simpleExoPlayer.getPlaybackState())
                + (simpleExoPlayer.getPlayWhenReady() ? "+playWhenReady" : "+paused")
                + (simpleExoPlayer.isLoading() ? " LOADING" : " idle")
                + " | memory~[" + formatProbeTime(memoryStartMs)
                + ".." + formatProbeTime(memoryEndMs) + "]"
                + " aheadMs=" + (memoryEndMs - playheadMs)
                + " | disk=" + PlayerDataSource.describeDiskCacheRanges(key, durationMs)
                + " | duration=" + formatProbeTime(durationMs));
    }

    private static String exoPlaybackStateName(final int state) {
        switch (state) {
            case com.google.android.exoplayer2.Player.STATE_IDLE: return "IDLE";
            case com.google.android.exoplayer2.Player.STATE_BUFFERING: return "BUFFERING";
            case com.google.android.exoplayer2.Player.STATE_READY: return "READY";
            case com.google.android.exoplayer2.Player.STATE_ENDED: return "ENDED";
            default: return "STATE_" + state;
        }
    }

    private static String formatProbeTime(final long ms) {
        final long totalSec = Math.max(0, ms) / 1000;
        return totalSec / 60 + ":" + String.format(Locale.US, "%02d", totalSec % 60);
    }

    private void updatePlaybackStartTrace(@NonNull final Intent intent) {
        if (!intent.hasExtra(PLAYBACK_START_TRACE_ID)
                || !intent.hasExtra(PLAYBACK_START_TRACE_ELAPSED_REALTIME_MS)) {
            return;
        }

        final long newTraceId = intent.getLongExtra(PLAYBACK_START_TRACE_ID, -1L);
        if (newTraceId != playbackStartTraceId) {
            playbackStartTraceId = newTraceId;
            playbackStartTraceStartMs = intent.getLongExtra(
                    PLAYBACK_START_TRACE_ELAPSED_REALTIME_MS, -1L);
            playbackStartTraceLastMs = playbackStartTraceStartMs;
            playbackStartFirstFrameLogged = false;
        }
    }

    private void logPlaybackStartTrace(@NonNull final String event) {
        if (playbackStartTraceId < 0 || playbackStartTraceStartMs < 0) {
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final long elapsedMs = now - playbackStartTraceStartMs;
        // stepMs = time since the previous PlaybackStartTrace marker, so each player-startup
        // phase's own cost is readable straight from logcat without subtracting timestamps.
        final long stepMs = playbackStartTraceLastMs < 0 ? 0 : now - playbackStartTraceLastMs;
        playbackStartTraceLastMs = now;
        PersistentPlayerLogger.log(context, "PlaybackStartTrace "
                + "id=" + playbackStartTraceId
                + " +" + elapsedMs + "ms"
                + " stepMs=" + stepMs + " "
                + event
                + " playerType=" + playerType
                + " currentState=" + currentState
                + " exoState=" + (exoPlayerIsNull() ? -1 : simpleExoPlayer.getPlaybackState())
                + " playWhenReady=" + getPlayWhenReady()
                + " url=" + getVideoUrl());
    }

    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Playback states
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback states
    @Override
    public void onPlayWhenReadyChanged(final boolean playWhenReady, final int reason) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPlayWhenReadyChanged() called with: "
                    + "playWhenReady = [" + playWhenReady + "], "
                    + "reason = [" + reason + "]");
        }
        PersistentPlayerLogger.log(context, "Player.exoPlayWhenReady "
                + "playWhenReady=" + playWhenReady
                + " reason=" + reason
                + " exoState=" + (exoPlayerIsNull() ? -1 : simpleExoPlayer.getPlaybackState()));
        final int playbackState = exoPlayerIsNull()
                ? com.google.android.exoplayer2.Player.STATE_IDLE
                : simpleExoPlayer.getPlaybackState();
        updatePlaybackState(playWhenReady, playbackState);
    }

    @Override
    public void onPlaybackStateChanged(final int playbackState) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPlaybackStateChanged() called with: "
                    + "playbackState = [" + playbackState + "]");
        }
        PersistentPlayerLogger.log(context, "Player.exoPlaybackState "
                + "playbackState=" + playbackState
                + " playWhenReady=" + getPlayWhenReady()
                + " currentState=" + currentState
                + " isPrepared=" + isPrepared
                + " playQueueNull=" + (playQueue == null));
        updatePlaybackState(getPlayWhenReady(), playbackState);
    }

    private void updatePlaybackState(final boolean playWhenReady, final int playbackState) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - updatePlaybackState() called with: "
                    + "playWhenReady = [" + playWhenReady + "], "
                    + "playbackState = [" + playbackState + "]");
        }

        if (currentState == STATE_PAUSED_SEEK) {
            if (DEBUG) {
                Log.d(TAG, "updatePlaybackState() is currently blocked");
            }
            return;
        }

        switch (playbackState) {
            case com.google.android.exoplayer2.Player.STATE_IDLE: // 1
                resetBufferingLagTrace();
                isPrepared = false;
                break;
            case com.google.android.exoplayer2.Player.STATE_BUFFERING: // 2
                markBufferingStart();
                logPlaybackStartTrace("exo.state.BUFFERING isPrepared=" + isPrepared);
                if (isPrepared) {
                    changeState(STATE_BUFFERING);
                }
                logBufferingProbe("STATE_BUFFERING");
                break;
            case com.google.android.exoplayer2.Player.STATE_READY: //3
                logPlaybackStartTrace("exo.state.READY isPrepared=" + isPrepared
                        + " playWhenReady=" + playWhenReady);
                maybeLogBufferingEnd();
                if (!isPrepared) {
                    isPrepared = true;
                    onPrepared(playWhenReady);
                }
                changeState(playWhenReady ? STATE_PLAYING : STATE_PAUSED);
                break;
            case com.google.android.exoplayer2.Player.STATE_ENDED: // 4
                resetBufferingLagTrace();
                if (shouldRetryLiveEnd()) {
                    final boolean isPremiereBroadcast = currentMetadata != null
                            && currentMetadata.getMaybeStreamInfo()
                                    .map(PlaybackResolver::isYoutubePremiereBroadcast)
                                    .orElse(false);
                    Log.w(TAG, "Live stream ended unexpectedly; reloading from live edge: "
                            + currentMetadata.getStreamUrl()
                            + " (isPremiereBroadcast=" + isPremiereBroadcast + ")");
                    if (playQueue != null) {
                        if (isPremiereBroadcast) {
                            // Resume past the placeholder content we just exhausted; a fresh
                            // extraction below will provide URLs with more recently broadcast
                            // segments, so seeking past the old end avoids replaying it.
                            final long endPositionMs = exoPlayerIsNull()
                                    ? 0L : simpleExoPlayer.getCurrentPosition();
                            setRecovery(playQueue.getIndex(), Math.max(0L, endPositionMs));
                        } else {
                            playQueue.unsetRecovery(playQueue.getIndex());
                        }
                    }
                    if (isPremiereBroadcast && currentMetadata != null) {
                        // Force the StreamInfo cache to drop the stale entry so the reload
                        // fetches fresh stream URLs with the latest broadcast content.
                        InfoCache.getInstance().removeInfo(
                                currentMetadata.getServiceId(),
                                currentMetadata.getStreamUrl(),
                                InfoCache.Type.STREAM);
                    }
                    isPrepared = false;
                    reloadPlayQueueManager();
                    break;
                }
                changeState(STATE_COMPLETED);
                saveStreamProgressStateCompleted();
                isPrepared = false;
                break;
        }
    }

    private void markBufferingStart() {
        if (bufferingStartElapsedRealtimeMs != -1L || exoPlayerIsNull()) {
            return;
        }
        bufferingStartElapsedRealtimeMs = SystemClock.elapsedRealtime();
        bufferingStartPositionMs = (int) simpleExoPlayer.getCurrentPosition();
    }

    private void logBufferingProbe(@NonNull final String tag) {
        if (exoPlayerIsNull()) {
            return;
        }
        final long pos = simpleExoPlayer.getCurrentPosition();
        final long duration = simpleExoPlayer.getDuration();
        final int buffered = simpleExoPlayer.getBufferedPercentage();
        final boolean isLoading = simpleExoPlayer.isLoading();
        final StreamType streamType = currentMetadata == null ? null
                : currentMetadata.getStreamType();
        final String url = currentMetadata == null ? "?" : currentMetadata.getStreamUrl();
        Log.d(TAG, "Lag probe - " + tag + " pos=" + pos + "/" + duration
                + " buffered=" + buffered + "%"
                + " loading=" + isLoading
                + " streamType=" + streamType
                + " url=" + url);
    }

    private void maybeLogBufferingEnd() {
        if (bufferingStartElapsedRealtimeMs == -1L || exoPlayerIsNull()) {
            return;
        }

        final long bufferingDurationMs =
                SystemClock.elapsedRealtime() - bufferingStartElapsedRealtimeMs;
        final int endPositionMs = (int) simpleExoPlayer.getCurrentPosition();
        final int bufferedPercent = simpleExoPlayer.getBufferedPercentage();

        if (bufferingDurationMs >= 1200L) {
            Log.w(TAG, "Lag probe - buffering " + bufferingDurationMs
                    + "ms, pos " + bufferingStartPositionMs + " -> " + endPositionMs
                    + ", buffered=" + bufferedPercent + "%, state=" + currentState
                    + ", url=" + getVideoUrl());
        } else if (DEBUG) {
            Log.d(TAG, "Lag probe - short buffering " + bufferingDurationMs
                    + "ms at pos=" + endPositionMs);
        }

        resetBufferingLagTrace();
    }

    private void resetBufferingLagTrace() {
        bufferingStartElapsedRealtimeMs = -1L;
        bufferingStartPositionMs = C.INDEX_UNSET;
    }

    @NonNull
    private static String classifyDecoder(@NonNull final String decoderName) {
        final String normalized = decoderName.toLowerCase(Locale.US);
        if (normalized.startsWith("c2.android.")
                || normalized.startsWith("omx.google.")) {
            return "software";
        }
        if (normalized.startsWith("c2.exynos.")
                || normalized.startsWith("c2.google.av1.")) {
            return "hardware";
        }
        if (normalized.startsWith("c2.dolby.")) {
            return "vendor-not-hardware";
        }
        return "unknown";
    }

    private void maybeDisableTunnelingForDecoder(@NonNull final String decoderName) {
        if (mediaTunnelingDisabledByDecoderProbe) {
            return;
        }

        final String normalized = decoderName.toLowerCase(Locale.US);
        if (!normalized.contains("exynos")) {
            return;
        }
        if (!trackSelector.getParameters().tunnelingEnabled) {
            return;
        }

        mediaTunnelingDisabledByDecoderProbe = true;
        Log.w(TAG, "Lag probe - disabling media tunneling for decoder " + decoderName
                + " after codec reclaim/freeze symptoms");

        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setTunnelingEnabled(false));

        prefs.edit()
                .putBoolean(context.getString(R.string.disable_media_tunneling_key), true)
                .putInt(context.getString(R.string.disabled_media_tunneling_automatically_key), 1)
                .apply();
    }

    @Override // exoplayer listener
    public void onIsLoadingChanged(final boolean isLoading) {
        if (!isLoading && currentState == STATE_PAUSED && isProgressLoopRunning()) {
            stopProgressLoop();
        } else if (isLoading && !isProgressLoopRunning()) {
            startProgressLoop();
        }
    }

    @Override // own playback listener
    public void onPlaybackBlock() {
        if (exoPlayerIsNull()) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackBlock() called");
        }

        currentItem = null;
        currentMetadata = null;
        simpleExoPlayer.stop();
        isPrepared = false;

        changeState(STATE_BLOCKED);
    }

    @Override // own playback listener
    public void onPlaybackUnblock(final MediaSource mediaSource) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackUnblock() called");
        }

        if (exoPlayerIsNull()) {
            return;
        }
        if (currentState == STATE_BLOCKED) {
            changeState(STATE_BUFFERING);
        }
        logPlaybackStartTrace("onPlaybackUnblock.setMediaSource");
        simpleExoPlayer.setMediaSource(mediaSource, false);
        logPlaybackStartTrace("onPlaybackUnblock.prepare");
        simpleExoPlayer.prepare();
    }

    public void changeState(final int state) {
        if (DEBUG) {
            Log.d(TAG, "changeState() called with: state = [" + state + "]");
        }
        currentState = state;
        switch (state) {
            case STATE_BLOCKED:
                onBlocked();
                break;
            case STATE_PLAYING:
                onPlaying();
                break;
            case STATE_BUFFERING:
                onBuffering();
                break;
            case STATE_PAUSED:
                onPaused();
                break;
            case STATE_PAUSED_SEEK:
                onPausedSeek();
                break;
            case STATE_COMPLETED:
                onCompleted();
                break;
        }
        notifyPlaybackUpdateToListeners();
    }

    private void onPrepared(final boolean playWhenReady) {
        if (DEBUG) {
            Log.d(TAG, "onPrepared() called with: playWhenReady = [" + playWhenReady + "]");
        }

        UIs.call(PlayerUi::onPrepared);

        if (playWhenReady && !isMuted()) {
            audioReactor.requestAudioFocus();
        }
    }

    private void onBlocked() {
        if (DEBUG) {
            Log.d(TAG, "onBlocked() called");
        }
        if (!isProgressLoopRunning()) {
            startProgressLoop();
        }

        UIs.call(PlayerUi::onBlocked);
    }

    private void onPlaying() {
        if (DEBUG) {
            Log.d(TAG, "onPlaying() called");
        }
        if (!isProgressLoopRunning()) {
            startProgressLoop();
        }

        UIs.call(PlayerUi::onPlaying);
    }

    private void onBuffering() {
        if (DEBUG) {
            Log.d(TAG, "onBuffering() called");
        }

        UIs.call(PlayerUi::onBuffering);
    }

    private void onPaused() {
        if (DEBUG) {
            Log.d(TAG, "onPaused() called");
        }

        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }

        UIs.call(PlayerUi::onPaused);
    }

    private void onPausedSeek() {
        if (DEBUG) {
            Log.d(TAG, "onPausedSeek() called");
        }
        UIs.call(PlayerUi::onPausedSeek);
    }

    private void onCompleted() {
        if (DEBUG) {
            Log.d(TAG, "onCompleted() called" + (playQueue == null ? ". playQueue is null" : ""));
        }
        if (playQueue == null) {
            return;
        }

        UIs.call(PlayerUi::onCompleted);

        if (playQueue.getIndex() < playQueue.size() - 1) {
            playQueue.offsetIndex(+1);
        }
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Repeat and shuffle
    //////////////////////////////////////////////////////////////////////////*/
    //region Repeat and shuffle

    @RepeatMode
    public int getRepeatMode() {
        return exoPlayerIsNull() ? REPEAT_MODE_OFF : simpleExoPlayer.getRepeatMode();
    }

    public void cycleNextRepeatMode() {
        if (!exoPlayerIsNull()) {
            @RepeatMode final int repeatMode;
            switch (simpleExoPlayer.getRepeatMode()) {
                case REPEAT_MODE_OFF:
                    repeatMode = REPEAT_MODE_ONE;
                    break;
                case REPEAT_MODE_ONE:
                    repeatMode = REPEAT_MODE_ALL;
                    break;
                case REPEAT_MODE_ALL:
                default:
                    repeatMode = REPEAT_MODE_OFF;
                    break;
            }
            simpleExoPlayer.setRepeatMode(repeatMode);
        }
    }

    @Override
    public void onRepeatModeChanged(@RepeatMode final int repeatMode) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onRepeatModeChanged() called with: "
                    + "repeatMode = [" + repeatMode + "]");
        }
        UIs.call(playerUi -> playerUi.onRepeatModeChanged(repeatMode));
        notifyPlaybackUpdateToListeners();
    }

    @Override
    public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onShuffleModeEnabledChanged() called with: "
                    + "mode = [" + shuffleModeEnabled + "]");
        }

        if (playQueue != null) {
            if (shuffleModeEnabled) {
                playQueue.shuffle();
            } else {
                playQueue.unshuffle();
            }
        }

        UIs.call(playerUi -> playerUi.onShuffleModeEnabledChanged(shuffleModeEnabled));
        notifyPlaybackUpdateToListeners();
    }

    public void toggleShuffleModeEnabled() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.setShuffleModeEnabled(!simpleExoPlayer.getShuffleModeEnabled());
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Volume helpers
    //////////////////////////////////////////////////////////////////////////*/
    //region Volume helpers

    private void setBaseVolume(final float volume) {
        baseVolume = MathUtils.clamp(volume, 0.0f, 1.0f);
        applyVolume();
    }

    private void applyVolume() {
        if (exoPlayerIsNull()) {
            return;
        }
        simpleExoPlayer.setVolume(baseVolume * audioPreampGain);
    }

    private void updateAudioPreampFromPrefs() {
        final int prefValue = prefs.getInt(
                context.getString(R.string.audio_preamp_key),
                AUDIO_PREAMP_DEFAULT_DB);
        final int clamped = MathUtils.clamp(prefValue, AUDIO_PREAMP_MIN_DB, AUDIO_PREAMP_MAX_DB);
        audioPreampGain = dbToGain(clamped);
        applyVolume();
    }

    private static float dbToGain(final int db) {
        return (float) Math.pow(10.0, db / 20.0);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Mute / Unmute
    //////////////////////////////////////////////////////////////////////////*/
    //region Mute / Unmute

    public void toggleMute() {
        final boolean wasMuted = isMuted();
        setBaseVolume(wasMuted ? 1.0f : 0.0f);
        if (wasMuted) {
            audioReactor.requestAudioFocus();
        } else {
            audioReactor.abandonAudioFocus();
        }
        UIs.call(playerUi -> playerUi.onMuteUnmuteChanged(!wasMuted));
        notifyPlaybackUpdateToListeners();
    }

    public boolean isMuted() {
        return baseVolume == 0.0f;
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // ExoPlayer listeners (that didn't fit in other categories)
    //////////////////////////////////////////////////////////////////////////*/
    //region ExoPlayer listeners (that didn't fit in other categories)

    /**
     * <p>Listens for event or state changes on ExoPlayer. When any event happens, we check for
     * changes in the currently-playing metadata and update the encapsulating
     * {@link Player}. Downstream listeners are also informed.</p>
     *
     * <p>When the renewed metadata contains any error, it is reported as a notification.
     * This is done because not all source resolution errors are {@link PlaybackException}, which
     * are also captured by {@link ExoPlayer} and stops the playback.</p>
     *
     * @param player The {@link com.google.android.exoplayer2.Player} whose state changed.
     * @param events The {@link com.google.android.exoplayer2.Player.Events} that has triggered
     *               the player state changes.
     **/
    @Override
    public void onEvents(@NonNull final com.google.android.exoplayer2.Player player,
                         @NonNull final com.google.android.exoplayer2.Player.Events events) {
        Listener.super.onEvents(player, events);
        MediaItemTag.from(player.getCurrentMediaItem()).ifPresent(tag -> {
            if (tag == currentMetadata) {
                return; // we still have the same metadata, no need to do anything
            }
            final StreamInfo previousInfo = Optional.ofNullable(currentMetadata)
                    .flatMap(MediaItemTag::getMaybeStreamInfo).orElse(null);
            final MediaItemTag.AudioTrack previousAudioTrack =
                    Optional.ofNullable(currentMetadata)
                            .flatMap(MediaItemTag::getMaybeAudioTrack).orElse(null);
            currentMetadata = tag;
            logPlaybackStartTrace("metadata.changed title=" + currentMetadata.getTitle());

            if (!currentMetadata.getErrors().isEmpty()) {
                // new errors might have been added even if previousInfo == tag.getMaybeStreamInfo()
                final ErrorInfo errorInfo = new ErrorInfo(
                        currentMetadata.getErrors(),
                        UserAction.PLAY_STREAM,
                        "Loading failed for [" + currentMetadata.getTitle()
                                + "]: " + currentMetadata.getStreamUrl(),
                        currentMetadata.getServiceId(),
                        currentMetadata.getStreamUrl());
                ErrorUtil.createNotification(context, errorInfo);
            }

            currentMetadata.getMaybeStreamInfo().ifPresent(info -> {
                if (DEBUG) {
                    Log.d(TAG, "ExoPlayer - onEvents() update stream info: " + info.getName());
                }
                if (previousInfo == null || !previousInfo.getUrl().equals(info.getUrl())) {
                    // only update with the new stream info if it has actually changed
                    updateMetadataWith(info);
                    startDiskCachePreload(currentMetadata);
                } else if (previousAudioTrack == null
                        || tag.getMaybeAudioTrack()
                        .map(t -> t.getSelectedAudioStreamIndex()
                                != previousAudioTrack.getSelectedAudioStreamIndex())
                        .orElse(false)) {
                    notifyAudioTrackUpdateToListeners();
                    startDiskCachePreload(currentMetadata);
                }
            });
        });
    }

    private void startDiskCachePreload(@NonNull final MediaItemTag tag) {
        diskCachePreloadDisposable.set(null);

        if (StreamTypeUtil.isLiveStream(tag.getStreamType())) {
            return;
        }

        final Optional<StreamInfo> maybeInfo = tag.getMaybeStreamInfo();
        if (maybeInfo.isEmpty()) {
            return;
        }

        final StreamInfo info = maybeInfo.get();
        final List<Stream> streams = getStreamsToDiskPreload(tag);
        if (streams.isEmpty()) {
            return;
        }

        // The first stream (video when present, otherwise audio) drives the seekbar timeline, so
        // its on-disk prefetch progress is what we surface as the secondary (red) seekbar. Use the
        // same stable key playback uses, so the progress reflects the shared cache entry.
        diskPreloadProgressKey = PlayerDataSource.diskCacheKeyOf(info, streams.get(0));

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<CacheWriter> activeWriter = new AtomicReference<>();
        final Disposable disposable = Completable.fromAction(() -> {
                    for (final Stream stream : streams) {
                        if (cancelled.get()) {
                            return;
                        }

                        final CacheWriter writer = dataSource.createDiskCacheWriter(info, stream);
                        if (writer == null) {
                            continue;
                        }

                        activeWriter.set(writer);
                        Log.d(TAG, "Disk preload - start stream=" + stream.getClass()
                                .getSimpleName() + " title=" + info.getName());
                        writer.cache();
                        activeWriter.compareAndSet(writer, null);
                    }
                })
                .subscribeOn(Schedulers.io())
                .doOnDispose(() -> {
                    cancelled.set(true);
                    final CacheWriter writer = activeWriter.getAndSet(null);
                    if (writer != null) {
                        writer.cancel();
                    }
                })
                .subscribe(
                        () -> Log.d(TAG, "Disk preload - complete title=" + info.getName()),
                        error -> {
                            if (!cancelled.get()) {
                                Log.w(TAG, "Disk preload - failed title=" + info.getName(),
                                        error);
                            }
                        });
        diskCachePreloadDisposable.set(disposable);
    }

    @NonNull
    private List<Stream> getStreamsToDiskPreload(@NonNull final MediaItemTag tag) {
        final List<Stream> streams = new ArrayList<>(2);
        final VideoStream video = tag.getMaybeQuality()
                .map(MediaItemTag.Quality::getSelectedVideoStream)
                .orElse(null);
        final AudioStream audio = tag.getMaybeAudioTrack()
                .map(MediaItemTag.AudioTrack::getSelectedAudioStream)
                .orElse(null);

        if (video != null) {
            streams.add(video);
        }

        final boolean audioIsUsedForPlayback = video == null
                || video.isVideoOnly()
                || videoResolver.getStreamSourceType()
                .map(type -> type == SourceType.VIDEO_WITH_SEPARATED_AUDIO)
                .orElse(false);
        if (audio != null && audioIsUsedForPlayback) {
            streams.add(audio);
        }

        return streams;
    }

    @Override
    public void onTracksChanged(@NonNull final Tracks tracks) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onTracksChanged(), "
                    + "track group size = " + tracks.getGroups().size());
        }
        UIs.call(playerUi -> playerUi.onTextTracksChanged(tracks));
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull final PlaybackParameters playbackParameters) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - playbackParameters(), speed = [" + playbackParameters.speed
                    + "], pitch = [" + playbackParameters.pitch + "]");
        }
        UIs.call(playerUi -> playerUi.onPlaybackParametersChanged(playbackParameters));
    }

    @Override
    public void onPositionDiscontinuity(@NonNull final PositionInfo oldPosition,
                                        @NonNull final PositionInfo newPosition,
                                        @DiscontinuityReason final int discontinuityReason) {
        if (DEBUG) {
            Log.d(TAG, "ExoPlayer - onPositionDiscontinuity() called with "
                    + "oldPositionIndex = [" + oldPosition.mediaItemIndex + "], "
                    + "oldPositionMs = [" + oldPosition.positionMs + "], "
                    + "newPositionIndex = [" + newPosition.mediaItemIndex + "], "
                    + "newPositionMs = [" + newPosition.positionMs + "], "
                    + "discontinuityReason = [" + discontinuityReason + "]");
        }
        if (playQueue == null) {
            return;
        }

        // Refresh the playback if there is a transition to the next video
        final int newIndex = newPosition.mediaItemIndex;
        switch (discontinuityReason) {
            case DISCONTINUITY_REASON_AUTO_TRANSITION:
            case DISCONTINUITY_REASON_REMOVE:
                // When player is in single repeat mode and a period transition occurs,
                // we need to register a view count here since no metadata has changed
                if (getRepeatMode() == REPEAT_MODE_ONE && newIndex == playQueue.getIndex()) {
                    registerStreamViewed();
                    break;
                }
            case DISCONTINUITY_REASON_SEEK:
                if (DEBUG) {
                    Log.d(TAG, "ExoPlayer - onSeekProcessed() called");
                }
                if (isPrepared) {
                    saveStreamProgressState();
                }
            case DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
            case DISCONTINUITY_REASON_INTERNAL:
                // Player index may be invalid when playback is blocked
                if (getCurrentState() != STATE_BLOCKED && newIndex != playQueue.getIndex()) {
                    saveStreamProgressStateCompleted(); // current stream has ended
                    playQueue.setIndex(newIndex);
                }
                break;
            case DISCONTINUITY_REASON_SKIP:
                break; // only makes Android Studio linter happy, as there are no ads
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        if (!playbackStartFirstFrameLogged) {
            playbackStartFirstFrameLogged = true;
            logPlaybackStartTrace("firstFrame");
        }
        UIs.call(PlayerUi::onRenderedFirstFrame);
    }

    @Override
    public void onCues(@NonNull final CueGroup cueGroup) {
        UIs.call(playerUi -> playerUi.onCues(cueGroup.cues));
    }

    /**
     * To be called when the {@code PlaybackPreparer} set in the {@link MediaSessionConnector}
     * receives an {@code onPrepare()} call. This function allows restoring the default behavior
     * that would happen if there was no playback preparer set, i.e. to just call
     * {@code player.prepare()}. You can find the default behavior in `onPlay()` inside the
     * {@link MediaSessionConnector} file.
     */
    public void onPrepare() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.prepare();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Errors
    //////////////////////////////////////////////////////////////////////////*/
    //region Errors

    /**
     * Process exceptions produced by {@link com.google.android.exoplayer2.ExoPlayer ExoPlayer}.
     * <p>There are multiple types of errors:</p>
     * <ul>
     * <li>{@link PlaybackException#ERROR_CODE_BEHIND_LIVE_WINDOW BEHIND_LIVE_WINDOW}:
     * If the playback on livestreams are lagged too far behind the current playable
     * window. Then we seek to the latest timestamp and restart the playback.
     * This error is <b>catchable</b>.
     * </li>
     * <li>From {@link PlaybackException#ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE BAD_IO} to
     * {@link PlaybackException#ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED UNSUPPORTED_FORMATS}:
     * If the stream source is validated by the extractor but not recognized by the player,
     * then we can try to recover playback by signalling an error on the {@link PlayQueue}.</li>
     * <li>For {@link PlaybackException#ERROR_CODE_TIMEOUT PLAYER_TIMEOUT},
     * {@link PlaybackException#ERROR_CODE_IO_UNSPECIFIED MEDIA_SOURCE_RESOLVER_TIMEOUT} and
     * {@link PlaybackException#ERROR_CODE_IO_NETWORK_CONNECTION_FAILED NO_NETWORK}:
     * We can keep set the recovery record and keep to player at the current state until
     * it is ready to play by restarting the {@link MediaSourceManager}.</li>
     * <li>On any ExoPlayer specific issue internal to its device interaction, such as
     * {@link PlaybackException#ERROR_CODE_DECODER_INIT_FAILED DECODER_ERROR}:
     * We terminate the playback.</li>
     * <li>For any other unspecified issue internal: We set a recovery and try to restart
     * the playback.</li>
     * For any error above that is <b>not</b> explicitly <b>catchable</b>, the player will
     * create a notification so users are aware.
     * </ul>
     *
     * @see com.google.android.exoplayer2.Player.Listener#onPlayerError(PlaybackException)
     */
    // Any error code not explicitly covered here are either unrelated to NewPipe use case
    // (e.g. DRM) or not recoverable (e.g. Decoder error). In both cases, the player should
    // shutdown.
    @SuppressWarnings("SwitchIntDef")
    @Override
    public void onPlayerError(@NonNull final PlaybackException error) {
        Log.e(TAG, "ExoPlayer - onPlayerError() called with:", error);
        PersistentPlayerLogger.log(context, "Player.onPlayerError "
                + "errorCode=" + error.errorCode
                + " errorCodeName=" + error.getErrorCodeName()
                + " message=" + error.getMessage()
                + " cause=" + (error.getCause() == null
                ? "null" : error.getCause().getClass().getSimpleName()));

        // Persist error info to file for offline debugging
        final long positionMs = exoPlayerIsNull() ? -1
                : simpleExoPlayer.getCurrentPosition();
        final long durationMs = exoPlayerIsNull() ? -1
                : simpleExoPlayer.getDuration();
        final String streamUrl = currentItem != null ? currentItem.getUrl() : "null";
        final String streamTitle = currentItem != null ? currentItem.getTitle() : "null";
        DebugFileLog.log(TAG, "onPlayerError"
                + " | errorCode=" + error.getErrorCodeName()
                + " | position=" + positionMs + "ms"
                + " | duration=" + durationMs + "ms"
                + " | stream=" + streamTitle
                + " | url=" + streamUrl, error);

        saveStreamProgressState();
        boolean isCatchableException = false;

        switch (error.errorCode) {
            case ERROR_CODE_BEHIND_LIVE_WINDOW:
                isCatchableException = true;
                simpleExoPlayer.seekToDefaultPosition();
                simpleExoPlayer.prepare();
                // Inform the user that we are reloading the stream by
                // switching to the buffering state
                onBuffering();
                break;
            case ERROR_CODE_IO_BAD_HTTP_STATUS:
                // HTTP 403 etc. often means the stream URL expired;
                // evict cached StreamInfo so reloading fetches fresh URLs
                if (currentItem != null) {
                    InfoCache.getInstance().removeInfo(
                            currentItem.getServiceId(),
                            currentItem.getUrl(),
                            InfoCache.Type.STREAM);
                }
                DebugFileLog.log(TAG, "BAD_HTTP_STATUS -> evicted cache, reloading"
                        + " | position=" + positionMs + "ms");
                isCatchableException = true;
                setRecovery();
                reloadPlayQueueManager();
                break;
            case ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE:
            case ERROR_CODE_IO_FILE_NOT_FOUND:
            case ERROR_CODE_IO_NO_PERMISSION:
            case ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
            case ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE:
            case ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
                // Source errors, signal on playQueue and move on:
                if (!exoPlayerIsNull() && playQueue != null) {
                    playQueue.error();
                }
                break;
            case ERROR_CODE_TIMEOUT:
            case ERROR_CODE_IO_UNSPECIFIED:
            case ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case ERROR_CODE_UNSPECIFIED:
                // Reload playback on unexpected errors:
                setRecovery();
                reloadPlayQueueManager();
                break;
            default:
                // API, remote and renderer errors belong here:
                onPlaybackShutdown();
                break;
        }

        if (!isCatchableException) {
            createErrorNotification(error);
        }

        if (fragmentListener != null) {
            fragmentListener.onPlayerError(error, isCatchableException);
        }
    }

    private void createErrorNotification(@NonNull final PlaybackException error) {
        final ErrorInfo errorInfo;
        if (currentMetadata == null) {
            errorInfo = new ErrorInfo(error, UserAction.PLAY_STREAM,
                    "Player error[type=" + error.getErrorCodeName()
                            + "] occurred, currentMetadata is null");
        } else {
            errorInfo = new ErrorInfo(error, UserAction.PLAY_STREAM,
                    "Player error[type=" + error.getErrorCodeName()
                            + "] occurred while playing " + currentMetadata.getStreamUrl(),
                    currentMetadata.getServiceId(), currentMetadata.getStreamUrl());
        }
        ErrorUtil.createNotification(context, errorInfo);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Playback position and seek
    //////////////////////////////////////////////////////////////////////////*/
    //region Playback position and seek

    @Override // own playback listener (this is a getter)
    public boolean isApproachingPlaybackEdge(final long timeToEndMillis) {
        // If live, then not near playback edge
        // If not playing, then not approaching playback edge
        if (exoPlayerIsNull() || isLive() || !isPlaying()) {
            return false;
        }

        final long currentPositionMillis = simpleExoPlayer.getCurrentPosition();
        final long currentDurationMillis = simpleExoPlayer.getDuration();
        return currentDurationMillis - currentPositionMillis < timeToEndMillis;
    }

    /**
     * Checks if the current playback is a livestream AND is playing at or beyond the live edge.
     *
     * @return whether the livestream is playing at or beyond the edge
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isLiveEdge() {
        if (exoPlayerIsNull() || !isLive()) {
            return false;
        }

        final Timeline currentTimeline = simpleExoPlayer.getCurrentTimeline();
        final int currentWindowIndex = simpleExoPlayer.getCurrentMediaItemIndex();
        if (currentTimeline.isEmpty() || currentWindowIndex < 0
                || currentWindowIndex >= currentTimeline.getWindowCount()) {
            return false;
        }

        final Timeline.Window timelineWindow = new Timeline.Window();
        currentTimeline.getWindow(currentWindowIndex, timelineWindow);
        return timelineWindow.getDefaultPositionMs() <= simpleExoPlayer.getCurrentPosition();
    }

    @Override // own playback listener
    public void onPlaybackSynchronize(@NonNull final PlayQueueItem item, final boolean wasBlocked) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onPlaybackSynchronize(was blocked: " + wasBlocked
                    + ") called with item=[" + item.getTitle() + "], url=[" + item.getUrl() + "]");
        }
        if (exoPlayerIsNull() || playQueue == null || currentItem == item) {
            return; // nothing to synchronize
        }

        final int playQueueIndex = playQueue.indexOf(item);
        final int playlistIndex = simpleExoPlayer.getCurrentMediaItemIndex();
        final int playlistSize = simpleExoPlayer.getCurrentTimeline().getWindowCount();
        final boolean removeThumbnailBeforeSync = currentItem == null
                || currentItem.getServiceId() != item.getServiceId()
                || !currentItem.getUrl().equals(item.getUrl());

        currentItem = item;

        if (playQueueIndex != playQueue.getIndex()) {
            // wrong window (this should be impossible, as this method is called with
            // `item=playQueue.getItem()`, so the index of that item must be equal to `getIndex()`)
            Log.e(TAG, "Playback - Play Queue may be not in sync: item index=["
                    + playQueueIndex + "], " + "queue index=[" + playQueue.getIndex() + "]");

        } else if ((playlistSize > 0 && playQueueIndex >= playlistSize) || playQueueIndex < 0) {
            // the queue and the player's timeline are not in sync, since the play queue index
            // points outside of the timeline
            Log.e(TAG, "Playback - Trying to seek to invalid index=[" + playQueueIndex
                    + "] with playlist length=[" + playlistSize + "]");

        } else if (wasBlocked || playlistIndex != playQueueIndex || !isPlaying()) {
            // either the player needs to be unblocked, or the play queue index has just been
            // changed and needs to be synchronized, or the player is not playing
            if (DEBUG) {
                Log.d(TAG, "Playback - Rewinding to correct index=[" + playQueueIndex + "], "
                        + "from=[" + playlistIndex + "], size=[" + playlistSize + "].");
            }

            if (removeThumbnailBeforeSync) {
                // unset the current (now outdated) thumbnail to ensure it is not used during sync
                onThumbnailLoaded(null);
            }

            // sync the player index with the queue index, and seek to the correct position
            if (item.getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET) {
                simpleExoPlayer.seekTo(playQueueIndex, item.getRecoveryPosition());
                playQueue.unsetRecovery(playQueueIndex);
            } else {
                simpleExoPlayer.seekToDefaultPosition(playQueueIndex);
            }
        }
    }

    public void seekTo(final long positionMillis) {
        if (DEBUG) {
            Log.d(TAG, "seekTo() called with: position = [" + positionMillis + "]");
        }
        // Precise seeks (e.g. dragging the seekbar) honour the user's configured seek mode.
        seekToInternal(positionMillis, PlayerHelper.getSeekParameters(context));
    }

    private void seekBy(final long offsetMillis) {
        if (DEBUG) {
            Log.d(TAG, "seekBy() called with: offsetMillis = [" + offsetMillis + "]");
        }
        if (exoPlayerIsNull()) {
            return;
        }
        // Double-tap / fast-forward-rewind: snap to the nearest keyframe instead of the exact
        // frame. An EXACT seek has to decode every frame from the previous keyframe up to the
        // target before it can render; on this device the hardware AVC decoder is filtered out
        // (Tensor G4 workaround) so that decode runs in software and costs ~1-3s even when the
        // target is already buffered. CLOSEST_SYNC jumps straight to a keyframe, so it is
        // effectively instant for in-buffer skips.
        seekToInternal(simpleExoPlayer.getCurrentPosition() + offsetMillis,
                SeekParameters.CLOSEST_SYNC);
    }

    private void seekToInternal(final long positionMillis, final SeekParameters seekParameters) {
        if (!exoPlayerIsNull()) {
            // prevent invalid positions when fast-forwarding/-rewinding
            final long target = MathUtils.clamp(positionMillis, 0, simpleExoPlayer.getDuration());

            // Seek probe: record where we are relative to the in-memory buffer and on-disk cache so
            // a subsequent load (or absence of one) tells us whether the seek target was already
            // resident in memory/disk or required fetching. The matching "first frame" log below
            // measures the perceived latency.
            seekProbeStartMs = SystemClock.elapsedRealtime();
            final long bufferedAheadMs = simpleExoPlayer.getBufferedPosition()
                    - simpleExoPlayer.getCurrentPosition();
            final String key = diskPreloadProgressKey;
            final int diskPercent = key == null ? -1
                    : Math.round(PlayerDataSource.getDiskCacheProgress(key) * 100);
            Log.d(TAG, "Seek probe - seekTo target=" + target + "ms"
                    + " from=" + simpleExoPlayer.getCurrentPosition() + "ms"
                    + " offset=" + (target - simpleExoPlayer.getCurrentPosition()) + "ms"
                    + " inMemoryBufferedAhead=" + bufferedAheadMs + "ms"
                    + " inMemoryBufferedPos=" + simpleExoPlayer.getBufferedPosition() + "ms"
                    + " diskCached=" + diskPercent + "%"
                    + " seekParams=" + seekParameters);

            simpleExoPlayer.setSeekParameters(seekParameters);
            simpleExoPlayer.seekTo(target);
        }
    }

    public void seekToDefault() {
        if (!exoPlayerIsNull()) {
            simpleExoPlayer.seekToDefaultPosition();
        }
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Player actions (play, pause, previous, fast-forward, ...)
    //////////////////////////////////////////////////////////////////////////*/
    //region Player actions (play, pause, previous, fast-forward, ...)

    public void play() {
        if (DEBUG) {
            Log.d(TAG, "play() called");
        }
        if (audioReactor == null || playQueue == null || exoPlayerIsNull()) {
            return;
        }

        if (!isMuted()) {
            audioReactor.requestAudioFocus();
        }

        if (currentState == STATE_COMPLETED) {
            if (playQueue.getIndex() == 0) {
                seekToDefault();
            } else {
                playQueue.setIndex(0);
            }
        }

        simpleExoPlayer.play();
        saveStreamProgressState();
    }

    public void pause() {
        if (DEBUG) {
            Log.d(TAG, "pause() called");
        }
        if (audioReactor == null || exoPlayerIsNull()) {
            return;
        }

        audioReactor.abandonAudioFocus();
        simpleExoPlayer.pause();
        saveStreamProgressState();
    }

    public void playPause() {
        if (DEBUG) {
            Log.d(TAG, "onPlayPause() called");
        }

        if (getPlayWhenReady()
                // When state is completed (replay button is shown) then (re)play and do not pause
                && currentState != STATE_COMPLETED) {
            pause();
        } else {
            play();
        }
    }

    public void playPrevious() {
        if (DEBUG) {
            Log.d(TAG, "onPlayPrevious() called");
        }
        if (exoPlayerIsNull() || playQueue == null) {
            return;
        }

        /* If current playback has run for PLAY_PREV_ACTIVATION_LIMIT_MILLIS milliseconds,
         * restart current track. Also restart the track if the current track
         * is the first in a queue.*/
        if (simpleExoPlayer.getCurrentPosition() > PLAY_PREV_ACTIVATION_LIMIT_MILLIS
                || playQueue.getIndex() == 0) {
            seekToDefault();
            playQueue.offsetIndex(0);
        } else {
            saveStreamProgressState();
            playQueue.offsetIndex(-1);
        }
        triggerProgressUpdate();
    }

    public void playNext() {
        if (DEBUG) {
            Log.d(TAG, "onPlayNext() called");
        }
        if (playQueue == null) {
            return;
        }

        saveStreamProgressState();
        playQueue.offsetIndex(+1);
        triggerProgressUpdate();
    }

    public void fastForward() {
        if (DEBUG) {
            Log.d(TAG, "fastRewind() called");
        }
        seekBy(retrieveSeekDurationFromPreferences(this));
        triggerProgressUpdate();
    }

    public void fastRewind() {
        if (DEBUG) {
            Log.d(TAG, "fastRewind() called");
        }
        seekBy(-retrieveSeekDurationFromPreferences(this));
        triggerProgressUpdate();
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // StreamInfo history: views and progress
    //////////////////////////////////////////////////////////////////////////*/
    //region StreamInfo history: views and progress

    private void registerStreamViewed() {
        getCurrentStreamInfo().ifPresent(info -> databaseUpdateDisposable
                .add(recordManager.onViewed(info).onErrorComplete().subscribe()));
    }

    private void saveStreamProgressState(final long progressMillis) {
        getCurrentStreamInfo().ifPresent(info -> {
            if (!prefs.getBoolean(context.getString(R.string.enable_watch_history_key), true)) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "saveStreamProgressState() called with: progressMillis=" + progressMillis
                        + ", currentMetadata=[" + info.getName() + "]");
            }

            databaseUpdateDisposable.add(recordManager.saveStreamState(info, progressMillis)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnError(e -> {
                        if (DEBUG) {
                            e.printStackTrace();
                        }
                    })
                    .onErrorComplete()
                    .subscribe());
        });
    }

    public void saveStreamProgressState() {
        if (exoPlayerIsNull() || currentMetadata == null || playQueue == null
                || playQueue.getIndex() != simpleExoPlayer.getCurrentMediaItemIndex()) {
            // Make sure play queue and current window index are equal, to prevent saving state for
            // the wrong stream on discontinuity (e.g. when the stream just changed but the
            // playQueue index and currentMetadata still haven't updated)
            return;
        }
        // Save current position. It will help to restore this position once a user
        // wants to play prev or next stream from the queue
        playQueue.setRecovery(playQueue.getIndex(), simpleExoPlayer.getContentPosition());
        saveStreamProgressState(simpleExoPlayer.getCurrentPosition());
    }

    public void saveStreamProgressStateCompleted() {
        // current stream has ended, so the progress is its duration (+1 to overcome rounding)
        getCurrentStreamInfo().ifPresent(info ->
                saveStreamProgressState((info.getDuration() + 1) * 1000));
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Metadata
    //////////////////////////////////////////////////////////////////////////*/
    //region Metadata

    private void updateMetadataWith(@NonNull final StreamInfo info) {
        if (DEBUG) {
            Log.d(TAG, "Playback - onMetadataChanged() called, playing: " + info.getName());
        }
        if (exoPlayerIsNull()) {
            return;
        }

        maybeAutoQueueNextStream(info);

        loadCurrentThumbnail(info.getThumbnails());
        registerStreamViewed();

        notifyMetadataUpdateToListeners();
        notifyAudioTrackUpdateToListeners();
        UIs.call(playerUi -> playerUi.onMetadataChanged(info));
    }

    @NonNull
    public String getVideoUrl() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getStreamUrl();
    }

    @NonNull
    public String getVideoUrlAtCurrentTime() {
        final long timeSeconds = simpleExoPlayer.getCurrentPosition() / 1000;
        String videoUrl = getVideoUrl();
        if (!isLive() && timeSeconds >= 0 && currentMetadata != null
                && currentMetadata.getServiceId() == YouTube.getServiceId()) {
            // Timestamp doesn't make sense in a live stream so drop it
            videoUrl += ("&t=" + timeSeconds);
        }
        return videoUrl;
    }

    @NonNull
    public String getVideoTitle() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getTitle();
    }

    @NonNull
    public String getUploaderName() {
        return currentMetadata == null
                ? context.getString(R.string.unknown_content)
                : currentMetadata.getUploaderName();
    }

    @Nullable
    public Bitmap getThumbnail() {
        return currentThumbnail;
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Play queue, segments and streams
    //////////////////////////////////////////////////////////////////////////*/
    //region Play queue, segments and streams

    private void maybeAutoQueueNextStream(@NonNull final StreamInfo info) {
        if (playQueue == null || playQueue.getIndex() != playQueue.size() - 1
                || getRepeatMode() != REPEAT_MODE_OFF
                || !PlayerHelper.isAutoQueueEnabled(context)) {
            return;
        }
        // auto queue when starting playback on the last item when not repeating
        final PlayQueue autoQueue = PlayerHelper.autoQueueOf(info,
                playQueue.getStreams());
        if (autoQueue != null) {
            playQueue.append(autoQueue.getStreams());
        }
    }

    public void selectQueueItem(final PlayQueueItem item) {
        if (playQueue == null || exoPlayerIsNull()) {
            return;
        }

        final int index = playQueue.indexOf(item);
        if (index == -1) {
            return;
        }

        if (playQueue.getIndex() == index && simpleExoPlayer.getCurrentMediaItemIndex() == index) {
            seekToDefault();
        } else {
            saveStreamProgressState();
        }
        playQueue.setIndex(index);
    }

    @Override
    public void onPlayQueueEdited() {
        notifyPlaybackUpdateToListeners();
        UIs.call(PlayerUi::onPlayQueueEdited);
    }

    @Override // own playback listener
    @Nullable
    public MediaSource sourceOf(final PlayQueueItem item, final StreamInfo info) {
        if (audioPlayerSelected()) {
            return audioResolver.resolve(info);
        }

        if (isAudioOnly && videoResolver.getStreamSourceType().orElse(
                SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY)
                == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY) {
            // If the current info has only video streams with audio and if the stream is played as
            // audio, we need to use the audio resolver, otherwise the video stream will be played
            // in background.
            return audioResolver.resolve(info);
        }

        // Even if the stream is played in background, we need to use the video resolver if the
        // info played is separated video-only and audio-only streams; otherwise, if the audio
        // resolver was called when the app was in background, the app will only stream audio when
        // the user come back to the app and will never fetch the video stream.
        // Note that the video is not fetched when the app is in background because the video
        // renderer is fully disabled (see useVideoSource method), except for HLS streams
        // (see https://github.com/google/ExoPlayer/issues/9282).
        return videoResolver.resolve(info);
    }

    public void disablePreloadingOfCurrentTrack() {
        loadController.disablePreloadingOfCurrentTrack();
    }

    public Optional<VideoStream> getSelectedVideoStream() {
        return Optional.ofNullable(currentMetadata)
                .flatMap(MediaItemTag::getMaybeQuality)
                .filter(quality -> {
                    final int selectedStreamIndex = quality.getSelectedVideoStreamIndex();
                    return selectedStreamIndex >= 0
                            && selectedStreamIndex < quality.getSortedVideoStreams().size();
                })
                .map(quality -> quality.getSortedVideoStreams()
                        .get(quality.getSelectedVideoStreamIndex()));
    }

    public Optional<AudioStream> getSelectedAudioStream() {
        return Optional.ofNullable(currentMetadata)
                .flatMap(MediaItemTag::getMaybeAudioTrack)
                .map(MediaItemTag.AudioTrack::getSelectedAudioStream);
    }
    //endregion



    /*//////////////////////////////////////////////////////////////////////////
    // Captions (text tracks)
    //////////////////////////////////////////////////////////////////////////*/
    //region Captions (text tracks)

    public int getCaptionRendererIndex() {
        if (exoPlayerIsNull()) {
            return RENDERER_UNAVAILABLE;
        }

        for (int t = 0; t < simpleExoPlayer.getRendererCount(); t++) {
            if (simpleExoPlayer.getRendererType(t) == C.TRACK_TYPE_TEXT) {
                return t;
            }
        }

        return RENDERER_UNAVAILABLE;
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Video size
    //////////////////////////////////////////////////////////////////////////*/
    //region Video size
    @Override // exoplayer listener
    public void onVideoSizeChanged(@NonNull final VideoSize videoSize) {
        if (DEBUG) {
            Log.d(TAG, "onVideoSizeChanged() called with: "
                    + "width / height = [" + videoSize.width + " / " + videoSize.height
                    + " = " + (((float) videoSize.width) / videoSize.height) + "], "
                    + "unappliedRotationDegrees = [" + videoSize.unappliedRotationDegrees + "], "
                    + "pixelWidthHeightRatio = [" + videoSize.pixelWidthHeightRatio + "]");
        }

        UIs.call(playerUi -> playerUi.onVideoSizeChanged(videoSize));
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Activity / fragment binding
    //////////////////////////////////////////////////////////////////////////*/
    //region Activity / fragment binding

    public void setFragmentListener(final PlayerServiceEventListener listener) {
        fragmentListener = listener;
        UIs.call(PlayerUi::onFragmentListenerSet);
        notifyQueueUpdateToListeners();
        notifyMetadataUpdateToListeners();
        notifyPlaybackUpdateToListeners();
        triggerProgressUpdate();
    }

    public void removeFragmentListener(final PlayerServiceEventListener listener) {
        if (fragmentListener == listener) {
            fragmentListener = null;
        }
    }

    void setActivityListener(final PlayerEventListener listener) {
        activityListener = listener;
        // TODO why not queue update?
        notifyMetadataUpdateToListeners();
        notifyPlaybackUpdateToListeners();
        triggerProgressUpdate();
    }

    void removeActivityListener(final PlayerEventListener listener) {
        if (activityListener == listener) {
            activityListener = null;
        }
    }

    void stopActivityBinding() {
        if (fragmentListener != null) {
            fragmentListener.onServiceStopped();
            fragmentListener = null;
        }
        if (activityListener != null) {
            activityListener.onServiceStopped();
            activityListener = null;
        }
    }

    private void notifyQueueUpdateToListeners() {
        if (fragmentListener != null && playQueue != null) {
            fragmentListener.onQueueUpdate(playQueue);
        }
        if (activityListener != null && playQueue != null) {
            activityListener.onQueueUpdate(playQueue);
        }
    }

    private void notifyMetadataUpdateToListeners() {
        getCurrentStreamInfo().ifPresent(info -> {
            if (fragmentListener != null) {
                fragmentListener.onMetadataUpdate(info, playQueue);
            }
            if (activityListener != null) {
                activityListener.onMetadataUpdate(info, playQueue);
            }
        });
    }

    private void notifyPlaybackUpdateToListeners() {
        if (fragmentListener != null && !exoPlayerIsNull() && playQueue != null) {
            fragmentListener.onPlaybackUpdate(currentState, getRepeatMode(),
                    playQueue.isShuffled(), simpleExoPlayer.getPlaybackParameters());
        }
        if (activityListener != null && !exoPlayerIsNull() && playQueue != null) {
            activityListener.onPlaybackUpdate(currentState, getRepeatMode(),
                    playQueue.isShuffled(), getPlaybackParameters());
        }
    }

    private void notifyProgressUpdateToListeners(final int currentProgress,
                                                 final int duration,
                                                 final int bufferPercent) {
        if (fragmentListener != null) {
            fragmentListener.onProgressUpdate(currentProgress, duration, bufferPercent);
        }
        if (activityListener != null) {
            activityListener.onProgressUpdate(currentProgress, duration, bufferPercent);
        }
    }

    private void notifyAudioTrackUpdateToListeners() {
        if (fragmentListener != null) {
            fragmentListener.onAudioTrackUpdate();
        }
        if (activityListener != null) {
            activityListener.onAudioTrackUpdate();
        }
    }

    public void useVideoSource(final boolean videoEnabled) {
        if (playQueue == null || audioPlayerSelected()) {
            return;
        }

        isAudioOnly = !videoEnabled;

        getCurrentStreamInfo().ifPresentOrElse(info -> {
            // In case we don't know the source type, fall back to either video-with-audio, or
            // audio-only source type
            final SourceType sourceType = videoResolver.getStreamSourceType()
                    .orElse(SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY);

            if (playQueueManagerReloadingNeeded(sourceType, info, getVideoRendererIndex())) {
                reloadPlayQueueManager();
            }

            setRecovery();

            // Disable or enable video and subtitles renderers depending of the videoEnabled value
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !videoEnabled)
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled));
        }, () -> {
            /*
            The current metadata may be null sometimes (for e.g. when using an unstable connection
            in livestreams) so we will be not able to execute the block below

            Reload the play queue manager in this case, which is the behavior when we don't know the
            index of the video renderer or playQueueManagerReloadingNeeded returns true
            */
            reloadPlayQueueManager();
            setRecovery();
        });
    }

    /**
     * Return whether the play queue manager needs to be reloaded when switching player type.
     *
     * <p>
     * The play queue manager needs to be reloaded if the video renderer index is not known and if
     * the content is not an audio content, but also if none of the following cases is met:
     *
     * <ul>
     *     <li>the content is an {@link StreamType#AUDIO_STREAM audio stream}, an
     *     {@link StreamType#AUDIO_LIVE_STREAM audio live stream}, or a
     *     {@link StreamType#POST_LIVE_AUDIO_STREAM ended audio live stream};</li>
     *     <li>the content is a {@link StreamType#LIVE_STREAM live stream} and the source type is a
     *     {@link SourceType#LIVE_STREAM live source};</li>
     *     <li>the content's source is {@link SourceType#VIDEO_WITH_SEPARATED_AUDIO a video stream
     *     with a separated audio source} or has no audio-only streams available <b>and</b> is a
     *     {@link StreamType#VIDEO_STREAM video stream}, an
     *     {@link StreamType#POST_LIVE_STREAM ended live stream}, or a
     *     {@link StreamType#LIVE_STREAM live stream}.
     *     </li>
     * </ul>
     * </p>
     *
     * @param sourceType         the {@link SourceType} of the stream
     * @param streamInfo         the {@link StreamInfo} of the stream
     * @param videoRendererIndex the video renderer index of the video source, if that's a video
     *                           source (or {@link #RENDERER_UNAVAILABLE})
     * @return whether the play queue manager needs to be reloaded
     */
    private boolean playQueueManagerReloadingNeeded(final SourceType sourceType,
                                                    @NonNull final StreamInfo streamInfo,
                                                    final int videoRendererIndex) {
        final StreamType streamType = streamInfo.getStreamType();
        final boolean isStreamTypeAudio = StreamTypeUtil.isAudio(streamType);

        if (videoRendererIndex == RENDERER_UNAVAILABLE && !isStreamTypeAudio) {
            return true;
        }

        // The content is an audio stream, an audio live stream, or a live stream with a live
        // source: it's not needed to reload the play queue manager because the stream source will
        // be the same
        if (isStreamTypeAudio || (streamType == StreamType.LIVE_STREAM
                && sourceType == SourceType.LIVE_STREAM)) {
            return false;
        }

        // The content's source is a video with separated audio or a video with audio -> the video
        // and its fetch may be disabled
        // The content's source is a video with embedded audio and the content has no separated
        // audio stream available: it's probably not needed to reload the play queue manager
        // because the stream source will be probably the same as the current played
        if (sourceType == SourceType.VIDEO_WITH_SEPARATED_AUDIO
                || (sourceType == SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
                && isNullOrEmpty(streamInfo.getAudioStreams()))) {
            // It's not needed to reload the play queue manager only if the content's stream type
            // is a video stream, a live stream or an ended live stream
            return !StreamTypeUtil.isVideo(streamType);
        }

        // Other cases: the play queue manager reload is needed
        return true;
    }
    //endregion


    /*//////////////////////////////////////////////////////////////////////////
    // Getters
    //////////////////////////////////////////////////////////////////////////*/
    //region Getters

    public Optional<StreamInfo> getCurrentStreamInfo() {
        return Optional.ofNullable(currentMetadata).flatMap(MediaItemTag::getMaybeStreamInfo);
    }

    public int getCurrentState() {
        return currentState;
    }

    public boolean exoPlayerIsNull() {
        return simpleExoPlayer == null;
    }

    public ExoPlayer getExoPlayer() {
        return simpleExoPlayer;
    }

    public boolean isStopped() {
        return exoPlayerIsNull() || simpleExoPlayer.getPlaybackState() == ExoPlayer.STATE_IDLE;
    }

    public boolean isPlaying() {
        return !exoPlayerIsNull() && simpleExoPlayer.isPlaying();
    }

    public boolean getPlayWhenReady() {
        return !exoPlayerIsNull() && simpleExoPlayer.getPlayWhenReady();
    }

    public boolean isLoading() {
        return !exoPlayerIsNull() && simpleExoPlayer.isLoading();
    }

    private boolean isLive() {
        try {
            return !exoPlayerIsNull() && simpleExoPlayer.isCurrentMediaItemDynamic();
        } catch (final IndexOutOfBoundsException e) {
            // Why would this even happen =(... but lets log it anyway, better safe than sorry
            if (DEBUG) {
                Log.d(TAG, "player.isCurrentWindowDynamic() failed: ", e);
            }
            return false;
        }
    }

    private boolean shouldRetryLiveEnd() {
        if (currentMetadata == null) {
            return false;
        }
        if (currentMetadata.getServiceId() != YouTube.getServiceId()) {
            return false;
        }
        final boolean isLiveStreamType =
                StreamTypeUtil.isLiveStream(currentMetadata.getStreamType());
        // YouTube premieres that have just started broadcasting are extracted as VIDEO_STREAM
        // with a placeholder duration but their segment URLs carry source=yt_premiere_broadcast.
        // Treat that case as live so playback restarts from the live edge instead of stopping
        // at the placeholder duration.
        final boolean isPremiereBroadcast = !isLiveStreamType
                && currentMetadata.getMaybeStreamInfo()
                        .map(PlaybackResolver::isYoutubePremiereBroadcast)
                        .orElse(false);
        if (!isLiveStreamType && !isPremiereBroadcast) {
            return false;
        }
        final String url = currentMetadata.getStreamUrl();
        if (url == null || url.isEmpty()) {
            return false;
        }

        final long now = SystemClock.elapsedRealtime();
        if (!url.equals(liveEndRetryUrl)
                || now - liveEndRetryElapsed > LIVE_END_RETRY_RESET_MILLIS) {
            liveEndRetryUrl = url;
            liveEndRetryCount = 0;
        }

        if (liveEndRetryCount >= LIVE_END_MAX_RETRIES) {
            Log.w(TAG, "Live end retry budget exhausted after " + liveEndRetryCount
                    + " retries for url=" + url
                    + " (isLiveStreamType=" + isLiveStreamType
                    + ", isPremiereBroadcast=" + isPremiereBroadcast + ")");
            return false;
        }

        liveEndRetryCount++;
        liveEndRetryElapsed = now;
        Log.i(TAG, "Live end retry " + liveEndRetryCount + "/" + LIVE_END_MAX_RETRIES
                + " for url=" + url
                + " (isLiveStreamType=" + isLiveStreamType
                + ", isPremiereBroadcast=" + isPremiereBroadcast + ")");
        return true;
    }

    public void setPlaybackQuality(@Nullable final String quality) {
        saveStreamProgressState();
        setRecovery();
        videoResolver.setPlaybackQuality(quality);
        reloadPlayQueueManager();
    }

    public void setAudioTrack(@Nullable final String audioTrackId) {
        saveStreamProgressState();
        setRecovery();
        videoResolver.setAudioTrack(audioTrackId);
        audioResolver.setAudioTrack(audioTrackId);
        reloadPlayQueueManager();
    }


    @NonNull
    public Context getContext() {
        return context;
    }

    @NonNull
    public SharedPreferences getPrefs() {
        return prefs;
    }


    public PlayerType getPlayerType() {
        return playerType;
    }

    public boolean audioPlayerSelected() {
        return playerType == PlayerType.AUDIO;
    }

    public boolean videoPlayerSelected() {
        return playerType == PlayerType.MAIN;
    }

    public boolean popupPlayerSelected() {
        return playerType == PlayerType.POPUP;
    }


    @Nullable
    public PlayQueue getPlayQueue() {
        return playQueue;
    }

    public AudioReactor getAudioReactor() {
        return audioReactor;
    }

    public PlayerService getService() {
        return service;
    }

    public boolean isAudioOnly() {
        return isAudioOnly;
    }

    @NonNull
    public DefaultTrackSelector getTrackSelector() {
        return trackSelector;
    }

    @Nullable
    public MediaItemTag getCurrentMetadata() {
        return currentMetadata;
    }

    @Nullable
    public PlayQueueItem getCurrentItem() {
        return currentItem;
    }

    public Optional<PlayerServiceEventListener> getFragmentListener() {
        return Optional.ofNullable(fragmentListener);
    }

    /**
     * @return the user interfaces connected with the player
     */
    @SuppressWarnings("MethodName") // keep the unusual method name
    public PlayerUiList UIs() {
        return UIs;
    }

    /**
     * Get the video renderer index of the current playing stream.
     * <p>
     * This method returns the video renderer index of the current
     * {@link MappingTrackSelector.MappedTrackInfo} or {@link #RENDERER_UNAVAILABLE} if the current
     * {@link MappingTrackSelector.MappedTrackInfo} is null or if there is no video renderer index.
     *
     * @return the video renderer index or {@link #RENDERER_UNAVAILABLE} if it cannot be get
     */
    private int getVideoRendererIndex() {
        final MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector
                .getCurrentMappedTrackInfo();

        if (mappedTrackInfo == null) {
            return RENDERER_UNAVAILABLE;
        }

        // Check every renderer
        return IntStream.range(0, mappedTrackInfo.getRendererCount())
                // Check the renderer is a video renderer and has at least one track
                .filter(i -> !mappedTrackInfo.getTrackGroups(i).isEmpty()
                        && simpleExoPlayer.getRendererType(i) == C.TRACK_TYPE_VIDEO)
                // Return the first index found (there is at most one renderer per renderer type)
                .findFirst()
                // No video renderer index with at least one track found: return unavailable index
                .orElse(RENDERER_UNAVAILABLE);
    }
    //endregion
}
