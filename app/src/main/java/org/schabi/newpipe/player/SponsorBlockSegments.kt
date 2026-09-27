/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.player

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.security.MessageDigest
import kotlin.math.ceil
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** PipePipe's hash-prefix lookup, restricted to sponsored segments, without extractor fork coupling. */
internal object SponsorBlockSegments {
    private val categories = listOf("sponsor")

    data class Segment(val startMs: Long, val endMs: Long)

    fun request(videoId: String): Request {
        val hash = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
        val prefix = hash.take(2).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val url = "https://sponsor.ajay.app/api/skipSegments/$prefix".toHttpUrl().newBuilder()
            .addQueryParameter("categories", categories.joinToString(",", "[", "]") { "\"$it\"" })
            .addQueryParameter("actionTypes", "[\"skip\"]")
            .addQueryParameter("userAgent", "NewPipe-SponsorBlock")
            .build()
        return Request.Builder().url(url).build()
    }

    fun parse(body: String, videoId: String): List<Segment> {
        val result = mutableListOf<Segment>()
        for (entry in JsonParser.array().from(body)) {
            val video = entry as? JsonObject ?: continue
            if (video.getString("videoID") != videoId) continue
            for (item in video.getArray("segments") ?: JsonArray()) {
                val segment = item as? JsonObject ?: continue
                if (segment.getString("actionType") != "skip" ||
                    segment.getString("category") !in categories
                ) {
                    continue
                }
                val times = segment.getArray("segment") ?: continue
                if (times.size != 2) continue
                val start = (times[0] as? Number)?.toDouble() ?: continue
                val end = (times[1] as? Number)?.toDouble() ?: continue
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start ||
                    end >= Long.MAX_VALUE / 1000.0
                ) {
                    continue
                }
                result.add(Segment(ceil(start * 1000).toLong(), ceil(end * 1000).toLong()))
            }
        }
        return result.sortedBy { it.startMs }
    }

    /** Merge overlapping/adjacent skips in one seek; segment ends are exclusive. */
    fun skipTarget(segments: List<Segment>, position: Long, duration: Long): Long? {
        if (duration <= 0 || position < 0 || position >= duration) return null
        var target = position
        for (segment in segments) {
            if (segment.startMs > target) break
            if (segment.endMs > target) target = segment.endMs.coerceAtMost(duration)
        }
        return target.takeIf { it > position }
    }
}
