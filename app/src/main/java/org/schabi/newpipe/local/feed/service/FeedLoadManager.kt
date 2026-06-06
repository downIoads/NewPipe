package org.schabi.newpipe.local.feed.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.processors.PublishProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.ktx.getStringSafe
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ChannelTabHelper
import org.schabi.newpipe.util.DebugFileLog
import org.schabi.newpipe.util.ExtractorHelper.getChannelInfo
import org.schabi.newpipe.util.ExtractorHelper.getChannelTab
import org.schabi.newpipe.util.ExtractorHelper.getMoreChannelTabItems

class FeedLoadManager(private val context: Context) {

    private val subscriptionManager = SubscriptionManager(context)
    private val feedDatabaseManager = FeedDatabaseManager(context)

    private val notificationUpdater = PublishProcessor.create<String>()
    private val currentProgress = AtomicInteger(-1)
    private val maxProgress = AtomicInteger(-1)
    private val cancelSignal = AtomicBoolean()
    private val feedResultsHolder = FeedResultsHolder()

    /** Wall-clock start of the current refresh, for the overall "REFRESH DONE tookMs" line. */
    private val refreshStartMs = AtomicLong(0L)
    private val subscriptionsToLoad = AtomicInteger(0)

    val notification: Flowable<FeedLoadState> = notificationUpdater.map { description ->
        FeedLoadState(description, maxProgress.get(), currentProgress.get())
    }

    /**
     * Start checking for new streams of a subscription group.
     * @param groupId The ID of the subscription group to load. When using
     * [FeedGroupEntity.GROUP_ALL_ID], all subscriptions are loaded. When using
     * [GROUP_NOTIFICATION_ENABLED], only subscriptions with enabled notifications for new streams
     * are loaded. Using an id of a group created by the user results in that specific group to be
     * loaded.
     * @param ignoreOutdatedThreshold When `false`, only subscriptions which have not been updated
     * within the `feed_update_threshold` are checked for updates. This threshold can be set by
     * the user in the app settings. When `true`, all subscriptions are checked for new streams.
     */
    fun startLoading(
        groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        ignoreOutdatedThreshold: Boolean = false
    ): Single<List<Notification<FeedUpdateInfo>>> {
        val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        // Always use the dedicated feed (RSS) fetch method for "What's new": for YouTube this is a
        // single tiny request per channel returning the 15 newest uploads/livestreams, instead of
        // the heavy multi-tab channel extraction (~2 large ~300 KB browse requests per channel).
        // It is by far the biggest speed win for the refresh. `loadStreams` automatically falls
        // back to the channel-tabs extractor for any service that has no dedicated feed extractor.
        // (We no longer read feed_use_dedicated_fetch_method_key, which could be stale/disabled.)
        val useFeedExtractor = true

        val outdatedThreshold = if (ignoreOutdatedThreshold) {
            OffsetDateTime.now(ZoneOffset.UTC)
        } else {
            val thresholdOutdatedSeconds = defaultSharedPreferences.getStringSafe(
                context.getString(R.string.feed_update_threshold_key),
                context.getString(R.string.feed_update_threshold_default_value)
            ).toInt()
            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(thresholdOutdatedSeconds.toLong())
        }

        /**
         * subscriptions which have not been updated within the feed updated threshold
         */
        val outdatedSubscriptions = when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedDatabaseManager.outdatedSubscriptions(
                outdatedThreshold
            )

            GROUP_NOTIFICATION_ENABLED -> feedDatabaseManager.outdatedSubscriptionsWithNotificationMode(
                outdatedThreshold,
                NotificationMode.ENABLED
            )

            else -> feedDatabaseManager.outdatedSubscriptionsForGroup(groupId, outdatedThreshold)
        }

