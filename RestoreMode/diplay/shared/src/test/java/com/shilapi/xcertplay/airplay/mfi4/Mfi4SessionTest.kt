package com.shilapi.xcertplay.airplay.mfi4

import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test

/** Covers the ported MFi4 primitives and the session's early rejection paths (LIVI mfi4). */
class Mfi4SessionTest {
    private class Signer(val major: Int = 3) : MfiAuthenticator {
        override fun protocolMajor() = major
        override fun readCertificate(maximumOutputLength: Int) = "CERT".toByteArray()
        override fun signChallenge(challenge: ByteArray) = ByteArray(64)
    }
    private fun noTunnel(data: ByteArray): ByteArray = error("no tunnel expected")

    @Test fun wireMessageRoundTripsWithLengthPrefixedParameters() {
        val msg = Mfi4Message(Mfi4Wire.CLEAR, 0x5201).with(0, byteArrayOf(3)).with(3, Mfi4Wire.be16(0x1234))
        val bytes = msg.encode()
        assertArrayEquals(byteArrayOf(0, 0, 0, 17, 0x52, 0x01, 0, 5, 0, 0, 3, 0, 6, 0, 3, 0x12, 0x34), bytes)
        val back = Mfi4Wire.decode(bytes)
        assertEquals(0x1234, back.u16(3)); assertEquals(3, back.u8(0)); assertEquals(listOf(0, 3), back.ids())
    }

    @Test fun aLengthThatDoesNotFitIsRefused() {
        val bytes = Mfi4Message(Mfi4Wire.CLEAR, 1).with(0, byteArrayOf(1, 2)).encode()
        assertNull(Mfi4Wire.peek(bytes.copyOf(bytes.size - 1)))
        val broken = bytes.copyOf(); broken[7] = 9
        try { Mfi4Wire.decode(broken); fail() } catch (_: Mfi4Exception) {}
    }

    @Test fun cmacMatchesThePublishedAesExample() {
        val key = byteArrayOf(0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae.toByte(), 0xd2.toByte(), 0xa6.toByte(),
            0xab.toByte(), 0xf7.toByte(), 0x15, 0x88.toByte(), 0x09, 0xcf.toByte(), 0x4f, 0x3c)
        val msg = byteArrayOf(0x6b, 0xc1.toByte(), 0xbe.toByte(), 0xe2.toByte(), 0x2e, 0x40, 0x9f.toByte(), 0x96.toByte(),
            0xe9.toByte(), 0x3d, 0x7e, 0x11, 0x73, 0x93.toByte(), 0x17, 0x2a)
        val expected = byteArrayOf(0x07, 0x0a, 0x16, 0xb4.toByte(), 0x6b, 0x4d, 0x41, 0x44, 0xf7.toByte(),
            0x9b.toByte(), 0xdd.toByte(), 0x9d.toByte(), 0xd0.toByte(), 0x4a, 0x28, 0x7c)
        assertArrayEquals(expected, Mfi4Crypto.cmac(key, msg))
    }

    @Test fun bothEndsAgreeAndASealedChannelCountsItsNonce() {
        val a = Mfi4Crypto.Ephemeral.generate()
        val b = Mfi4Crypto.Ephemeral.generate()
        val shared = a.shared(b.public)
        assertArrayEquals(shared, b.shared(a.public)); assertEquals(32, shared.size)
        val ours = Mfi4Crypto.derive(shared, "ctx".toByteArray())
        val theirs = Mfi4Crypto.derive(shared, "ctx".toByteArray())
        val first = ours.ours.seal("aad".toByteArray(), "one".toByteArray())
        val second = ours.ours.seal("aad".toByteArray(), "one".toByteArray())
        assertFalse(first.contentEquals(second))
        assertArrayEquals("one".toByteArray(), theirs.ours.open("aad".toByteArray(), first))
        assertNull(theirs.ours.open("other".toByteArray(), second))
        assertArrayEquals("one".toByteArray(), theirs.ours.open("aad".toByteArray(), second))
    }

    @Test fun theNonceCarriesIntoTheNextByte() {
        val channel = Mfi4Crypto.Channel(ByteArray(16), byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte()))
        channel.step()
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0), channel.nonce)
    }

    @Test fun anInvalidPointIsRejected() {
        try { Mfi4Crypto.point(ByteArray(33)); fail() } catch (_: Mfi4Exception) {}
    }

    @Test fun setupNeedsAThreePointZeroIdentity() {
        val session = Mfi4Session()
        val setup = Mfi4Message(Mfi4Wire.CLEAR, 0x5100).with(0, byteArrayOf(2)).with(1, byteArrayOf(3))
            .with(2, byteArrayOf(1)).with(3, byteArrayOf(2)).encode()
        try { session.handle(setup, Signer(major = 2), ::noTunnel); fail() } catch (e: Mfi4Exception) {
            assertTrue(e.message!!.contains("3.0"))
        }
        assertFalse(session.proven)
    }

    @Test fun setupAnswersAndMovesOffTheClearStart() {
        val session = Mfi4Session()
        val setup = Mfi4Message(Mfi4Wire.CLEAR, 0x5100).with(0, byteArrayOf(2)).with(1, byteArrayOf(3))
            .with(2, byteArrayOf(1)).with(3, byteArrayOf(2)).with(4, byteArrayOf(1)).encode()
        val answer = Mfi4Wire.decode(session.handle(setup, Signer(), ::noTunnel))
        assertEquals(0x5201, answer.id); assertEquals(3, answer.u8(0))
        assertEquals(Mfi4Crypto.POINT, answer.param(4)!!.size)
    }

    @Test fun aMessageUnderAnUnknownStartFails() {
        val session = Mfi4Session()
        val stray = Mfi4Message(0x1234, 0x5111).encode()
        assertEquals(0x52FE, Mfi4Wire.decode(session.handle(stray, Signer(), ::noTunnel)).id)
    }
}
