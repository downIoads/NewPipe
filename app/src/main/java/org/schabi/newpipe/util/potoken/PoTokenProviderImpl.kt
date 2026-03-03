package org.schabi.newpipe.util.potoken

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.schabi.newpipe.App
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.util.DeviceUtils

object PoTokenProviderImpl : PoTokenProvider {
    val TAG = PoTokenProviderImpl::class.simpleName
    private val webViewSupported by lazy { DeviceUtils.supportsWebView() }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private object WebPoTokenGenLock
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenGenerator? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) {
            return null
        }

        try {
            return getWebClientPoToken(videoId = videoId, forceRecreate = false)
        } catch (e: RuntimeException) {
            // RxJava's Single wraps exceptions into RuntimeErrors, so we need to unwrap them here
            when (val cause = e.cause) {
                is BadWebViewException -> {
                    Log.e(TAG, "Could not obtain poToken because WebView is broken", e)
                    webViewBadImpl = true
                    return null
                }

                null -> throw e

                else -> throw cause // includes PoTokenException
            }
        }
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenGenerator.generatePoToken] was called
     */
    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        // just a helper class since Kotlin does not have builtin support for 4-tuples
        data class Quadruple<T1, T2, T3, T4>(val t1: T1, val t2: T2, val t3: T3, val t4: T4)

        val (poTokenGenerator, visitorData, streamingPot, hasBeenRecreated) =
            synchronized(WebPoTokenGenLock) {
                val shouldRecreate = webPoTokenGenerator == null || forceRecreate ||
                    webPoTokenGenerator!!.isExpired()

                if (shouldRecreate) {
                    val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
                    innertubeClientRequestInfo.clientInfo.clientVersion =
                        YoutubeParsingHelper.getClientVersion()

                    webPoTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                        innertubeClientRequestInfo,
                        NewPipe.getPreferredLocalization(),
                        NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(),
                        YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null,
                        false
                    )
                    // close the current webPoTokenGenerator on the main thread
                    webPoTokenGenerator?.let { Handler(Looper.getMainLooper()).post { it.close() } }

                    // create a new webPoTokenGenerator
                    webPoTokenGenerator = PoTokenWebView
                        .newPoTokenGenerator(App.getApp()).blockingGet()

                    // The streaming poToken needs to be generated exactly once before generating
                    // any other (player) tokens.
                    webPoTokenStreamingPot = webPoTokenGenerator!!
                        .generatePoToken(webPoTokenVisitorData!!).blockingGet()
                }

                return@synchronized Quadruple(
                    webPoTokenGenerator!!,
                    webPoTokenVisitorData!!,
                    webPoTokenStreamingPot!!,
                    shouldRecreate
                )
            }

        val playerPot: String
        val gvsPot: String
        try {
            // Not using synchronized here, since poTokenGenerator would be able to generate
            // multiple poTokens in parallel if needed. The only important thing is for exactly one
            // visitorData/streaming poToken to be generated before anything else.

            // Player token: video-ID-bound, sent in serviceIntegrityDimensions.poToken
            playerPot = poTokenGenerator.generatePoToken(videoId).blockingGet()

            // GVS token: also video-ID-bound (YouTube experiment html5_generate_content_po_token).
            // YouTube has shifted from visitorData-bound to video-ID-bound GVS tokens.
            // This token is appended to streaming URLs as &pot=
            gvsPot = poTokenGenerator.generatePoToken(videoId).blockingGet()
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if NewPipe goes in the background and the WebView
                // content is lost
                Log.e(TAG, "Failed to obtain poToken, retrying", throwable)
                return getWebClientPoToken(videoId = videoId, forceRecreate = true)
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "poToken for $videoId: playerPot=$playerPot, " +
                    "gvsPot=$gvsPot, visitor_data=$visitorData"
            )
        }

        return PoTokenResult(visitorData, playerPot, gvsPot)
    }

    /**
     * Returns the cached streaming poToken without regenerating it.
     * This is the same streaming pot that was embedded in the stream URLs by the extractor.
     * Returns null if no pot has been generated yet (e.g. WebView not supported).
     */
    fun getCachedStreamingPot(): String? {
        synchronized(WebPoTokenGenLock) {
            return webPoTokenStreamingPot
        }
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? {
        // BotGuard-generated poTokens are bound to visitorData/videoId, not client type.
        // The same WEB BotGuard token works for ANDROID player requests.
        // Per yt-dlp: ANDROID GVS pot is not required if a player token is provided.
        return getWebClientPoToken(videoId)
    }

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
