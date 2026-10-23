package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.roundToInt

/** Measures hardware clock drift from window peaks, excluding network stalls and playback pauses. */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var trim: Double = learnedTrim,
) {
    /** Each finished window contributes its end time and peak buffered level. */
    private val windowEndNanos = LongArray(MAX_WINDOWS)
    private val windowPeakFrames = LongArray(MAX_WINDOWS)
    private var windowCount = 0
    private var windowStartNanos: Long? = null
    private var currentPeakFrames = Long.MIN_VALUE
    private var lastSampleNanos: Long? = null
    private var appliedTrim = trim
    private var lastUpdateNanos: Long? = null
    private var compressedBacklog = false

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /** Paused PCM does not measure drift; keep the learned clock across every playback restart. */
    fun onPlaybackStarted(nowNanos: Long? = null) {
        restart(nowNanos)
        lastSampleNanos = null
        compressedBacklog = false
        if (nowNanos != null && lastUpdateNanos != null) lastUpdateNanos = nowNanos
    }

    /** Include pending compressed audio in the level; it already belongs to the playback queue. */
    fun sample(queuedFrames: Long, nowNanos: Long, compressedFrames: Long = 0L) {
        if (targetFrames <= 0L) return
        val previousSampleNanos = lastSampleNanos
        lastSampleNanos = nowNanos
        val previousCompressedBacklog = compressedBacklog
        // A normal wireless burst can exceed the 300 ms preset without decoder backpressure.
        compressedBacklog = compressedFrames > maxOf(targetFrames, sampleRate.toLong())
        if (
            previousSampleNanos == null || nowNanos - previousSampleNanos > STALE_NS ||
            compressedBacklog || previousCompressedBacklog
        ) {
            restart(nowNanos)
            return
        }
        // Windows measured while the rate glides would mix two rates.
        if (appliedTrim != trim) return
        val bufferedFrames = queuedFrames.coerceAtLeast(0L) + compressedFrames.coerceAtLeast(0L)
        val windowStartedAt = windowStartNanos ?: nowNanos.also { windowStartNanos = it }
        currentPeakFrames = maxOf(currentPeakFrames, bufferedFrames)
        if (nowNanos - windowStartedAt < WINDOW_NS) return
        if (windowCount == MAX_WINDOWS) {
            windowEndNanos.copyInto(windowEndNanos, 0, 1)
            windowPeakFrames.copyInto(windowPeakFrames, 0, 1)
            windowCount--
        }
        windowEndNanos[windowCount] = nowNanos
        windowPeakFrames[windowCount] = currentPeakFrames
        windowCount++
        windowStartNanos = nowNanos
        currentPeakFrames = Long.MIN_VALUE
    }

    /** A rebuffer, a skip or dropped packets moved the level at once: measure the drift again from here. */
    fun restart(nowNanos: Long? = null) {
        windowCount = 0
        windowStartNanos = nowNanos
        currentPeakFrames = Long.MIN_VALUE
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNanos: Long): Int? {
        val previousUpdateNanos = lastUpdateNanos
        if (previousUpdateNanos != null && nowNanos - previousUpdateNanos < UPDATE_NS) return null
        lastUpdateNanos = nowNanos
        measure()
        if (appliedTrim != trim) {
            val seconds = if (previousUpdateNanos == null) Double.MAX_VALUE else (nowNanos - previousUpdateNanos) / 1e9
            val step = MAX_GLIDE_PER_SECOND * seconds.coerceAtMost(MAX_STEP_SECONDS)
            // The first rate of a stream is the trim the last one learned: no glide into it.
            appliedTrim = if (previousUpdateNanos == null || abs(trim - appliedTrim) <= step) {
                trim
            } else {
                appliedTrim + step * Math.signum(trim - appliedTrim)
            }
            if (appliedTrim == trim) restart(nowNanos)
        }
        val nextRate = (sampleRate * (1.0 + appliedTrim)).roundToInt()
        if (nextRate == rate) return null

        rate = nextRate
        return nextRate
    }

    private fun measure() {
        if (appliedTrim != trim || windowCount < MIN_WINDOWS) return
        val slope = robustSlope() // frames per second the buffer gains
        val spanSeconds = (windowEndNanos[windowCount - 1] - windowEndNanos[0]) / 1e9
        if (abs(slope) * spanSeconds < sampleRate * SIGNIFICANT_FRAMES_MS / 1000.0) return
        // A Wi-Fi slowdown can hold the level down for a minute; only a faulty clock is that fast for longer.
        if (abs(slope) < sampleRate * FAST_DRIFT && spanSeconds < SLOW_DRIFT_SPAN_SECONDS) return
        // The buffer gains what the iPhone sends minus what the track plays: play that much faster or slower.
        trim = (trim + slope / sampleRate).coerceIn(-MAX_TRIM, MAX_TRIM)
        learnedTrim = trim
        restart()
    }

    /** Theil–Sen rejects isolated outliers by taking the median slope between window pairs. */
    private fun robustSlope(): Double {
        val slopes = DoubleArray(windowCount * (windowCount - 1) / 2)
        var count = 0
        for (firstWindow in 0 until windowCount) {
            for (nextWindow in firstWindow + 1 until windowCount) {
                val elapsedSeconds = (windowEndNanos[nextWindow] - windowEndNanos[firstWindow]) / 1e9
                slopes[count++] = (windowPeakFrames[nextWindow] - windowPeakFrames[firstWindow]) / elapsedSeconds
            }
        }
        slopes.sort()
        val middle = count / 2
        return if (count % 2 == 1) slopes[middle] else (slopes[middle - 1] + slopes[middle]) / 2
    }

    internal companion object {
        /** At most 1.5 % slower or faster, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015

        /** Long enough to hold several of the iPhone's 0.4 s Wi-Fi bursts, so its peak comes after an on-time one. */
        const val WINDOW_NS = 5_000_000_000L

        /** 20 s of peaks before a first estimate; a 0.76 % drift moves the buffer 150 ms in that time. */
        const val MIN_WINDOWS = 4

        /** A drift this large (13 cents, #610 was 0.76 %) is tuned after [MIN_WINDOWS]; a smaller one needs longer. */
        const val FAST_DRIFT = 0.003

        /** On a wireless head unit a slower iPhone send rate held the level down by 0.065 % for a minute. */
        const val SLOW_DRIFT_SPAN_SECONDS = 120.0

        /** Five minutes of peaks: a drift of 0.013 % then also moves the buffer by [SIGNIFICANT_FRAMES_MS]. */
        const val MAX_WINDOWS = 60

        /** Far above the tens of ms the peaks scatter by, so Wi-Fi alone never changes the rate. */
        const val SIGNIFICANT_FRAMES_MS = 40

        /** About 0.9 cents a second: a 0.76 % correction takes 15 s, once per run. */
        const val MAX_GLIDE_PER_SECOND = 0.0005
        const val UPDATE_NS = 1_000_000_000L

        /** Longer than the 0.4 s between the iPhone's bursts over Wi-Fi: a longer silence is a pause or a stall. */
        const val STALE_NS = 1_000_000_000L
        const val MAX_STEP_SECONDS = 2.0

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile
        var learnedTrim = 0.0
    }
}
