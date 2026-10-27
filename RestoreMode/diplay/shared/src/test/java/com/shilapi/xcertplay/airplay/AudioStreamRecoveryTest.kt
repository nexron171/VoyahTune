package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamRecoveryTest {
    private val peer = InetAddress.getByName("127.0.0.1")
    private val key = ByteArray(32) { (it + 11).toByte() }

    private fun wire(sequence: Int): ByteArray {
        val header =
            ByteBuffer.allocate(12)
                .order(ByteOrder.BIG_ENDIAN)
                .put(0x80.toByte())
                .put(0x60)
                .putShort(sequence.toShort())
                .putInt(sequence * 1024)
                .putInt(7)
                .array()
        val shortNonce =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence.toLong()).array()
        val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
        return header +
            AirPlayCrypto.chachaSeal(
                key,
                nonce,
                byteArrayOf(sequence.toByte()),
                header.copyOfRange(4, 12),
            ) +
            shortNonce
    }

    private fun send(socket: DatagramSocket, port: Int, bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, peer, port))
    }

    private class Receiver : AudioStream.Listener {
        val delivered = LinkedBlockingQueue<Int>()
        val invalid = CountDownLatch(1)

        override fun onRtp(rtp: ByteArray, sample: Int) {
            delivered += ((rtp[2].toInt() and 0xff) shl 8) or (rtp[3].toInt() and 0xff)
        }

        override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
            if (error != null) invalid.countDown()
        }
    }

    @Test
    fun anEncryptedResendRestoresTheSongBeforeLaterPacketsAreDelivered() {
        val reports = CopyOnWriteArrayList<String>()
        val confirmed = CountDownLatch(1)
        val receiver = Receiver()
        DatagramSocket(0, peer).use { phone ->
            phone.soTimeout = 2000
            val stream =
                AudioStream(
                    key,
                    onDiagnostic = {
                        reports += it
                        if (it.contains("resend confirmed")) confirmed.countDown()
                    },
                    expectedPeer = peer,
                    recoveryBudgetMillis = { 1000 },
                    remoteControlPort = phone.localPort,
                )
            try {
                val (dataPort, controlPort) = stream.listen(receiver)
                send(phone, dataPort, wire(100))
                assertEquals(100, receiver.delivered.poll(2, TimeUnit.SECONDS))
                send(phone, dataPort, wire(102))
                val request = DatagramPacket(ByteArray(64), 64)
                phone.receive(request)
                assertEquals(0xd5, request.data[1].toInt() and 0xff)
                assertEquals(101, ByteBuffer.wrap(request.data, 4, 2).short.toInt() and 0xffff)
                assertNull(receiver.delivered.poll())
                send(
                    phone,
                    controlPort,
                    byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 1) + wire(101),
                )
                assertEquals(101, receiver.delivered.poll(2, TimeUnit.SECONDS))
                assertEquals(102, receiver.delivered.poll(2, TimeUnit.SECONDS))
                assertTrue(confirmed.await(2, TimeUnit.SECONDS))
            } finally {
                stream.close()
            }
            assertTrue(reports.toString(), reports.any { it.contains("resend confirmed") })
        }
    }

    @Test
    fun aCorruptResponseCannotFillTheMissingPacket() {
        val receiver = Receiver()
        DatagramSocket(0, peer).use { phone ->
            phone.soTimeout = 2000
            val stream =
                AudioStream(
                    key,
                    expectedPeer = peer,
                    recoveryBudgetMillis = { 1000 },
                    remoteControlPort = phone.localPort,
                )
            try {
                val (dataPort, controlPort) = stream.listen(receiver)
                send(phone, dataPort, wire(10))
                assertNotNull(receiver.delivered.poll(2, TimeUnit.SECONDS))
                send(phone, dataPort, wire(12))
                phone.receive(DatagramPacket(ByteArray(64), 64))
                val corrupt = wire(11).also { it[12] = (it[12].toInt() xor 1).toByte() }
                send(phone, controlPort, byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 1) + corrupt)
                assertTrue(receiver.invalid.await(2, TimeUnit.SECONDS))
                assertNull(receiver.delivered.poll())
                send(phone, controlPort, byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 2) + wire(11))
                assertEquals(11, receiver.delivered.poll(2, TimeUnit.SECONDS))
                assertEquals(12, receiver.delivered.poll(2, TimeUnit.SECONDS))
            } finally {
                stream.close()
            }
        }
    }

    @Test
    fun theControlEndpointCanBeLearnedFromTheCurrentPhone() {
        val receiver = Receiver()
        val endpointKnown = CountDownLatch(1)
        DatagramSocket(0, peer).use { phone ->
            phone.soTimeout = 2000
            val stream =
                AudioStream(
                    key,
                    expectedPeer = peer,
                    recoveryBudgetMillis = { 1000 },
                    onDiagnostic = {
                        if (it.contains("control available")) endpointKnown.countDown()
                    },
                )
            try {
                val (dataPort, controlPort) = stream.listen(receiver)
                val timing =
                    ByteArray(20).also {
                        it[0] = 0x80.toByte()
                        it[1] = 0xd4.toByte()
                    }
                send(phone, controlPort, timing)
                assertTrue(endpointKnown.await(2, TimeUnit.SECONDS))
                send(phone, dataPort, wire(20))
                assertNotNull(receiver.delivered.poll(2, TimeUnit.SECONDS))
                send(phone, dataPort, wire(22))
                val request = DatagramPacket(ByteArray(64), 64)
                phone.receive(request)
                assertEquals(0xd5, request.data[1].toInt() and 0xff)
            } finally {
                stream.close()
            }
        }
    }

    @Test
    fun aRetiredStreamCannotDeliverItsLateResponse() {
        val receiver = Receiver()
        val current = AtomicBoolean(true)
        DatagramSocket(0, peer).use { phone ->
            phone.soTimeout = 2000
            val stream =
                AudioStream(
                    key,
                    expectedPeer = peer,
                    recoveryBudgetMillis = { 1000 },
                    remoteControlPort = phone.localPort,
                    isCurrent = current::get,
                )
            try {
                val (dataPort, controlPort) = stream.listen(receiver)
                send(phone, dataPort, wire(30))
                assertNotNull(receiver.delivered.poll(2, TimeUnit.SECONDS))
                send(phone, dataPort, wire(32))
                phone.receive(DatagramPacket(ByteArray(64), 64))
                current.set(false)
                send(phone, controlPort, byteArrayOf(0x80.toByte(), 0xd6.toByte(), 0, 1) + wire(31))
                assertNull(receiver.delivered.poll(100, TimeUnit.MILLISECONDS))
            } finally {
                stream.close()
            }
        }
    }

    @Test
    fun streamsWithoutMusicRecoveryNeverWaitForMissingPackets() {
        val receiver = Receiver()
        DatagramSocket(0, peer).use { phone ->
            val stream = AudioStream(key, expectedPeer = peer)
            try {
                val (dataPort, _) = stream.listen(receiver)
                send(phone, dataPort, wire(40))
                send(phone, dataPort, wire(42))
                assertEquals(40, receiver.delivered.poll(2, TimeUnit.SECONDS))
                assertEquals(42, receiver.delivered.poll(2, TimeUnit.SECONDS))
            } finally {
                stream.close()
            }
        }
    }
}
