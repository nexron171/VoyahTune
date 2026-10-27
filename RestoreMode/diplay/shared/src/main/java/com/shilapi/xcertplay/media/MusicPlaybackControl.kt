package com.shilapi.xcertplay.media

import kotlin.math.abs

/** Owns clock calibration and pitch-preserving latency recovery on one music track. */
internal class MusicPlaybackControl(
    private val sampleRate: Int,
    targetFrames: Long,
    private val recoverLatency: Boolean = true,
) {
    data class Adjustment(val clockRate: Int, val tempo: Double)

    val clock = MediaClockTrim(sampleRate, targetFrames)
    private val latency = MusicLatencyController(sampleRate, targetFrames)
    private val driftTargetFrames = targetFrames
    private var previousHead: Long? = null
    private var previousObservationNanos: Long? = null
    private var outputClockDeficitFrames = 0.0
    private var lastUpdateNanos: Long? = null
    private var extraConsumedFrames = 0.0
    private var tempoAvailable = recoverLatency
    private var clockAvailable = true

    var applied = Adjustment(sampleRate, 1.0)
        private set

    val targetFrames: Long
        get() = latency.targetFrames.toLong()

    fun started(nowNanos: Long) {
        previousHead = null
        previousObservationNanos = null
        outputClockDeficitFrames = 0.0
        lastUpdateNanos = null
        extraConsumedFrames = 0.0
        clock.onPlaybackStarted(nowNanos)
        latency.stalled(nowNanos)
    }

    fun stalled(nowNanos: Long, rebuffer: Boolean = false) {
        clock.restart(nowNanos)
        latency.stalled(nowNanos, rebuffer)
    }

    fun observe(
        queuedFrames: Long,
        compressedFrames: Long,
        rawPlaybackHead: Int,
        nowNanos: Long,
    ): Adjustment? {
        val head = rawPlaybackHead.toLong() and UNSIGNED_INT_MASK
        previousHead?.let { previous ->
            val playedFrames = (head - previous) and UNSIGNED_INT_MASK
            if (playedFrames < sampleRate * MAX_HEAD_ADVANCE_SECONDS) {
                // Remove our own tempo correction from the drift measurement, in source frames.
                extraConsumedFrames += playedFrames * (1.0 - 1.0 / applied.tempo)
                previousObservationNanos?.let { previousNanos ->
                    val elapsedNanos = nowNanos - previousNanos
                    if (elapsedNanos in 0..MusicLatencyController.STALL_GAP_NANOS) {
                        outputClockDeficitFrames += sampleRate * (elapsedNanos / 1e9) - playedFrames
                    } else {
                        stalled(nowNanos)
                    }
                }
            } else {
                stalled(nowNanos)
            }
        }
        previousHead = head
        previousObservationNanos = nowNanos
        if (clockAvailable) {
            if (recoverLatency) {
                clock.sample(queuedFrames, nowNanos, compressedFrames, extraConsumedFrames)
            } else {
                // Credit-based TCP feeding keeps the queue constant even when the output clock
                // drifts.
                clock.sample(driftTargetFrames, nowNanos, 0L, outputClockDeficitFrames)
            }
        }
        latency.sample(queuedFrames + compressedFrames, nowNanos)
        val previousUpdate = lastUpdateNanos
        if (previousUpdate != null && nowNanos - previousUpdate < UPDATE_NANOS) return null
        lastUpdateNanos = nowNanos
        val clockRate =
            if (clockAvailable) clock.rateDue(nowNanos) ?: clock.rate else applied.clockRate
        val correction = if (tempoAvailable) latency.tempoCorrection(nowNanos) else 0.0
        val desiredTempo = 1.0 + correction
        val elapsedSeconds =
            if (previousUpdate == null) 0.0
            else ((nowNanos - previousUpdate) / 1e9).coerceIn(0.0, 2.0)
        val maxChange = TEMPO_GLIDE_PER_SECOND * elapsedSeconds
        val tempo =
            when {
                !tempoAvailable -> 1.0
                queuedFrames < sampleRate / 10 && desiredTempo < applied.tempo ->
                    minOf(1.0, desiredTempo)
                abs(desiredTempo - applied.tempo) <= maxChange -> desiredTempo
                else -> applied.tempo + Math.copySign(maxChange, desiredTempo - applied.tempo)
            }
        return Adjustment(clockRate, tempo).takeUnless { it == applied }
    }

    fun applied(adjustment: Adjustment) {
        applied = adjustment
    }

    fun disableTempo() {
        tempoAvailable = false
    }

    fun disableClock() {
        clockAvailable = false
    }

    companion object {
        private const val UNSIGNED_INT_MASK = 0xffff_ffffL
        private const val UPDATE_NANOS = 1_000_000_000L
        private const val MAX_HEAD_ADVANCE_SECONDS = 60
        private const val TEMPO_GLIDE_PER_SECOND = 0.0005
    }
}
