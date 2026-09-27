/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.player

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.exoplayer2.SeekParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.ui.VideoPlayerUi
import org.schabi.newpipe.util.StreamTypeUtil

/**
 * Automatic skipping adapted from PipePipe's SponsorBlockController and extractor helper.
 * Requests are optional, asynchronous and scoped to the current video, never to the play queue.
 */
class SponsorBlockController(private val player: Player) {
    private val handler = Handler(Looper.getMainLooper())
    private val key = player.context.getString(R.string.sponsor_block_enabled_key)
    private var videoId: String? = null
    private var request: Call? = null
    private var segments = emptyList<SponsorBlockSegments.Segment>()
    private val replay = SponsorBlockReplay()
    private var destroyed = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
        if (changed == null || changed == key) {
            handler.post {
                if (!destroyed) {
                    reset()
                    onProgress()
                }
            }
        }
    }

    init {
        player.prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun reset() {
        request?.cancel()
        request = null
        videoId = null
        segments = emptyList()
        replay.reset()
        updateMarkers()
    }

    fun destroy() {
        destroyed = true
        reset()
        player.prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        handler.removeCallbacksAndMessages(null)
    }

    fun onProgress() {
        if (destroyed) return
        val exo = player.exoPlayer
        val info = MediaItemTag.from(exo?.currentMediaItem)
            .flatMap { it.maybeStreamInfo }.orElse(null)
        val enabled = player.prefs.getBoolean(key, false)
        val id = info?.takeIf {
            enabled && it.serviceId == ServiceList.YouTube.serviceId &&
                !StreamTypeUtil.isLiveStream(it.streamType)
        }?.id
        if (id != videoId) {
            reset()
            videoId = id
            if (id != null) fetch(id)
        }
        updateMarkers()
        if (exo == null) return
        if (!enabled || !exo.isPlaying || !exo.isCurrentMediaItemSeekable) return
        if (!replay.shouldSkip(exo.currentPosition)) return
        val target = SponsorBlockSegments.skipTarget(segments, exo.currentPosition, exo.duration)
            ?: return
        // Like PipePipe, force exact seeking so a preceding keyframe cannot cause a skip loop.
        val previous = exo.seekParameters
        exo.setSeekParameters(SeekParameters.EXACT)
        exo.seekTo(target)
        exo.setSeekParameters(previous)
        Log.d(TAG, "Skipped segment to ${target}ms")
    }

    fun onManualSeek(from: Long, to: Long) {
        replay.onManualSeek(from, to, segments, player.exoPlayer?.duration ?: -1L)
    }

    fun getMarkers(): FloatArray {
        val duration = player.exoPlayer?.duration ?: -1L
        return if (duration > 0) {
            segments.flatMap {
                listOf(
                    (it.startMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat(),
                    (it.endMs.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
                )
            }.toFloatArray()
        } else {
            floatArrayOf()
        }
    }

    fun updateMarkers() {
        val markers = getMarkers()
        player.UIs().call { ui ->
            if (ui is VideoPlayerUi) ui.binding.playbackSeekBar.setSponsorBlockMarkers(markers)
        }
    }

    private fun fetch(id: String) {
        val call = client.newCall(SponsorBlockSegments.request(id))
        request = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) Log.w(TAG, "Segment lookup failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        when {
                            it.code == 404 -> emptyList()
                            !it.isSuccessful -> throw IOException("SponsorBlock HTTP ${it.code}")
                            else -> SponsorBlockSegments.parse(it.body?.string().orEmpty(), id)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Segment lookup failed", e)
                    emptyList()
                }
                handler.post {
                    // A cancelled request may already have queued its callback when videos change.
                    if (!destroyed && request === call && videoId == id &&
                        player.prefs.getBoolean(key, false)
                    ) {
                        segments = result
                        Log.d(TAG, "Loaded ${result.size} segments")
                        onProgress()
                    }
                }
            }
        })
    }

    private companion object {
        const val TAG = "SponsorBlock"
        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
