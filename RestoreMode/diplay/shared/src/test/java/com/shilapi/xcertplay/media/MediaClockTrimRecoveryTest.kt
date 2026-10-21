package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class MediaClockTrimRecoveryTest {
    @Before
    fun resetLearnedTrim() {
        MediaClockTrim.learnedTrim = 0.0
    }

    @After
    fun clearLearnedTrim() {
        MediaClockTrim.learnedTrim = 0.0
    }

    @Test
    fun theFirstBurstAfterAPauseDoesNotChangeTheLearnedClock() {
        MediaClockTrim.learnedTrim = -0.0075
        val trim = MediaClockTrim(48_000, 48_000)
        trim.sample(48_000, 0)
        assertEquals(47_640, trim.rateDue(0))

        // A resumed burst is a new buffer level, not five seconds of clock drift.
        trim.sample(62_400, 5_000_000_000L)

        assertNull(trim.rateDue(5_000_000_000L))
        assertEquals(47_640, trim.rate)
        assertEquals(-0.0075, MediaClockTrim.learnedTrim, 1e-9)
    }

    @Test
    fun aShortRebufferDiscardsPausedHistoryButKeepsTheLearnedClock() {
        MediaClockTrim.learnedTrim = -0.0075
        val trim = MediaClockTrim(48_000, 48_000)
        trim.sample(48_000, 0)
        trim.rateDue(0)

        trim.onPlaybackStarted(400_000_000L)
        trim.sample(62_400, 400_000_000L)

        assertNull(trim.rateDue(400_000_000L))
        assertEquals(47_640, trim.rate)
        assertEquals(-0.0075, MediaClockTrim.learnedTrim, 1e-9)
    }

    @Test
    fun aCompressedBacklogCannotSlowMusicOnItsFirstSample() {
        val trim = MediaClockTrim(48_000, 48_000)
        trim.sample(48_000, 0)
        trim.rateDue(0)

        // The previous level drained normally, but the decoder now holds two seconds of music.
        trim.sample(48_000, 1_000_000_000L, compressedFrames = 96_000)
        trim.rateDue(1_000_000_000L)

        assertTrue("Playback rate ${trim.rate}", trim.rate >= 48_000)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 1e-9)
    }

    @Test
    fun aClearedBacklogDoesNotTeachTheNextSongAFasterClock() {
        val trim = MediaClockTrim(48_000, 48_000)
        trim.sample(48_000, 0, compressedFrames = 96_000)
        trim.rateDue(0)

        trim.sample(48_000, 1_000_000_000L)
        trim.rateDue(1_000_000_000L)

        assertEquals(48_000, trim.rate)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 1e-9)
    }

    @Test
    fun aShortRebufferDoesNotJoinTwoDifferentLevelsIntoADrift() {
        val trim = MediaClockTrim(48_000, 14_400)
        observeLevel(trim, 0, 150, 30_000)

        trim.onPlaybackStarted(15_100_000_000L)
        observeLevel(trim, 151, 301, 14_400)

        assertEquals(48_000, trim.rate)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 0.0)
    }

    @Test
    fun decoderBackpressureSeparatesTheObservationsBeforeAndAfterIt() {
        val trim = MediaClockTrim(48_000, 14_400)
        observeLevel(trim, 0, 150, 30_000)
        for (step in 151..170) {
            val nowNanos = step * 100_000_000L
            trim.sample(30_000, nowNanos, compressedFrames = 96_000)
            trim.rateDue(nowNanos)
        }
        observeLevel(trim, 171, 321, 14_400)

        assertEquals(48_000, trim.rate)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 0.0)
    }

    private fun observeLevel(trim: MediaClockTrim, firstStep: Int, lastStep: Int, frames: Long) {
        for (step in firstStep..lastStep) {
            val nowNanos = step * 100_000_000L
            trim.sample(frames, nowNanos)
            trim.rateDue(nowNanos)
        }
    }

    @Test
    fun aOneSecondBufferKeepsABatchedTrackNearItsRealRate() {
        for (seed in 1..3) {
            MediaClockTrim.learnedTrim = 0.0
            val playback = BatchedPlayback(bufferMillis = 1000, seed = seed)

            playback.run(seconds = 300)

            assertEquals("Seed $seed underruns", 0, playback.underruns)
            assertEquals(48_000.0, playback.settledRates.average(), 0.0)
            assertTrue(
                "Seed $seed rates: ${playback.settledRates.min()}..${playback.settledRates.max()}",
                playback.settledRates.all { it == 48_000 },
            )
        }
    }

    @Test
    fun briefCompressedBurstsStillAllowTheHardwareClockToBeLearned() {
        for (drift in listOf(-0.0076, 0.0076)) {
            MediaClockTrim.learnedTrim = 0.0
            val playback = BatchedPlayback(bufferMillis = 300, drift = drift)

            playback.run(seconds = 300)

            assertEquals("Drift $drift", 0, playback.settledUnderruns)
            assertEquals("Drift $drift, final rate ${playback.settledRates.last()}",
                48_000 / (1 + drift), playback.settledRates.average(), 25.0)
            assertEquals(1 / (1 + drift) - 1, MediaClockTrim.learnedTrim, 0.0005)
        }
    }

    /** Models bounded PCM writes and compressed bursts, including the brief backlog inside each burst. */
    private class BatchedPlayback(
        bufferMillis: Int,
        private val drift: Double = 0.0,
        seed: Int = 7,
    ) {
        private val random = Random(seed)
        private val targetFrames = 48L * bufferMillis
        private val capacityFrames = MediaAudioBuffer.plan(true, 48_000, 2, 4096, bufferMillis)
            .trackBufferBytes / 4L
        private val trim = MediaClockTrim(48_000, targetFrames)
        private var queuedFrames = maxOf(targetFrames, 19_200L).toDouble()
        private var compressedFrames = 0L
        private var playing = true
        private var restartFrames = targetFrames

        var underruns = 0
            private set

        var settledUnderruns = 0
            private set

        val settledRates = mutableListOf<Int>()

        fun run(seconds: Int) {
            var nextBurstMillis = 400.0
            var burstNumber = 1

            for (elapsedMillis in 0 until seconds * 1000) {
                if (playing) {
                    queuedFrames -= trim.rate * (1 + drift) / 1000
                    if (queuedFrames < 0) {
                        queuedFrames = 0.0
                        playing = false
                        restartFrames = minOf(restartFrames + 4800, targetFrames + 28_800)
                        trim.restart(elapsedMillis * 1_000_000L)
                        underruns++
                        if (elapsedMillis >= 60_000) settledUnderruns++
                    }
                }

                if (elapsedMillis >= nextBurstMillis) {
                    compressedFrames += 19_200
                    burstNumber++
                    nextBurstMillis = burstNumber * 400 + (random.nextDouble() - 0.5) * 60
                }

                while (compressedFrames >= 1024 && queuedFrames + 1024 <= capacityFrames) {
                    compressedFrames -= 1024
                    queuedFrames += 1024
                    val nowNanos = elapsedMillis * 1_000_000L
                    if (playing) {
                        trim.sample(queuedFrames.toLong(), nowNanos, compressedFrames)
                        trim.rateDue(nowNanos)
                    } else if (queuedFrames >= restartFrames) {
                        playing = true
                        trim.onPlaybackStarted(nowNanos)
                    }
                }

                if (elapsedMillis >= 60_000 && elapsedMillis % 1000 == 0) {
                    settledRates += trim.rate
                }
            }
        }
    }
}
