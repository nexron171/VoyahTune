package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPacketRecoveryTest {
    private val requests = mutableListOf<Pair<Int, Int>>()

    private fun recovery(budgetMillis: Long = 1000) =
        AudioPacketRecovery(
            { budgetMillis },
            { first, count ->
                requests += first to count
                true
            },
        )

    private fun packet(
        sequence: Int,
        source: Int = 7,
        timestamp: Int = sequence * 1024,
    ): ByteArray =
        ByteArray(16).also {
            it[0] = 0x80.toByte()
            it[1] = 0x60
            it[2] = (sequence shr 8).toByte()
            it[3] = sequence.toByte()
            repeat(4) { index -> it[4 + index] = (timestamp ushr (24 - index * 8)).toByte() }
            it[11] = source.toByte()
            it[12] = sequence.toByte()
        }

    private fun sequences(packets: List<ByteArray>): List<Int> = packets.map {
        ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff)
    }

    @Test
    fun reorderedPacketsAreDecodedInOrderWithoutRequestingAResend() {
        val recovery = recovery()
        recovery.offer(packet(100), 0)
        assertEquals(listOf(100), sequences(recovery.drain(0)))
        recovery.offer(packet(102), 1_000_000)
        assertTrue(recovery.drain(1_000_000).isEmpty())
        recovery.offer(packet(101), 10_000_000)
        assertEquals(listOf(101, 102), sequences(recovery.drain(10_000_000)))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aMissingRangeIsRequestedAndRestoredBeforeItsDeadline() {
        val recovery = recovery()
        recovery.offer(packet(10), 0)
        recovery.drain(0)
        recovery.offer(packet(13), 1_000_000)
        recovery.drain(1_000_000)
        assertTrue(recovery.drain(25_000_000).isEmpty())
        assertEquals(listOf(11 to 2), requests)
        recovery.offer(packet(12), 50_000_000)
        recovery.offer(packet(11), 60_000_000)
        assertEquals(listOf(11, 12, 13), sequences(recovery.drain(60_000_000)))
        assertEquals(2L, recovery.recoveredPackets)
        assertEquals(0L, recovery.missingPackets)
    }

    @Test
    fun expiredPacketsAreNotReinsertedIntoAlreadyDeliveredAudio() {
        val recovery = recovery(200)
        recovery.offer(packet(10), 0)
        recovery.drain(0)
        recovery.offer(packet(12), 0)
        recovery.drain(0)
        assertEquals(listOf(12), sequences(recovery.drain(121_000_000)))
        recovery.offer(packet(11), 130_000_000)
        assertTrue(recovery.drain(130_000_000).isEmpty())
        assertEquals(1L, recovery.missingPackets)
        assertEquals(1L, recovery.latePackets)
    }

    @Test
    fun retriesAreBoundedAndTheStreamNeverWaitsForever() {
        val recovery = recovery()
        recovery.offer(packet(0), 0)
        recovery.drain(0)
        recovery.offer(packet(2), 0)
        recovery.drain(0)
        for (millis in 20..880 step 20) recovery.drain(millis * 1_000_000L)
        assertEquals(3, requests.size)
        assertEquals(listOf(2), sequences(recovery.drain(901_000_000)))
    }

    @Test
    fun aShortOutputBufferDoesNotWaitForAnImpossibleRoundTrip() {
        val recovery = recovery(50)
        recovery.offer(packet(20), 0)
        recovery.drain(0)
        recovery.offer(packet(22), 0)
        assertEquals(listOf(22), sequences(recovery.drain(0)))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun wrapDuplicatesAndForeignSourcesCannotCorruptTheQueue() {
        val recovery = recovery()
        recovery.offer(packet(65534), 0)
        recovery.drain(0)
        recovery.offer(packet(0, timestamp = 65536 * 1024), 0)
        recovery.offer(packet(0, timestamp = 65536 * 1024), 0)
        recovery.offer(packet(65535, source = 8), 0)
        recovery.offer(packet(65535), 0)
        assertEquals(listOf(65535, 0), sequences(recovery.drain(0)))
        assertEquals(1L, recovery.duplicatePackets)
        assertEquals(1L, recovery.rejectedPackets)
    }

    @Test
    fun malformedPacketsDoNotStartAStream() {
        val recovery = recovery()
        recovery.offer(ByteArray(2), 0)
        recovery.offer(ByteArray(20), 0)
        assertTrue(recovery.drain(0).isEmpty())
        assertEquals(2L, recovery.rejectedPackets)
    }

    @Test
    fun aResponseAfterTheDeadlineIsLateEvenBeforeTheNextTimerTick() {
        val recovery = recovery(200)
        recovery.offer(packet(1), 0)
        recovery.drain(0)
        recovery.offer(packet(3), 0)
        recovery.drain(0)
        recovery.drain(25_000_000)
        recovery.offer(packet(2), 121_000_000)
        assertEquals(listOf(3), sequences(recovery.drain(121_000_000)))
        assertEquals(0L, recovery.recoveredPackets)
        assertEquals(1L, recovery.latePackets)
    }

    @Test
    fun aLongRadioGapCanResumeBeyondTheBoundedReorderWindow() {
        val recovery = recovery()
        recovery.offer(packet(10), 0)
        recovery.drain(0)
        recovery.offer(packet(700), 12_000_000_000L)
        assertEquals(listOf(700), sequences(recovery.drain(12_000_000_000L)))
        assertEquals(689L, recovery.missingPackets)
    }

    @Test
    fun appleResendMessagesPreserveTheOriginalEncryptedRtp() {
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0xd5.toByte(), 0, 2, 0xff.toByte(), 0xfe.toByte(), 0, 3),
            AudioResendPacket.request(2, 65534, 3),
        )
        val wire =
            ByteArray(40).also {
                it[0] = 0x80.toByte()
                it[1] = 0x60
            }
        val response = byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 2) + wire
        assertArrayEquals(wire, AudioResendPacket.audioResponse(response))
    }

    @Test
    fun aReplayWithAChangedSequenceCannotFillAnUnrelatedTimestampGap() {
        val recovery = recovery()
        recovery.offer(packet(100), 0)
        recovery.drain(0)
        recovery.offer(packet(102), 0)
        recovery.drain(0)
        recovery.drain(25_000_000)
        recovery.offer(packet(101, timestamp = 102 * 1024), 40_000_000)
        assertTrue(recovery.drain(40_000_000).isEmpty())
        assertEquals(0L, recovery.recoveredPackets)
        recovery.offer(packet(101), 50_000_000)
        assertEquals(listOf(101, 102), sequences(recovery.drain(50_000_000)))
        assertEquals(1L, recovery.recoveredPackets)
    }
}
