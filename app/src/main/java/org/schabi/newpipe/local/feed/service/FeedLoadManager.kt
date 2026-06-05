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
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
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
        val useFeedExtractor = defaultSharedPreferences.getBoolean(
            context.getString(R.string.feed_use_dedicated_fetch_method_key),
            false
        )

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

        // like `currentProgress`, but counts the number of YouTube extractions that have begun, so
        // they can be properly throttled every once in a while (see doOnNext below)
        val youtubeExtractionCount = AtomicInteger()

        return outdatedSubscriptions
            .take(1)
            .doOnNext {
                currentProgress.set(0)
                maxProgress.set(it.size)
                feedDbg(
                    "REFRESH START groupId=$groupId ignoreOutdatedThreshold=$ignoreOutdatedThreshold " +
                        "useFeedExtractor=$useFeedExtractor outdatedSubscriptions=${it.size}"
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
            .doOnNext { subscriptionEntity ->
                // throttle YouTube extractions once every BATCH_SIZE to avoid being rate limited
                if (subscriptionEntity.serviceId == ServiceList.YouTube.serviceId) {
                    val previousCount = youtubeExtractionCount.getAndIncrement()
                    if (previousCount != 0 && previousCount % BATCH_SIZE == 0) {
                        Thread.sleep(DELAY_BETWEEN_BATCHES_MILLIS.random())
                    }
                }
            }
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
            .flatMap { x -> postProcessFeed().toSingleDefault(x.flatten()) }
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

        try {
            // check for and load new streams
            // either by using the dedicated feed method or by getting the channel info
            var originalInfo: Info? = null
            var streams: List<StreamInfoItem>? = null
            val errors = ArrayList<Throwable>()

            if (useFeedExtractor) {
                NewPipe.getService(subscriptionEntity.serviceId)
                    .getFeedExtractor(subscriptionEntity.url)
                    ?.also { feedExtractor ->
                        // the user wants to use a feed extractor and there is one, use it
                        val feedInfo = FeedInfo.getInfo(feedExtractor)
                        errors.addAll(feedInfo.errors)
                        originalInfo = feedInfo
                        streams = feedInfo.relatedItems
                    }
            }

            if (originalInfo == null) {
                // use the normal channel tabs extractor if either the user wants it, or
                // the current service does not have a dedicated feed extractor

                val channelInfo = getChannelInfo(
                    subscriptionEntity.serviceId,
                    subscriptionEntity.url,
                    true
                )
                    .onErrorReturn(storeOriginalErrorAndRethrow)
                    .blockingGet()
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
                        Pair(
                            getChannelTab(subscriptionEntity.serviceId, it, true)
                                .onErrorReturn(storeOriginalErrorAndRethrow)
                                .blockingGet(),
                            it
                        )
                    }
                    .flatMap { (channelTabInfo, linkHandler) ->
                        errors.addAll(channelTabInfo.errors)
                        if (channelTabInfo.relatedItems.isEmpty() &&
                            channelTabInfo.nextPage != null
                        ) {
                            val infoItemsPage = getMoreChannelTabItems(
                                subscriptionEntity.serviceId,
                                linkHandler,
                                channelTabInfo.nextPage
                            )
                                .blockingGet()

                            errors.addAll(infoItemsPage.errors)
                            return@flatMap infoItemsPage.items
                        } else {
                            return@flatMap channelTabInfo.relatedItems
                        }
                    }
                    .filterIsInstance<StreamInfoItem>()
            }

            val tookMs = System.currentTimeMillis() - startMs
            if (errors.isEmpty()) {
                feedDbg(
                    "OK ${label(subscriptionEntity)} streams=${streams?.size ?: 0} tookMs=$tookMs"
                )
            } else {
                // Partial success: streams may have been returned, but at least one tab/page
                // failed. Because info.errors is non-empty, DatabaseConsumer will still mark this
                // subscription as outdated, so it counts as NOT LOADED.
                feedDbg(
                    "PARTIAL ${label(subscriptionEntity)} streams=${streams?.size ?: 0} " +
                        "errors=${errors.size} tookMs=$tookMs -> will count as NOT LOADED"
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
            val request = "${subscriptionEntity.serviceId}:${subscriptionEntity.url}"
            val cause = error ?: e
            feedDbg("FAIL ${label(subscriptionEntity)} tookMs=$tookMs", cause)
            val wrapper = FeedLoadService.RequestException(
                subscriptionEntity.uid,
                request,
                // do this to prevent blockingGet() from wrapping into RuntimeException
                cause
            )
            return Notification.createOnError(wrapper)
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

        feedDbg(
            "REFRESH DONE collectedErrors=${feedResultsHolder.itemsErrors.size} " +
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
         * How many extractions will be running in parallel.
         */
        private const val PARALLEL_EXTRACTIONS = 3

        /**
         * How many YouTube extractions to perform before waiting [DELAY_BETWEEN_BATCHES_MILLIS]
         * to avoid being rate limited
         */
        private const val BATCH_SIZE = 50

        /**
         * Wait a random delay in this range once every [BATCH_SIZE] YouTube extractions to avoid
         * being rate limited
         */
        private val DELAY_BETWEEN_BATCHES_MILLIS = (6000L..12000L)

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
