package com.shilapi.xcertplay.media

/**
 * Decides when music queued beyond the normal buffer may be skipped, so a Wi-Fi stall does not leave
 * music late for good. Pitch never changes for this.
 *
 * After a stall the iPhone sends what it could not, and the music plays that much later than before. While
 * the connection is flaky that extra audio is a cushion: the next stall plays from it instead of going
 * silent. Once no stall came for [calmNs], the lowest level of the last [LOW_WINDOW_NS] above the target
 * is skipped in one jump, which leaves the buffer at least as full as a normal start. A stall soon after a
 * skip doubles the calm that the next skip waits for, up to [MAX_CALM_NS]: on a link that keeps dropping,
 * the cushion stays.
 */
internal class MusicCatchUp(private val targetFrames: Long, private val sampleRate: Int) {
    private var calmSinceNs: Long? = null
    private var lastSkipNs: Long? = null
    /** Lowest level in each second of the last [LOW_WINDOW_NS], oldest first. */
    private val lowSeconds = ArrayDeque<LongArray>()

    /** The calm a skip waits for now. */
    var calmNs = MIN_CALM_NS
        private set

    /** A gap in the packets, a rebuffer or dropped packets: the connection is not calm. */
    fun stalled(nowNs: Long) {
        val skipped = lastSkipNs
        if (skipped != null && nowNs - skipped < FLAKY_AFTER_SKIP_NS) calmNs = (calmNs * 2).coerceAtMost(MAX_CALM_NS)
        calmSinceNs = nowNs
        lowSeconds.clear()
    }

    /** Call before and after each write while music plays, with the same level the clock trim sees. */
    fun sample(bufferedFrames: Long, nowNs: Long) {
        if (calmSinceNs == null) calmSinceNs = nowNs
        val second = nowNs / 1_000_000_000L
        val last = lowSeconds.lastOrNull()
        if (last != null && last[0] == second) last[1] = minOf(last[1], bufferedFrames)
        else lowSeconds.addLast(longArrayOf(second, bufferedFrames))
        while (lowSeconds.size > LOW_WINDOW_NS / 1_000_000_000L) lowSeconds.removeFirst()
    }

    /** Frames to skip now, or 0. A skip starts a new calm period. */
    fun framesToSkip(nowNs: Long): Long {
        val calmSince = calmSinceNs ?: return 0L
        if (nowNs - calmSince < calmNs || lowSeconds.size * 1_000_000_000L < LOW_WINDOW_NS) return 0L
        val excess = lowSeconds.minOf { it[1] } - targetFrames
        if (excess < sampleRate * MIN_SKIP_MS / 1000L) return 0L
        lastSkipNs = nowNs
        calmSinceNs = nowNs
        lowSeconds.clear()
        return excess
    }

    internal companion object {
        const val MIN_CALM_NS = 30_000_000_000L
        const val MAX_CALM_NS = 480_000_000_000L
        /** A stall this soon after a skip means the link is still flaky. */
        const val FLAKY_AFTER_SKIP_NS = 180_000_000_000L
        /** The lowest level over this long is what a skip may take; it covers many 0.4 s Wi-Fi bursts. */
        const val LOW_WINDOW_NS = 10_000_000_000L
        /** A smaller excess is not worth a jump in the song. */
        const val MIN_SKIP_MS = 200L
        /** Longer than the 0.4–0.55 s between the iPhone's bursts over Wi-Fi. */
        const val STALL_GAP_NS = 800_000_000L
    }
}
