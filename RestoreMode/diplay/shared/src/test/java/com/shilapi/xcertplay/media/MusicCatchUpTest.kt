package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCatchUpTest {
    private val second = 1_000_000_000L
    private val target = 14_400L // 300 ms

    /** Samples [level] every 100 ms from [fromS] to [toS] and returns the first skip and when it came. */
    private fun MusicCatchUp.run(fromS: Int, toS: Int, level: Long): Pair<Long, Long>? {
        for (step in fromS * 10 until toS * 10) {
            val nowNs = step * 100_000_000L
            sample(level, nowNs)
            framesToSkip(nowNs).takeIf { it > 0 }?.let { return it to nowNs }
        }
        return null
    }

    @Test fun lateMusicPlaysAsACushionUntilTheConnectionIsCalm() {
        val catchUp = MusicCatchUp(target, 48_000)
        catchUp.stalled(0)
        // A stall left 0.9 s more than the target queued.
        val (frames, atNs) = catchUp.run(0, 60, target + 43_200)!!
        assertEquals(30 * second, atNs)
        // Exactly the excess: the buffer is left at the target, as full as a normal start.
        assertEquals(43_200L, frames)
    }

    @Test fun aNormalBufferIsNeverSkipped() {
        val catchUp = MusicCatchUp(target, 48_000)
        // The level between bursts swings around the target; its low is below it.
        for (step in 0 until 6000) {
            val nowNs = step * 100_000_000L
            catchUp.sample(if (step % 4 == 0) target + 19_200 else target - 4_800, nowNs)
            assertEquals(0L, catchUp.framesToSkip(nowNs))
        }
    }

    @Test fun onlyTheLowestLevelCounts() {
        val catchUp = MusicCatchUp(target, 48_000)
        for (step in 0 until 400) {
            val nowNs = step * 100_000_000L
            // Bursts peak 1 s above the target, but the low before each is only 250 ms above it.
            catchUp.sample(if (step % 4 == 0) target + 48_000 else target + 12_000, nowNs)
            val skip = catchUp.framesToSkip(nowNs)
            if (skip > 0) { assertEquals(12_000L, skip); return }
        }
        throw AssertionError("no skip")
    }

    @Test fun aSmallExcessIsNotWorthAJump() {
        val catchUp = MusicCatchUp(target, 48_000)
        assertEquals(null, catchUp.run(0, 600, target + 9_000))
    }

    @Test fun aStallRestartsTheCalm() {
        val catchUp = MusicCatchUp(target, 48_000)
        assertEquals(null, catchUp.run(0, 25, target + 43_200))
        catchUp.stalled(25 * second)
        assertEquals(55 * second, catchUp.run(25, 90, target + 43_200)!!.second)
    }

    @Test fun aLinkThatKeepsDroppingKeepsItsCushionLonger() {
        val catchUp = MusicCatchUp(target, 48_000)
        assertEquals(30 * second, catchUp.run(0, 60, target + 43_200)!!.second)
        // A minute after the skip the link stalls again: the next skip waits a minute.
        catchUp.stalled(90 * second)
        assertEquals(60 * second, catchUp.calmNs)
        assertEquals(150 * second, catchUp.run(90, 300, target + 43_200)!!.second)
        catchUp.stalled(200 * second)
        catchUp.stalled(210 * second)
        catchUp.stalled(220 * second)
        catchUp.stalled(230 * second)
        assertEquals(MusicCatchUp.MAX_CALM_NS, catchUp.calmNs)
    }

    @Test fun aStallLongAfterASkipDoesNotLengthenTheCalm() {
        val catchUp = MusicCatchUp(target, 48_000)
        assertEquals(30 * second, catchUp.run(0, 60, target + 43_200)!!.second)
        catchUp.stalled(300 * second)
        assertEquals(MusicCatchUp.MIN_CALM_NS, catchUp.calmNs)
    }

    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, s -> bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s shr 8).toByte() }
    }

    private fun samples(bytes: ByteArray, from: Int, to: Int) =
        (from until to step 2).map { (bytes[it].toInt() and 0xff) or (bytes[it + 1].toInt() shl 8) }.toIntArray()

    @Test fun aSkipFadesOutDropsAndFadesBackIn() {
        val skip = PcmSkip(channels = 1, fadeFrames = 4)
        assertFalse(skip.active)
        skip.start(3)
        assertTrue(skip.active)
        val chunk = pcm(800, 800, 800, 800, 9, 9, 9, 800, 800, 800, 800, 800)
        skip.process(chunk, 0, chunk.size)
        // Four frames fade out to silence, three are dropped, four fade back in.
        assertEquals(8, skip.headBytes)
        assertArrayEquals(intArrayOf(600, 400, 200, 0), samples(chunk, 0, 8))
        assertEquals(14, skip.tailOffset)
        assertArrayEquals(intArrayOf(200, 400, 600, 800, 800), samples(chunk, 14, 24))
        assertFalse(skip.active)
    }

    @Test fun aSkipLongerThanAChunkDropsWholeChunks() {
        val skip = PcmSkip(channels = 2, fadeFrames = 2)
        skip.start(5)
        val first = pcm(-800, -800, -800, -800, 1, 1, 1, 1)
        skip.process(first, 0, first.size)
        assertEquals(8, skip.headBytes)
        assertArrayEquals(intArrayOf(-400, -400, 0, 0), samples(first, 0, 8))
        assertEquals(first.size, skip.tailOffset)
        val second = pcm(1, 1, 1, 1, 1, 1, 800, 800, 800, 800)
        skip.process(second, 0, second.size)
        assertEquals(0, skip.headBytes)
        assertEquals(12, skip.tailOffset)
        assertArrayEquals(intArrayOf(400, 400, 800, 800), samples(second, 12, 20))
        assertFalse(skip.active)
    }
}
