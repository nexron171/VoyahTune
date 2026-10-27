package com.shilapi.xcertplay.media

import kotlin.math.abs

/** Smoothly returns excess latency through a bounded tempo correction. */
internal class MusicLatencyController(
    private val sampleRate: Int,
    private val baseTargetFrames: Long,
) {
    private data class LowLevel(val second: Long, var frames: Long)

    private val lowLevels = ArrayDeque<LowLevel>()
    private var calmSinceNanos: Long? = null
    private var lastSampleNanos: Long? = null
    private var reserveFrames = 0.0

    var targetFrames = baseTargetFrames.toDouble()
        private set

    fun stalled(nowNanos: Long, rebuffer: Boolean = false) {
        calmSinceNanos = nowNanos
        lastSampleNanos = nowNanos
        lowLevels.clear()
        if (rebuffer) {
            reserveFrames =
                minOf(
                    reserveFrames + sampleRate * MediaAudioBuffer.CUSHION_STEP_MILLIS / 1000.0,
                    sampleRate * MediaAudioBuffer.CUSHION_MILLIS / 1000.0,
                )
        }
        targetFrames = baseTargetFrames + reserveFrames
    }

    fun sample(bufferedFrames: Long, nowNanos: Long) {
        val previousNanos = lastSampleNanos
        if (previousNanos != null && nowNanos - previousNanos > STALL_GAP_NANOS) {
            stalled(nowNanos)
        }
        lastSampleNanos = nowNanos
        val calmSince = calmSinceNanos ?: nowNanos.also { calmSinceNanos = it }
        if (previousNanos != null && nowNanos - calmSince >= CALM_NANOS) {
            val elapsedSeconds = ((nowNanos - previousNanos) / 1e9).coerceIn(0.0, 1.0)
            reserveFrames =
                maxOf(0.0, reserveFrames - sampleRate * RESERVE_DECAY_PER_SECOND * elapsedSeconds)
            targetFrames = baseTargetFrames + reserveFrames
        }

        val second = nowNanos / SECOND_NANOS
        val last = lowLevels.lastOrNull()
        if (last?.second == second) {
            last.frames = minOf(last.frames, bufferedFrames)
        } else {
            lowLevels.addLast(LowLevel(second, bufferedFrames))
        }
        while (lowLevels.firstOrNull()?.let { second - it.second >= WINDOW_SECONDS } == true) {
            lowLevels.removeFirst()
        }
    }

    fun tempoCorrection(nowNanos: Long): Double {
        val calmSince = calmSinceNanos ?: return 0.0
        if (nowNanos - calmSince < CALM_NANOS || lowLevels.size < WINDOW_SECONDS) return 0.0
        val errorFrames = lowLevels.minOf { it.frames } - targetFrames
        val deadbandFrames = sampleRate * DEADBAND_MILLIS / 1000.0
        if (abs(errorFrames) <= deadbandFrames) return 0.0
        val outsideDeadband = errorFrames - Math.copySign(deadbandFrames, errorFrames)
        return (outsideDeadband / (sampleRate * RECOVERY_SECONDS)).coerceIn(
            -MAX_SLOWDOWN,
            MAX_SPEEDUP,
        )
    }

    companion object {
        const val STALL_GAP_NANOS = 800_000_000L
        const val MAX_SPEEDUP = 0.01
        const val MAX_SLOWDOWN = 0.005
        private const val SECOND_NANOS = 1_000_000_000L
        private const val CALM_NANOS = 30_000_000_000L
        private const val WINDOW_SECONDS = 10
        private const val DEADBAND_MILLIS = 75
        private const val RECOVERY_SECONDS = 30.0
        private const val RESERVE_DECAY_PER_SECOND = 0.005
    }
}
