/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.schabi.newpipe.player

/** A manual seek overrides automatic skipping until the replayed interval has finished. */
internal class SponsorBlockReplay {
    private var resumeAt = -1L

    fun reset() {
        resumeAt = -1L
    }

    fun onManualSeek(from: Long, to: Long, segments: List<SponsorBlockSegments.Segment>, duration: Long) {
        val segmentEnd = SponsorBlockSegments.skipTarget(segments, to, duration) ?: -1L
        resumeAt = if (to < from) maxOf(resumeAt, from, segmentEnd) else segmentEnd
    }

    fun shouldSkip(position: Long): Boolean {
        if (position < resumeAt) return false
        reset()
        return true
    }
}
