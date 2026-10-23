package com.shilapi.xcertplay.airplay

import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VehicleStateChannelTest {
    @Test fun fragmentedEncryptedRequestIsAcknowledgedAndResponseHasZeroReplyToken() {
        val inputKey = ByteArray(32) { 7 }
        val outputKey = ByteArray(32) { 11 }
        val received = CountDownLatch(1)
        val body = byteArrayOf(1, 2, 3)
        val channel = VehicleStateChannel(inputKey, outputKey, {
            assertArrayEquals(body, it)
            received.countDown()
        }, {})
        try {
            Socket(InetAddress.getLoopbackAddress(), channel.listen(InetAddress.getLoopbackAddress())).use { phone ->
                phone.soTimeout = 2000
                val token = 0x123456789abcdefL
                val packet = ByteBuffer.allocate(35).putInt(35).putInt(0x73796e63).array()
                ByteBuffer.wrap(packet).putLong(20, token)
                body.copyInto(packet, 32)
                val aad = byteArrayOf(35, 0)
                val encrypted = aad + AirPlayCrypto.chachaSeal(inputKey, AirPlayCrypto.nonce64(0), packet, aad)
                phone.getOutputStream().write(encrypted, 0, 7)
                phone.getOutputStream().write(encrypted, 7, encrypted.size - 7)
                assertTrue(received.await(2, TimeUnit.SECONDS))
                val input = DataInputStream(phone.getInputStream())
                fun read(counter: Long): ByteArray {
                    val header = ByteArray(2)
                    input.readFully(header)
                    val length = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
                    val ciphertext = ByteArray(length + 16)
                    input.readFully(ciphertext)
                    return AirPlayCrypto.chachaOpen(outputKey, AirPlayCrypto.nonce64(counter), ciphertext, header)
                }
                val ack = ByteBuffer.wrap(read(0))
                assertEquals(32, ack.getInt(0))
                assertEquals(0x72706c79, ack.getInt(4))
                assertEquals(token, ack.getLong(20))
                channel.send(body)
                val response = ByteBuffer.wrap(read(1))
                assertEquals(0x6173796e, response.getInt(4))
                assertEquals(0x636d6e64, response.getInt(16))
                assertEquals(0L, response.getLong(20))
                assertArrayEquals(body, response.array().copyOfRange(32, 35))
            }
        } finally {
            channel.close()
        }
    }
}
