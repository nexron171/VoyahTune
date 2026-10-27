package com.shilapi.xcertplay.media

import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusicPlaybackControlTest {
    @Before
    fun resetClock() {
        MediaClockTrim.learnedTrim = 0.0
    }

    @After
    fun clearClock() {
        MediaClockTrim.learnedTrim = 0.0
    }

    private class Playback(
        private val hardwareFactor: Double,
        extraMillis: Int = 0,
        private val burstDelivery: Boolean = false,
        recoverLatency: Boolean = true,
        private val feedByOutputCredits: Boolean = false,
    ) {
        val control = MusicPlaybackControl(48_000, 48_000, recoverLatency)
        var queuedFrames = 48_000.0 + extraMillis * 48
        var playedFrames = 0.0
        var minimumQueuedFrames = queuedFrames
        var maximumQueuedFrames = queuedFrames
        val adjustments = mutableListOf<MusicPlaybackControl.Adjustment>()

        init {
            control.started(0L)
        }

        fun run(seconds: Int, startMillis: Int = 0) {
            for (millis in startMillis until startMillis + seconds * 1000 step 10) {
                val played =
                    control.applied.clockRate * hardwareFactor * control.applied.tempo / 100.0
                val receivedFrames =
                    if (feedByOutputCredits) played
                    else if (burstDelivery) {
                        if (millis % 400 == 0) 19_200.0 else 0.0
                    } else 480.0
                queuedFrames += receivedFrames - played
                playedFrames += played
                control
                    .observe(
                        queuedFrames.toLong(),
                        0,
                        playedFrames.toLong().toInt(),
                        millis * 1_000_000L,
                    )
                    ?.let { adjustment ->
                        adjustments += adjustment
                        control.applied(adjustment)
                    }
                minimumQueuedFrames = minOf(minimumQueuedFrames, queuedFrames)
                maximumQueuedFrames = maxOf(maximumQueuedFrames, queuedFrames)
            }
        }
    }

    @Test
    fun aSmallSlowClockConvergesWhileLatencyRecoveryIsActive() {
        val playback = Playback(0.9975)
        playback.run(900)

        assertEquals(48_000.0 / 0.9975, playback.control.applied.clockRate.toDouble(), 8.0)
        assertTrue(playback.adjustments.any { it.tempo > 1.0 })
        assertTrue(playback.minimumQueuedFrames > 24_000)
        assertTrue(playback.maximumQueuedFrames < 86_400)
        assertTrue(playback.control.clock.rate > 48_000)
    }

    @Test
    fun differentClockErrorsKeepTheQueueBoundedForFifteenMinutes() {
        for (drift in listOf(-0.0076, -0.0025, -0.0015, -0.0005, 0.0005, 0.0015, 0.0025, 0.0076)) {
            MediaClockTrim.learnedTrim = 0.0
            val playback = Playback(1.0 + drift)
            playback.run(900)
            assertEquals(
                "drift=$drift",
                48_000.0 / (1.0 + drift),
                playback.control.applied.clockRate.toDouble(),
                12.0,
            )
            assertTrue(
                "drift=$drift min=${playback.minimumQueuedFrames}",
                playback.minimumQueuedFrames > 12_000,
            )
            assertTrue(
                "drift=$drift max=${playback.maximumQueuedFrames}",
                playback.maximumQueuedFrames < 86_400,
            )
        }
    }

    @Test
    fun excessAudioIsConsumedGraduallyWithoutLosingClockCalibration() {
        val playback = Playback(1.0, extraMillis = 600)
        playback.run(300)
        assertEquals(48_000, playback.control.applied.clockRate)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 0.0001)
        assertTrue(playback.queuedFrames < 48_000 * 1.12)
        assertTrue(playback.adjustments.any { it.tempo > 1.005 })
        playback.adjustments.zipWithNext().forEach { (first, second) ->
            assertTrue(abs(second.tempo - first.tempo) <= 0.000501)
        }
    }

    @Test
    fun normalBatchesDoNotBecomeAnInventedClockDrift() {
        val playback = Playback(1.0, burstDelivery = true)
        playback.run(600)
        assertEquals(48_000, playback.control.applied.clockRate)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 0.0001)
        assertTrue(playback.minimumQueuedFrames > 24_000)
    }

    @Test
    fun rebufferReserveDecaysGraduallyAfterTheConnectionSettles() {
        val latency = MusicLatencyController(48_000, 48_000)
        repeat(6) { latency.stalled(it * 1_000_000_000L, rebuffer = true) }
        assertEquals(76_800.0, latency.targetFrames, 0.0)
        for (step in 60..2000) latency.sample(76_800, step * 100_000_000L)
        assertEquals(48_000.0, latency.targetFrames, 0.1)
    }

    @Test
    fun anUnavailableTempoBackendKeepsClockCalibrationWorking() {
        val playback = Playback(0.9975)
        playback.control.disableTempo()
        playback.run(600)
        assertEquals(48_000.0 / 0.9975, playback.control.applied.clockRate.toDouble(), 8.0)
        assertTrue(playback.adjustments.all { it.tempo == 1.0 })
    }

    @Test
    fun bufferedTcpDoesNotTryToCatchUpItsIntentionalLead() {
        val playback = Playback(1.0, extraMillis = 600, recoverLatency = false)
        playback.run(300)
        assertTrue(playback.adjustments.isEmpty())
        assertEquals(76_800.0, playback.queuedFrames, 0.1)
    }

    @Test
    fun bufferedOutputCreditsDoNotHideTheHardwareClockDrift() {
        val playback = Playback(0.9975, recoverLatency = false, feedByOutputCredits = true)
        playback.run(600)
        assertEquals(48_000.0 / 0.9975, playback.control.applied.clockRate.toDouble(), 8.0)
        assertTrue(playback.adjustments.all { it.tempo == 1.0 })
        assertEquals(48_000.0, playback.queuedFrames, 0.1)
    }

    @Test
    fun aPlaybackRestartDoesNotCountThePausedTimeAsDrift() {
        val playback = Playback(0.9975)
        playback.run(300)
        val learned = MediaClockTrim.learnedTrim
        playback.control.started(900_000_000_000L)
        playback.run(30, startMillis = 900_000)
        assertEquals(learned, MediaClockTrim.learnedTrim, 0.0001)
    }
}
