package com.shilapi.xcertplay.media

import kotlin.math.exp
import kotlin.math.roundToInt

/** Aligns music clocks using the time-averaged buffer level, excluding pauses and decoder backpressure. */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var integral: Double = learnedTrim,
) {
    private var averagedLevelError: Double? = null
    private var lastSampleNanos = 0L
    private var lastBufferedFrames = 0L
    private var lastUpdateNanos: Long? = null
    private var observedPlaybackSeconds = 0.0
    private var compressedBacklog = false
    private var playbackRestarted = false

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /** A paused track does not drain while it refills; keep its clock correction, not its buffer history. */
    fun onPlaybackStarted() {
        averagedLevelError = null
        lastUpdateNanos = null
        observedPlaybackSeconds = 0.0
        compressedBacklog = false
        playbackRestarted = true
    }

    /** Include compressed packets waiting for decode: those frames are already queued audio. */
    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L) {
        if (targetFrames <= 0L) return

        val previousCompressedBacklog = compressedBacklog
        compressedBacklog = compressedFrames > targetFrames
        val bufferedFrames = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * 16L)
        val previousLevelError = averagedLevelError
        val elapsedNanos = (nowNs - lastSampleNanos).coerceAtLeast(0L)

        when {
            previousLevelError == null -> {
                averagedLevelError = if (compressedBacklog || playbackRestarted) {
                    0.0
                } else {
                    levelError(bufferedFrames.toDouble())
                }
            }

            elapsedNanos > STALE_SAMPLE_NANOS -> {
                // A resumed burst cannot measure drift during the silence; retain only the learned clock.
                averagedLevelError = 0.0
                observedPlaybackSeconds = 0.0
            }

            !previousCompressedBacklog -> {
                // Writes sample burst peaks. Account for the audio consumed between them instead.
                val meanBufferedFrames = drainedMean(lastBufferedFrames.toDouble(), rate * elapsedNanos / 1e9)
                val weight = 1.0 - exp(-elapsedNanos.toDouble() / AVERAGING_TIME_NANOS)
                averagedLevelError = previousLevelError + (levelError(meanBufferedFrames) - previousLevelError) * weight
                observedPlaybackSeconds = (observedPlaybackSeconds + elapsedNanos / 1e9)
                    .coerceAtMost(MAX_INTEGRATION_SECONDS)
            }
        }

        // Congested intervals never enter the average or the clock learned by the next stream.
        lastBufferedFrames = bufferedFrames
        lastSampleNanos = nowNs
        playbackRestarted = false
    }

    private fun levelError(frames: Double): Double = (frames - targetFrames) / targetFrames

    /** The mean of a level that starts at [start] and drains [drained] frames at a steady pace, stopping at 0. */
    private fun drainedMean(start: Double, drained: Double): Double = when {
        drained <= 0.0 -> start
        drained <= start -> start - drained / 2
        else -> start * start / (2 * drained)
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        val error = averagedLevelError ?: return null
        if (compressedBacklog || nowNs - lastSampleNanos > STALE_SAMPLE_NANOS) return null

        val previousUpdateNanos = lastUpdateNanos
        if (previousUpdateNanos != null && nowNs - previousUpdateNanos < UPDATE_INTERVAL_NANOS) return null
        lastUpdateNanos = nowNs

        // Count observed playback, not time spent paused or blocked behind the decoder.
        integral = (integral + INTEGRAL_GAIN * error * observedPlaybackSeconds)
            .coerceIn(-MAX_RATE_ADJUSTMENT, MAX_RATE_ADJUSTMENT)
        learnedTrim = integral
        observedPlaybackSeconds = 0.0

        val adjustment = (PROPORTIONAL_GAIN * error + integral)
            .coerceIn(-MAX_RATE_ADJUSTMENT, MAX_RATE_ADJUSTMENT)
        val nextRate = (sampleRate * (1.0 + adjustment)).roundToInt()
        if (nextRate == rate) return null

        rate = nextRate
        return nextRate
    }

    internal companion object {
        /** At most 1.5 % slower or faster: 26 cents, above the 0.76 % seen on #610. */
        const val MAX_RATE_ADJUSTMENT = 0.015
        const val PROPORTIONAL_GAIN = 0.03

        /** Per second, per unit of level error. */
        const val INTEGRAL_GAIN = 0.001
        const val AVERAGING_TIME_NANOS = 5_000_000_000.0
        const val UPDATE_INTERVAL_NANOS = 1_000_000_000L

        /** Longer than the typical 0.4 s between wireless bursts. */
        const val STALE_SAMPLE_NANOS = 1_000_000_000L
        const val MAX_INTEGRATION_SECONDS = 2.0

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile
        var learnedTrim = 0.0
    }
}
