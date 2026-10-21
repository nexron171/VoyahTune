package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class MediaClockTrimTest {
    @Before fun forgetLearnedTrim() { MediaClockTrim.learnedTrim = 0.0 }
    @After fun cleanUp() { MediaClockTrim.learnedTrim = 0.0 }

    /**
     * A head unit whose audio clock runs [drift] fast: the iPhone sends 352-frame packets at 48 kHz, with
     * gaps of 50–150 ms followed by a catch-up burst; the head unit plays 1024-frame periods.
     */
    private class Drive(val drift: Double, val trimmed: Boolean, seed: Int = 7) {
        val target = 14_400L // 300 ms at 48 kHz, as #610's music track started
        val trim = MediaClockTrim(48_000, target)
        val random = Random(seed)
        var queued = target.toDouble()
        var starved = 0
        val rates = mutableListOf<Int>()

        fun run(seconds: Int) {
            var rate = 48_000
            var nextPacketMs = 0.0
            var pending = 0
            var gapUntilMs = -1.0
            var played = 0.0
            for (ms in 0 until seconds * 1000) {
                val nowNs = ms * 1_000_000L
                if (random.nextDouble() < 0.4 / 1000) gapUntilMs = ms + 50 + random.nextDouble() * 100
                while (nextPacketMs <= ms) { pending += 352; nextPacketMs += 352 / 48.0 }
                if (ms >= gapUntilMs && pending > 0) {
                    if (trimmed) trim.sample(queued.toLong(), nowNs)
                    queued += pending
                    pending = 0
                    if (trimmed) {
                        trim.sample(queued.toLong(), nowNs)
                        trim.rateDue(nowNs)?.let { rate = it; rates += it }
                    }
                }
                played += rate * (1 + drift) / 1000.0
                while (played >= 1024) {
                    played -= 1024
                    if (queued >= 1024) queued -= 1024 else { starved++; queued = target.toDouble() }
                }
            }
        }
    }

    /**
     * Wireless CarPlay: the iPhone sends about 0.4 s of music at once, every 0.4 s by its clock give or take
     * 30 ms (#807, #976), and now and then stalls for up to 0.9 s and then sends what it held back.
     */
    private class BurstDrive(targetMs: Int, seed: Int = 7) {
        val target = 48L * targetMs
        val trim = MediaClockTrim(48_000, target)
        val random = Random(seed)
        var queued = maxOf(target, 19_200L).toDouble()
        val rates = mutableListOf<Int>()

        fun run(seconds: Int) {
            var rate = 48_000
            var burst = 1
            var nextBurstMs = 400.0
            var played = 0.0
            var owed = 0.0
            for (ms in 0 until seconds * 1000) {
                val nowNs = ms * 1_000_000L
                owed += 48.0
                if (ms >= nextBurstMs) {
                    trim.sample(queued.toLong(), nowNs)
                    queued += owed
                    owed = 0.0
                    burst++
                    val stall = if (random.nextDouble() < 0.03) random.nextDouble() * 500 else 0.0
                    nextBurstMs = burst * 400 + (random.nextDouble() - 0.5) * 60 + stall
                    trim.sample(queued.toLong(), nowNs)
                    trim.rateDue(nowNs)?.let { rate = it; rates += it }
                }
                played += rate / 1000.0
                while (played >= 1024) {
                    played -= 1024
                    // A rebuffer: the renderer pauses until the next burst and restarts the measurement.
                    if (queued >= 1024) queued -= 1024 else { queued = 0.0; trim.restart(nowNs) }
                }
            }
        }
    }

    @Test fun overWiFiTheRateNeverMoves() {
        for (targetMs in listOf(300, 500, 1000)) for (seed in 1..3) {
            val drive = BurstDrive(targetMs, seed)
            drive.run(600)
            // No resampling at all: the pitch is exactly the iPhone's.
            assertEquals("$targetMs ms seed $seed", emptyList<Int>(), drive.rates)
            assertEquals(0.0, MediaClockTrim.learnedTrim, 0.0)
        }
    }

    @Test fun anAccurateClockIsLeftAlone() {
        val drive = Drive(0.0, trimmed = true)
        drive.run(600)
        assertEquals(emptyList<Int>(), drive.rates)
        assertEquals(0, drive.starved)
    }

    @Test fun withoutTheTrimA076PercentFastHeadUnitRunsDryEveryMinuteOrSo() {
        val drive = Drive(0.0076, trimmed = false)
        drive.run(300)
        assertTrue("starved ${drive.starved}", drive.starved >= 3)
    }

    @Test fun aFastHeadUnitIsTunedOnceAndThenHolds() {
        val drive = Drive(0.0076, trimmed = true)
        drive.run(300)
        // 48000 / 1.0076: the track plays that much slower, so the fast clock consumes 48 kHz and music is in tune.
        assertEquals(47_638.0, drive.trim.rate.toDouble(), 15.0)
        assertEquals(-0.0075, MediaClockTrim.learnedTrim, 0.0003)
        // A glide, never a jump: 0.9 cents a second, and an update covers at most 2 s.
        drive.rates.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", abs(a - b) <= 48) }
        // Measuring takes 20 s, so the first song may refill once; then the buffer holds.
        assertTrue("starved ${drive.starved}", drive.starved <= 1)
        assertTrue("rates ${drive.rates.size}", drive.rates.last() == drive.trim.rate)
    }

    @Test fun aSlowHeadUnitIsTunedTheOtherWay() {
        val drive = Drive(-0.0076, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertEquals(48_368.0, drive.trim.rate.toDouble(), 15.0)
    }

    @Test fun aSmallDriftThatStillEmptiesTheBufferIsTunedToo() {
        // 0.05 % fast: 300 ms would last 10 minutes.
        val drive = Drive(0.0005, trimmed = true)
        drive.run(600)
        assertEquals(47_976.0, drive.trim.rate.toDouble(), 6.0)
        assertEquals(0, drive.starved)
    }

    @Test fun aNewStreamStartsFromTheLearnedTrim() {
        MediaClockTrim.learnedTrim = -0.0075
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(14_400, 0)
        assertEquals(47_640, trim.rateDue(0))
    }

    @Test fun updatesComeAtMostOnceASecond() {
        MediaClockTrim.learnedTrim = -0.0075
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(14_400, 0)
        assertEquals(47_640, trim.rateDue(0))
        assertNull(trim.rateDue(500_000_000L))
    }

    /** A head unit 1 % fast, with a stall or skip every 30 s that moves the level by up to a second. */
    private fun driveFallingLevelWithJumps(trim: MediaClockTrim, restartOnJump: Boolean) {
        var level = 30_000.0
        for (step in 0 until 600 * 10) {
            val nowNs = step * 100_000_000L
            if (step % 300 == 299) {
                level += 48_000.0 * ((step / 300) % 3 - 1)
                level = level.coerceAtLeast(14_400.0)
                if (restartOnJump) trim.restart(nowNs)
            }
            level += 4_800.0 - trim.rate * 1.01 / 10
            trim.sample(level.toLong(), nowNs)
            trim.rateDue(nowNs)
        }
    }

    @Test fun aStallOrSkipThatMovesTheLevelIsNotDrift() {
        val trim = MediaClockTrim(48_000, 14_400)
        driveFallingLevelWithJumps(trim, restartOnJump = true)
        // Only the slope between jumps counts: 48000 / 1.01.
        assertEquals(47_525.0, trim.rate.toDouble(), 30.0)
    }

    @Test fun aMinuteOfSlowWiFiIsNotDrift() {
        val trim = MediaClockTrim(48_000, 14_400)
        var level = 14_400.0
        for (step in 0 until 600 * 10) {
            val nowNs = step * 100_000_000L
            // The iPhone's audio arrives 0.1 % slow for a minute, then catches up for a minute, as a Galaxy S7 saw.
            level += when ((step / 600) % 2) { 0 -> -4.8; else -> 4.8 }
            trim.sample(level.toLong(), nowNs)
            trim.rateDue(nowNs)
        }
        assertEquals(48_000, trim.rate)
    }

    @Test fun theTrimStaysWithinOnePointFivePercent() {
        val trim = MediaClockTrim(48_000, 14_400)
        var level = 1_000_000.0
        for (step in 0 until 1800 * 10) {
            val nowNs = step * 100_000_000L
            level -= 480.0 // 10 % fast
            trim.sample(level.toLong(), nowNs)
            trim.rateDue(nowNs)
        }
        assertEquals(47_280, trim.rate)
    }

    @Test fun aPauseRestartsTheMeasurement() {
        val trim = MediaClockTrim(48_000, 14_400)
        // 15 s at 14,400, then 2 s with no write, then 15 s at 4,800: no slope within either stretch.
        for (step in 0..150) { trim.sample(14_400, step * 100_000_000L); trim.rateDue(step * 100_000_000L) }
        for (step in 170..320) { trim.sample(4_800, step * 100_000_000L); trim.rateDue(step * 100_000_000L) }
        assertEquals(48_000, trim.rate)
    }

    @Test fun aLargeCompressedQueueMustNotTrickClockControlIntoSlowingDown() {
        val trim = MediaClockTrim(48_000, 14_400)
        // The #806 recording had 150-192 pending AAC packets while Android's track writes
        // reported a small hardware PCM queue. That backlog is decoder congestion, not the head unit's clock.
        for (step in 0..600) {
            val nowNs = step * 100_000_000L
            trim.sample(4_800, nowNs, compressedFrames = 96 * 1024L + step * 200L)
            trim.rateDue(nowNs)
        }
        assertEquals(48_000, trim.rate)
        assertEquals(0.0, MediaClockTrim.learnedTrim, 0.0)
    }
}
