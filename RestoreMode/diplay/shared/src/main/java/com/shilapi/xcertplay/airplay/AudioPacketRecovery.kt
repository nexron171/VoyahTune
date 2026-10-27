package com.shilapi.xcertplay.airplay

import java.util.TreeMap

/**
 * Orders authenticated RTP before decoding and spends only the available playout reserve on resend.
 */
internal class AudioPacketRecovery(
    private val recoveryBudgetMillis: () -> Long,
    private val requestResend: (firstSequence: Int, count: Int) -> Boolean,
) {
    private val packets = TreeMap<Long, ByteArray>()
    private var expectedSequence: Long? = null
    private var sourceIdentifier: Int? = null
    private var gapSequence: Long? = null
    private var gapDeadlineNanos = 0L
    private var nextRequestNanos = 0L
    private var requestAttempts = 0
    private var lastRequestNanos: Long? = null
    private var roundTripNanos = INITIAL_RETRY_NANOS
    private val requestedSequences = mutableSetOf<Long>()
    private var lastAcceptedNanos: Long? = null
    private var lastDeliveredTimestamp: Int? = null

    var duplicatePackets = 0L
        private set

    var latePackets = 0L
        private set

    var missingPackets = 0L
        private set

    var recoveredPackets = 0L
        private set

    var resendRequests = 0L
        private set

    var rejectedPackets = 0L
        private set

    fun offer(rtp: ByteArray, nowNanos: Long) {
        if (rtp.size < RTP_HEADER_BYTES || rtp[0].toInt() and 0xc0 != 0x80) {
            rejectedPackets++
            return
        }
        val source = readInt(rtp, 8)
        if (sourceIdentifier != null && sourceIdentifier != source) {
            rejectedPackets++
            return
        }
        sourceIdentifier = source
        val sequence = ((rtp[2].toInt() and 0xff) shl 8) or (rtp[3].toInt() and 0xff)
        expireGap(nowNanos)
        var expected = expectedSequence ?: sequence.toLong().also { expectedSequence = it }
        val extended = expected + (sequence - (expected.toInt() and 0xffff)).toShort().toLong()
        if (
            (extended in requestedSequences || extended == gapSequence) &&
                !timestampFitsGap(extended, rtp)
        ) {
            rejectedPackets++
            return
        }
        val previousAccepted = lastAcceptedNanos
        if (
            extended - expected >= MAX_PENDING_PACKETS &&
                packets.isEmpty() &&
                previousAccepted != null &&
                nowNanos - previousAccepted >= MAX_HOLD_MILLIS * 1_000_000L
        ) {
            missingPackets += extended - expected
            expected = extended
            expectedSequence = expected
            requestedSequences.clear()
            clearGap()
        }
        when {
            extended < expected -> latePackets++
            packets.containsKey(extended) -> duplicatePackets++
            extended - expected >= MAX_PENDING_PACKETS || packets.size >= MAX_PENDING_PACKETS ->
                rejectedPackets++
            else -> {
                packets[extended] = rtp
                lastAcceptedNanos = nowNanos
                if (requestedSequences.remove(extended)) {
                    recoveredPackets++
                    lastRequestNanos?.let { sent ->
                        val elapsed = (nowNanos - sent).coerceIn(MIN_RETRY_NANOS, MAX_RETRY_NANOS)
                        roundTripNanos = (roundTripNanos * 3 + elapsed) / 4
                    }
                }
            }
        }
    }

    fun drain(nowNanos: Long): List<ByteArray> {
        val ready = mutableListOf<ByteArray>()
        while (packets.isNotEmpty()) {
            val expected = expectedSequence ?: break
            val next = packets.remove(expected)
            if (next != null) {
                ready += next
                lastDeliveredTimestamp = readInt(next, 4)
                requestedSequences.remove(expected)
                expectedSequence = expected + 1
                if (gapSequence == expected) clearGap()
                continue
            }
            if (gapSequence != expected) {
                gapSequence = expected
                val budgetMillis =
                    (recoveryBudgetMillis() - PLAYOUT_GUARD_MILLIS).coerceIn(0L, MAX_HOLD_MILLIS)
                gapDeadlineNanos = nowNanos + budgetMillis * 1_000_000L
                nextRequestNanos = nowNanos + REORDER_WAIT_NANOS
                requestAttempts = 0
            }
            if (nowNanos >= gapDeadlineNanos) {
                val firstAvailable = packets.firstKey()
                missingPackets += firstAvailable - expected
                requestedSequences.removeAll { it < firstAvailable }
                expectedSequence = firstAvailable
                clearGap()
                continue
            }
            if (nowNanos >= nextRequestNanos && requestAttempts < MAX_REQUEST_ATTEMPTS) {
                val count =
                    minOf(packets.firstKey() - expected, MAX_REQUEST_PACKETS.toLong()).toInt()
                if (requestResend(expected.toInt() and 0xffff, count)) {
                    resendRequests++
                    requestAttempts++
                    lastRequestNanos = nowNanos
                    repeat(count) { requestedSequences += expected + it }
                }
                nextRequestNanos =
                    nowNanos + (roundTripNanos * 2).coerceIn(MIN_RETRY_NANOS, MAX_RETRY_NANOS)
            }
            break
        }
        return ready
    }

    fun diagnostic(): String =
        "pending=${packets.size} missing=$missingPackets recovered=$recoveredPackets " +
            "resendRequests=$resendRequests duplicates=$duplicatePackets late=$latePackets rejected=$rejectedPackets"

    private fun clearGap() {
        gapSequence = null
        lastRequestNanos = null
        requestAttempts = 0
    }

    private fun timestampFitsGap(sequence: Long, rtp: ByteArray): Boolean {
        val timestamp = readInt(rtp, 4)
        val lower =
            packets.lowerEntry(sequence)?.value?.let { readInt(it, 4) } ?: lastDeliveredTimestamp
        val upper = packets.higherEntry(sequence)?.value?.let { readInt(it, 4) }
        return (lower == null || timestamp - lower > 0) && (upper == null || upper - timestamp > 0)
    }

    private fun expireGap(nowNanos: Long) {
        val expected = gapSequence ?: return
        if (nowNanos < gapDeadlineNanos || packets.isEmpty()) return
        val firstAvailable = packets.firstKey()
        missingPackets += firstAvailable - expected
        requestedSequences.removeAll { it < firstAvailable }
        expectedSequence = firstAvailable
        clearGap()
    }

    companion object {
        private const val RTP_HEADER_BYTES = 12
        private const val MAX_PENDING_PACKETS = 512
        private const val MAX_REQUEST_PACKETS = 128
        private const val MAX_REQUEST_ATTEMPTS = 3
        private const val PLAYOUT_GUARD_MILLIS = 80L
        private const val MAX_HOLD_MILLIS = 900L
        private const val REORDER_WAIT_NANOS = 20_000_000L
        private const val INITIAL_RETRY_NANOS = 80_000_000L
        private const val MIN_RETRY_NANOS = 40_000_000L
        private const val MAX_RETRY_NANOS = 200_000_000L

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
    }
}

/** Apple's resend request is an eight-byte control message, not an RTCP Generic NACK. */
internal object AudioResendPacket {
    fun request(requestSequence: Int, firstSequence: Int, count: Int): ByteArray {
        require(count in 1..128)
        return byteArrayOf(
            0x80.toByte(),
            0xd5.toByte(),
            (requestSequence shr 8).toByte(),
            requestSequence.toByte(),
            (firstSequence shr 8).toByte(),
            firstSequence.toByte(),
            (count shr 8).toByte(),
            count.toByte(),
        )
    }

    fun audioResponse(datagram: ByteArray): ByteArray? {
        if (
            datagram.size < 40 ||
                datagram[0].toInt() and 0xc0 != 0x80 ||
                datagram[1].toInt() and 0x7f != 0x56 ||
                datagram[4].toInt() and 0xc0 != 0x80
        )
            return null
        return datagram.copyOfRange(4, datagram.size)
    }
}