        return outdatedSubscriptions
            .take(1)
            .doOnNext {
                currentProgress.set(0)
                maxProgress.set(it.size)
                refreshStartMs.set(System.currentTimeMillis())
                subscriptionsToLoad.set(it.size)
                feedDbg(
                    "REFRESH START groupId=$groupId ignoreOutdatedThreshold=$ignoreOutdatedThreshold " +
                        "useFeedExtractor=$useFeedExtractor outdatedSubscriptions=${it.size} " +
                        "parallelExtractions=$PARALLEL_EXTRACTIONS"
                )
                it.forEach { sub -> feedDbg("  to-load: ${label(sub)}") }
            }
            .filter { it.isNotEmpty() }
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext {
                notificationUpdater.onNext("")
                broadcastProgress()
            }
            .observeOn(Schedulers.io())
            .flatMap { Flowable.fromIterable(it) }
            .takeWhile { !cancelSignal.get() }
            // No proactive throttle here: the dedicated feed (RSS) requests are tiny and cheap, so
            // we extract at full parallelism. Rate limiting is handled reactively in
            // loadStreamsWithRetries (a brief backoff only if YouTube actually returns HTTP 429).
            .parallel(PARALLEL_EXTRACTIONS, PARALLEL_EXTRACTIONS * 2)
            .runOn(Schedulers.io(), PARALLEL_EXTRACTIONS * 2)
            .filter { !cancelSignal.get() }
            .map { subscriptionEntity ->
                loadStreamsWithRetries(subscriptionEntity, useFeedExtractor, defaultSharedPreferences)
            }
            .sequential()
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext(NotificationConsumer())
            .observeOn(Schedulers.io())
            .buffer(BUFFER_COUNT_BEFORE_INSERT)
            .doOnNext(DatabaseConsumer())
            .subscribeOn(Schedulers.io())
            .toList()
            .flatMap { x ->
                val notifications = x.flatten()
                // First finish the refresh (postProcessFeed fires SuccessResultEvent and logs
                // "REFRESH DONE", so the feed is shown immediately), THEN enrich missing durations
                // in the background. This keeps the perceived refresh time unchanged while the
                // (RSS) duration-less overlays get filled in a moment later.
                postProcessFeed()
                    .andThen(enrichMissingDurations(notifications))
                    .toSingleDefault(notifications)
            }
    }

    fun cancel() {
        cancelSignal.set(true)
    }

    private fun broadcastProgress() {
        FeedEventManager.postEvent(
            FeedEventManager.Event.ProgressEvent(
                currentProgress.get(),
                maxProgress.get()
            )
        )
    }

    private fun loadStreamsWithRetries(
        subscriptionEntity: SubscriptionEntity,
        useFeedExtractor: Boolean,
        defaultSharedPreferences: SharedPreferences
    ): Notification<FeedUpdateInfo> {
        var notification = loadStreams(
            subscriptionEntity,
            useFeedExtractor,
            defaultSharedPreferences
        )
        var retryCount = 0

        while (shouldRetry(notification) &&
            retryCount < MAX_RETRY_COUNT &&
            !cancelSignal.get()
        ) {
            retryCount++
            // Only stall when YouTube actually rate-limited us (429 -> ReCaptchaException); a brief
            // cooldown then helps the retry succeed. Other errors retry immediately.
            if (isRateLimited(notification)) {
                val backoff = RATE_LIMIT_BACKOFF_MILLIS.random()
                feedDbg(
                    "RATE LIMITED ${label(subscriptionEntity)} -> backoff ${backoff}ms before retry"
                )
                Thread.sleep(backoff)
            }
            feedDbg(
                "RETRY $retryCount/$MAX_RETRY_COUNT ${label(subscriptionEntity)} " +
                    "reason=${retryReason(notification)}"
            )
            notification = loadStreams(
                subscriptionEntity,
                useFeedExtractor,
                defaultSharedPreferences
            )
        }

        if (shouldRetry(notification)) {
            // Still failing after all in-manager retries: this subscription will be marked
            // outdated (last_updated=NULL) and therefore counts towards "Not loaded: N".
            feedDbg(
                "EXHAUSTED retries=$retryCount ${label(subscriptionEntity)} " +
                    "reason=${retryReason(notification)} -> will count as NOT LOADED"
            )
        }

        return notification
    }

    private fun retryReason(notification: Notification<FeedUpdateInfo>): String {
        return when {
            notification.isOnError ->
                "onError ${notification.error?.javaClass?.simpleName}: ${notification.error?.message}"

            notification.value?.errors?.isNotEmpty() == true ->
                "partialErrors=${notification.value!!.errors.size} " +
                    "[${notification.value!!.errors.joinToString { it.javaClass.simpleName }}]"

            else -> "none"
        }
    }

    private fun shouldRetry(notification: Notification<FeedUpdateInfo>): Boolean {
        return notification.isOnError || notification.value?.errors?.isNotEmpty() == true
    }

    /**
     * A YouTube Short, identified by its canonical "/shorts/<id>" URL (the RSS feed returns Shorts
     * with such links, while regular uploads and livestreams use "/watch?v=<id>"). These are
     * excluded from the feed because the user only wants uploaded videos and livestreams.
     */
    private fun isShort(item: StreamInfoItem): Boolean {
        return item.url?.contains("/shorts/") == true
    }

    /**
     * Recover real uploads for a single channel whose entire RSS feed window was Shorts (see the
     * call site). Fetches the channel's Videos tab, which lists only long-form uploads and past
     * livestreams (no Shorts) and, unlike the RSS feed, is paginated. The Videos tab's first page
     * is already included in the channel response, so this is ~1 request. Returns up to
     * [FALLBACK_VIDEO_LIMIT] non-Short videos.
     */
    private fun loadVideosTabFallback(
        subscriptionEntity: SubscriptionEntity
    ): List<StreamInfoItem> {
        val channelInfo = getChannelInfo(
            subscriptionEntity.serviceId,
            subscriptionEntity.url,
            true
        ).blockingGet()

        val videosTab = channelInfo.tabs.firstOrNull {
            it.contentFilters.contains(ChannelTabs.VIDEOS)
        } ?: return emptyList()

        return getChannelTab(subscriptionEntity.serviceId, videosTab, true)
            .blockingGet()
            .relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filterNot { isShort(it) }
            .take(FALLBACK_VIDEO_LIMIT)
    }

    // /////////////////////////////////////////////////////////////////////////
    // Background duration enrichment
    // /////////////////////////////////////////////////////////////////////////

    /**
     * The fast RSS feed path returns no duration (the YouTube feed XML simply does not contain
     * one), so its items show no length overlay on their thumbnail. This runs AFTER the refresh
     * has completed (and the feed is already on screen) and, only for channels that actually have
     * at least one stored item with a missing duration, fetches the channel's Videos tab once to
     * learn the durations and patches them into the DB. When at least one row is patched it posts
     * an [IdleEvent] so the feed re-renders with the overlays.
     *
     * Targeting stored-but-missing (rather than just "new") items also backfills the items that
     * earlier RSS refreshes already saved without a duration. Best-effort: any failure just leaves
     * the next refresh to try again, and once durations are filled in a refresh does no extra
     * network work (the per-channel DB check below short-circuits before any Videos-tab fetch).
     */
    private fun enrichMissingDurations(
        notifications: List<Notification<FeedUpdateInfo>>
    ): Completable = Completable.defer {
        if (cancelSignal.get()) {
            return@defer Completable.complete()
        }

        val candidates = notifications.mapNotNull { it.value }
            .filter { it.streams.isNotEmpty() }
        if (candidates.isEmpty()) {
            return@defer Completable.complete()
        }

        val startMs = System.currentTimeMillis()
        val patched = AtomicInteger(0)
        val channelsFetched = AtomicInteger(0)

        Flowable.fromIterable(candidates)
            .takeWhile { !cancelSignal.get() }
            .parallel(PARALLEL_EXTRACTIONS, PARALLEL_EXTRACTIONS * 2)
            .runOn(Schedulers.io(), PARALLEL_EXTRACTIONS * 2)
            .map { info -> enrichChannelDurations(info, patched, channelsFetched) }
            .sequential()
            .ignoreElements()
            .doOnComplete {
                val tookMs = System.currentTimeMillis() - startMs
                if (channelsFetched.get() > 0 || patched.get() > 0) {
                    feedDbg(
                        "DURATION-ENRICH done channelsFetched=${channelsFetched.get()} " +
                            "patched=${patched.get()} tookMs=$tookMs"
                    )
                }
                if (patched.get() > 0) {
                    // Make the FeedViewModel re-read the DB so the freshly-patched overlays appear.
                    // IdleEvent re-renders without the loadJustCompleted side effects (no snackbar/
                    // auto-retry) that a second SuccessResultEvent would trigger.
                    FeedEventManager.postEvent(FeedEventManager.Event.IdleEvent)
                }
            }
    }

    /**
     * Enrich a single channel's stored, duration-less streams. First checks the DB for which of
     * this channel's current items are missing a duration; only if some are does it fetch the
     * Videos tab once (the items are recent uploads, so they are on its first page) and patch each
     * matching duration in. Swallows all errors so it never fails the already-completed refresh.
     */
    private fun enrichChannelDurations(
        info: FeedUpdateInfo,
        patched: AtomicInteger,
        channelsFetched: AtomicInteger
    ) {
        try {
            val candidateUrls = info.streams.mapNotNull { it.url }.distinct()
            val missingUrls = feedDatabaseManager
                .urlsWithMissingDuration(info.serviceId, candidateUrls)
                .toHashSet()
            if (missingUrls.isEmpty()) {
                return
            }

            channelsFetched.incrementAndGet()
            val durations = fetchVideoDurations(info.serviceId, info.url)
            if (durations.isEmpty()) {
                return
            }

            var localPatched = 0
            for (url in missingUrls) {
                val duration = durations[videoKey(url)] ?: continue
                if (duration > 0 &&
                    feedDatabaseManager.setStreamDurationIfMissing(info.serviceId, url, duration)
                ) {
                    localPatched++
                }
            }
            patched.addAndGet(localPatched)
            feedDbg(
                "DURATION-ENRICH channel name=\"${info.name}\" url=${info.url} " +
                    "missing=${missingUrls.size} patched=$localPatched"
            )
        } catch (e: Throwable) {
            feedDbg("DURATION-ENRICH FAILED name=\"${info.name}\" url=${info.url}", e)
        }
    }

    /**
     * Fetch a channel's Videos-tab items (which carry real durations) and return a
     * [videoKey] -> duration map. ~1 reused browse request per channel.
     */
    private fun fetchVideoDurations(serviceId: Int, channelUrl: String): Map<String, Long> {
        val channelInfo = getChannelInfo(serviceId, channelUrl, true).blockingGet()
        val videosTab = channelInfo.tabs.firstOrNull {
            it.contentFilters.contains(ChannelTabs.VIDEOS)
        } ?: return emptyMap()

        return getChannelTab(serviceId, videosTab, true)
            .blockingGet()
            .relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.duration > 0 && it.url != null }
            .associate { videoKey(it.url!!) to it.duration }
    }

    /**
     * Stable key for matching the same video across the RSS feed and the Videos tab. For YouTube
     * this is the 11-char video id, so extra/ordering-different query params do not break the
     * match; for other services (and unexpected URL shapes) it falls back to the full URL, which
     * is fine because both sides come from the same extractor.
     */
    private fun videoKey(url: String): String {
        return YOUTUBE_VIDEO_ID_REGEX.find(url)?.groupValues?.get(1) ?: url
    }

    /**
     * True if the failure was caused by YouTube rate limiting us (HTTP 429, surfaced as a
     * [ReCaptchaException] by DownloaderImpl), either as the top-level error or as the cause of a
     * wrapping [FeedLoadService.RequestException]. Used to apply a brief reactive backoff.
     */
    private fun isRateLimited(notification: Notification<FeedUpdateInfo>): Boolean {
        fun isRecaptcha(t: Throwable?): Boolean = t is ReCaptchaException || t?.cause is ReCaptchaException
        return isRecaptcha(notification.error) ||
            notification.value?.errors?.any { isRecaptcha(it) } == true
    }

    private fun loadStreams(
        subscriptionEntity: SubscriptionEntity,
        useFeedExtractor: Boolean,
        defaultSharedPreferences: SharedPreferences
    ): Notification<FeedUpdateInfo> {
        var error: Throwable? = null
        val storeOriginalErrorAndRethrow = { e: Throwable ->
            // keep original to prevent blockingGet() from wrapping it into RuntimeException
            error = e
            throw e
        }

        feedDbg("BEGIN ${label(subscriptionEntity)}")
        val startMs = System.currentTimeMillis()
        // Tag every HTTP request issued on this thread so DownloaderImpl can attribute its network
        // time to this subscription in the FeedNet trace (cleared in `finally`).
        DownloaderImpl.setFeedRequestTag("sub#${subscriptionEntity.uid}")

        // Phase timings (ms) for the per-subscription breakdown logged below. They reveal whether a
        // slow subscription is bound by the channel-page fetch, the per-tab fetches, or pagination.
        var feedInfoMs = 0L
        var channelInfoMs = 0L
        var tabsMs = 0L
        var tabsFetched = 0
        var moreItemsMs = 0L

        try {
            // check for and load new streams
            // either by using the dedicated feed method or by getting the channel info
            var originalInfo: Info? = null
            var streams: List<StreamInfoItem>? = null
            val errors = ArrayList<Throwable>()

            var usedFeedExtractor = false
            if (useFeedExtractor) {
                NewPipe.getService(subscriptionEntity.serviceId)
                    .getFeedExtractor(subscriptionEntity.url)
                    ?.also { feedExtractor ->
                        // the user wants to use a feed extractor and there is one, use it
                        val feedStartMs = System.currentTimeMillis()
                        val feedInfo = FeedInfo.getInfo(feedExtractor)
                        feedInfoMs = System.currentTimeMillis() - feedStartMs
                        errors.addAll(feedInfo.errors)
                        originalInfo = feedInfo
                        streams = feedInfo.relatedItems
                        usedFeedExtractor = true
                    }
            }

            if (originalInfo == null) {
                // use the normal channel tabs extractor if either the user wants it, or
                // the current service does not have a dedicated feed extractor

                val channelInfoStartMs = System.currentTimeMillis()
                val channelInfo = getChannelInfo(
                    subscriptionEntity.serviceId,
                    subscriptionEntity.url,
                    true
                )
                    .onErrorReturn(storeOriginalErrorAndRethrow)
                    .blockingGet()
                channelInfoMs = System.currentTimeMillis() - channelInfoStartMs
                errors.addAll(channelInfo.errors)
                originalInfo = channelInfo

                streams = channelInfo.tabs
                    .filter { tab ->
                        ChannelTabHelper.fetchFeedChannelTab(
                            context,
                            defaultSharedPreferences,
                            tab
                        )
                    }
                    .map {
                        val tabStartMs = System.currentTimeMillis()
                        val tabInfo = getChannelTab(subscriptionEntity.serviceId, it, true)
                            .onErrorReturn(storeOriginalErrorAndRethrow)
                            .blockingGet()
                        tabsMs += System.currentTimeMillis() - tabStartMs
                        tabsFetched++
                        Pair(tabInfo, it)
                    }
                    .flatMap { (channelTabInfo, linkHandler) ->
                        errors.addAll(channelTabInfo.errors)
                        if (channelTabInfo.relatedItems.isEmpty() &&
                            channelTabInfo.nextPage != null
                        ) {
                            val moreStartMs = System.currentTimeMillis()
                            val infoItemsPage = getMoreChannelTabItems(
                                subscriptionEntity.serviceId,
                                linkHandler,
                                channelTabInfo.nextPage
                            )
                                .blockingGet()
                            moreItemsMs += System.currentTimeMillis() - moreStartMs

                            errors.addAll(infoItemsPage.errors)
                            return@flatMap infoItemsPage.items
                        } else {
                            return@flatMap channelTabInfo.relatedItems
                        }
                    }
                    .filterIsInstance<StreamInfoItem>()
            }

            // The "What's new" feed should only contain uploaded videos and (active/past)
            // livestreams. YouTube's RSS feed mixes in Shorts as ".../shorts/<id>" links, so drop
            // those. Scheduled/upcoming videos are naturally absent from the RSS feed.
            val shortsDropped = streams?.count { isShort(it) } ?: 0
            if (shortsDropped > 0) {
                streams = streams?.filterNot { isShort(it) }
            }

            // Fallback for the rare case where the channel's whole 15-item RSS window was Shorts
            // (so no actual videos survived the filter): the channel's recent real uploads were
            // pushed out of the feed. For this one channel, fetch the (paginated, Shorts-free)
            // Videos tab to recover up to FALLBACK_VIDEO_LIMIT videos. Costs ~1 request and is rare.
            var fallbackMs = 0L
            var fallbackVideos = -1
            if (usedFeedExtractor && streams.isNullOrEmpty() &&
                shortsDropped >= SHORTS_ONLY_FALLBACK_THRESHOLD
            ) {
                val fallbackStartMs = System.currentTimeMillis()
                try {
                    streams = loadVideosTabFallback(subscriptionEntity)
                    fallbackVideos = streams.size
                    fallbackMs = System.currentTimeMillis() - fallbackStartMs
                    feedDbg(
                        "SHORTS-FALLBACK ${label(subscriptionEntity)} " +
                            "shortsDropped=$shortsDropped recoveredVideos=$fallbackVideos " +
                            "tookMs=$fallbackMs"
                    )
                } catch (e: Throwable) {
                    // Keep the empty RSS result rather than failing the whole subscription; there is
                    // simply nothing new to show for it this refresh.
                    fallbackMs = System.currentTimeMillis() - fallbackStartMs
                    feedDbg(
                        "SHORTS-FALLBACK FAILED ${label(subscriptionEntity)} tookMs=$fallbackMs",
                        e
                    )
                }
            }

            val tookMs = System.currentTimeMillis() - startMs
            val reqs = DownloaderImpl.feedRequestCount()
            // Breakdown appended to OK/PARTIAL so each subscription's slow phase is visible.
            val timing = "reqs=$reqs channelInfoMs=$channelInfoMs tabsMs=$tabsMs " +
                "tabs=$tabsFetched moreMs=$moreItemsMs feedMs=$feedInfoMs shortsDropped=$shortsDropped" +
                if (fallbackVideos >= 0) " fallbackMs=$fallbackMs fallbackVideos=$fallbackVideos" else ""
            if (errors.isEmpty()) {
                feedDbg(
                    "OK ${label(subscriptionEntity)} streams=${streams?.size ?: 0} " +
                        "tookMs=$tookMs $timing"
                )
            } else {
                // Partial success: streams may have been returned, but at least one tab/page
                // failed. Because info.errors is non-empty, DatabaseConsumer will still mark this
                // subscription as outdated, so it counts as NOT LOADED.
                feedDbg(
                    "PARTIAL ${label(subscriptionEntity)} streams=${streams?.size ?: 0} " +
                        "errors=${errors.size} tookMs=$tookMs $timing -> will count as NOT LOADED"
                )
                errors.forEachIndexed { i, t ->
                    feedDbg("  partialError[#${i + 1}] ${label(subscriptionEntity)}", t)
                }
            }

            return Notification.createOnNext(
                FeedUpdateInfo(
                    subscriptionEntity,
                    originalInfo!!,
                    streams!!,
                    errors
                )
            )
        } catch (e: Throwable) {
            val tookMs = System.currentTimeMillis() - startMs
            val reqs = DownloaderImpl.feedRequestCount()
            val request = "${subscriptionEntity.serviceId}:${subscriptionEntity.url}"
            val cause = error ?: e
            feedDbg(
                "FAIL ${label(subscriptionEntity)} tookMs=$tookMs reqs=$reqs " +
                    "channelInfoMs=$channelInfoMs tabsMs=$tabsMs tabs=$tabsFetched " +
                    "moreMs=$moreItemsMs feedMs=$feedInfoMs",
                cause
            )
            val wrapper = FeedLoadService.RequestException(
                subscriptionEntity.uid,
                request,
                // do this to prevent blockingGet() from wrapping into RuntimeException
                cause
            )
            return Notification.createOnError(wrapper)
        } finally {
            DownloaderImpl.clearFeedRequestTag()
        }
    }

    /**
     * Keep the feed and the stream tables small
     * to reduce loading times when trying to display the feed.
     * <br>
     * Remove streams from the feed which are older than [FeedDatabaseManager.FEED_OLDEST_ALLOWED_DATE].
     * Remove streams from the database which are not linked / used by any table.
     */
    private fun postProcessFeed() = Completable.fromRunnable {
        FeedEventManager.postEvent(FeedEventManager.Event.ProgressEvent(R.string.feed_processing_message))
        feedDatabaseManager.removeOrphansOrOlderStreams()

        val start = refreshStartMs.get()
        val totalMs = if (start > 0) System.currentTimeMillis() - start else -1
        val count = subscriptionsToLoad.get()
        val perSub = if (count > 0 && totalMs > 0) totalMs / count else -1
        feedDbg(
            "REFRESH DONE tookMs=$totalMs subscriptions=$count avgMsPerSub=$perSub " +
                "parallelExtractions=$PARALLEL_EXTRACTIONS " +
                "collectedErrors=${feedResultsHolder.itemsErrors.size} " +
                "(see DB markAsOutdated lines above for which subscription(s) are NOT LOADED)"
        )
        FeedEventManager.postEvent(FeedEventManager.Event.SuccessResultEvent(feedResultsHolder.itemsErrors))
    }.doOnSubscribe {
        currentProgress.set(-1)
        maxProgress.set(-1)

        notificationUpdater.onNext(context.getString(R.string.feed_processing_message))
        FeedEventManager.postEvent(FeedEventManager.Event.ProgressEvent(R.string.feed_processing_message))
    }.subscribeOn(Schedulers.io())

    private inner class NotificationConsumer : Consumer<Notification<FeedUpdateInfo>> {
        override fun accept(item: Notification<FeedUpdateInfo>) {
            currentProgress.incrementAndGet()
            notificationUpdater.onNext(item.value?.name.orEmpty())

            broadcastProgress()
        }
    }

    private inner class DatabaseConsumer : Consumer<List<Notification<FeedUpdateInfo>>> {

        override fun accept(list: List<Notification<FeedUpdateInfo>>) {
            feedDatabaseManager.database().runInTransaction {
                for (notification in list) {
                    when {
                        notification.isOnNext -> {
                            val info = notification.value!!

                            notification.value!!.newStreams = filterNewStreams(info.streams)

                            feedDatabaseManager.upsertAll(info.uid, info.streams)
                            subscriptionManager.updateFromInfo(info)

                            if (info.errors.isNotEmpty()) {
                                feedResultsHolder.addErrors(
                                    info.errors.map {
                                        FeedLoadService.RequestException(
                                            info.uid,
                                            "${info.serviceId}:${info.url}",
                                            it
                                        )
                                    }
                                )
                                feedDbg(
                                    "DB markAsOutdated (partial errors) uid=${info.uid} " +
                                        "svc=${info.serviceId} name=\"${info.name}\" url=${info.url} " +
                                        "errors=${info.errors.size} -> NOT LOADED"
                                )
                                feedDatabaseManager.markAsOutdated(info.uid)
                            } else {
                                feedDbg(
                                    "DB persisted OK uid=${info.uid} svc=${info.serviceId} " +
                                        "name=\"${info.name}\" url=${info.url} " +
                                        "newStreams=${info.newStreams.size}"
                                )
                            }
                        }

                        notification.isOnError -> {
                            val error = notification.error
                            feedResultsHolder.addError(error!!)

                            if (error is FeedLoadService.RequestException) {
                                feedDbg(
                                    "DB markAsOutdated (onError) uid=${error.subscriptionId} " +
                                        "request=${error.message} -> NOT LOADED",
                                    error.cause
                                )
                                feedDatabaseManager.markAsOutdated(error.subscriptionId)
                            } else {
                                feedDbg("DB onError (non-RequestException)", error)
                            }
                        }
                    }
                }
            }
        }

        private fun filterNewStreams(list: List<StreamInfoItem>): List<StreamInfoItem> {
            return list.filter {
                !feedDatabaseManager.doesStreamExist(it) &&
                    it.uploadDate != null &&
                    // Streams older than this date are automatically removed from the feed.
                    // Therefore, streams which are not in the database,
                    // but older than this date, are considered old.
                    it.uploadDate!!.offsetDateTime().isAfter(
                        FeedDatabaseManager.FEED_OLDEST_ALLOWED_DATE
                    )
            }
        }
    }

    companion object {

        /**
         * Constant used to check for updates of subscriptions with [NotificationMode.ENABLED].
         */
        const val GROUP_NOTIFICATION_ENABLED = -2L

        /**
         * How many extractions will be running in parallel. The dedicated feed (RSS) fetch is a
         * single tiny request per channel, so we can afford much more parallelism than the old
         * heavy channel-tabs path (which was capped at 3). This is the main lever for refresh speed.
         */
        private const val PARALLEL_EXTRACTIONS = 8

        /**
         * Reactive-only backoff: if (and only if) a subscription fails because YouTube returned a
         * rate-limit (HTTP 429 -> [ReCaptchaException]), wait a brief random delay in this range
         * before retrying, to let the rate limit cool down. There is no longer any proactive stall.
         */
        private val RATE_LIMIT_BACKOFF_MILLIS = (300L..900L)

        /**
         * If the RSS feed returned a full window (15 entries) that were ALL Shorts, the channel's
         * recent real uploads were pushed out of the feed; trigger the Videos-tab fallback. (The
         * YouTube RSS feed always returns at most 15 entries.)
         */
        private const val SHORTS_ONLY_FALLBACK_THRESHOLD = 15

        /**
         * How many videos the Shorts-only fallback recovers from the Videos tab for the affected
         * channel (the Videos tab's first page typically holds ~30 uploads).
         */
        private const val FALLBACK_VIDEO_LIMIT = 30

        /**
         * Extracts the 11-char YouTube video id from a watch URL (`...?v=<id>` / `...&v=<id>`).
         * Used by the background duration enrichment to match RSS items against Videos-tab items.
         */
        private val YOUTUBE_VIDEO_ID_REGEX = Regex("[?&]v=([A-Za-z0-9_-]{11})")

        /**
         * Number of items to buffer to mass-insert in the database.
         */
        private const val BUFFER_COUNT_BEFORE_INSERT = 20

        /**
         * How many times to retry loading a subscription after the first attempt failed.
         */
        private const val MAX_RETRY_COUNT = 2

        /**
         * logcat tag for the per-subscription feed-load trace. Filter with:
         * `adb logcat -s FeedDebug`. Driven programmatically by
         * `scripts/trace_feed_refresh.py` (which sends the
         * `org.schabi.newpipe.debug.REFRESH_FEED` broadcast and parses these lines).
         */
        const val FEED_DEBUG_TAG = "FeedDebug"

        /**
         * Stable, greppable identifier for a subscription in the trace logs. Includes everything
         * needed to tell exactly which subscription/channel a line refers to.
         */
        private fun label(e: SubscriptionEntity) = "svc=${e.serviceId} uid=${e.uid} name=\"${e.name}\" url=${e.url}"

        /**
         * Per-subscription feed-load tracing. Logs both to logcat (tag [FEED_DEBUG_TAG]) and to the
         * persistent debug log file ([DebugFileLog]) so a "Not loaded: N" refresh can be diagnosed
         * either live (logcat) or offline (pulled log file).
         *
         * Per CODEX.md: this was added to find the root cause of the "Not loaded: 1" refresh issue.
         * Keep it; comment out the body (not the call sites) if it ever becomes too noisy.
         */
        fun feedDbg(msg: String, t: Throwable? = null) {
            if (t == null) {
                Log.i(FEED_DEBUG_TAG, msg)
            } else {
                Log.w(FEED_DEBUG_TAG, msg, t)
            }
            DebugFileLog.log(FEED_DEBUG_TAG, msg, t)
        }
    }
}
