package com.shilapi.xcertplay.airplay.mfi4

import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.util.BigIntegers
import org.junit.Assert.*
import org.junit.Test

/** The compressed-to-DER rebuild and leaf signature check (LIVI cert.rs). */
class Mfi4CertificateTest {
    private val pair = ECKeyPairGenerator().apply {
        init(ECKeyGenerationParameters(Mfi4Crypto.domain, Mfi4Crypto.random))
    }.generateKeyPair()
    private val compressedKey = (pair.public as ECPublicKeyParameters).q.getEncoded(true)

    private fun leaf(kind: Int = 0xA0, property: Byte = 0x03): ByteArray =
        byteArrayOf(kind.toByte()) + "IPHONE01".toByteArray() + "20250101000000".toByteArray() +
            "20450101000000".toByteArray() + compressedKey + ByteArray(32).also { it[31] = property } +
            ByteArray(20) { 7 } + byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 1)

    @Test fun aLeafUnpacksToADerThatVerifiesTheDeviceSignature() {
        val cert = Mfi4Certificate.unpack(leaf())
        assertTrue(cert.leaf)
        assertEquals(0x03.toByte(), cert.property!!.last())
        assertEquals(65, cert.point.size)
        val data = "signed-by-phone".toByteArray()
        val digest = Mfi4Crypto.sha256(data)
        val (r, s) = ECDSASigner().apply { init(true, pair.private as ECPrivateKeyParameters) }.generateSignature(digest)
        val signature = BigIntegers.asUnsignedByteArray(32, r) + BigIntegers.asUnsignedByteArray(32, s)
        assertTrue(cert.signed(data, signature))
        assertFalse(cert.signed("other".toByteArray(), signature))
    }

    @Test fun trailingBytesAndUnknownKindsAreRejected() {
        try { Mfi4Certificate.unpack(leaf() + byteArrayOf(0)); fail() } catch (_: Mfi4Exception) {}
        try { Mfi4Certificate.unpack(leaf(kind = 0xB0)); fail() } catch (_: Mfi4Exception) {}
    }
}
