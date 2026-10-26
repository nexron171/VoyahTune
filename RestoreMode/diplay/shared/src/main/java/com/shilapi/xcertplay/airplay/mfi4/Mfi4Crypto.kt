package com.shilapi.xcertplay.airplay.mfi4

import com.shilapi.xcertplay.airplay.AirPlayCrypto
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.BigIntegers
import java.security.MessageDigest
import java.security.SecureRandom

/** P-256, AES-CMAC, AES-128-CCM and the SIGMA-I key schedule of MFi 4.0 (ported from LIVI keys.rs). */
object Mfi4Crypto {
    const val POINT = 33
    const val TAG = 16
    const val SIGMA: Byte = 0x01
    private const val EXPANDED = 200
    private val EXTRACTION = "MFi 4.0 SIGMA-I Authentication Randomness Extraction".toByteArray(Charsets.US_ASCII)
    private val EXPANSION = "MFi 4.0 SIGMA-I Authentication Key Expansion".toByteArray(Charsets.US_ASCII)
    private val curve = CustomNamedCurves.getByName("secp256r1")
    val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
    internal val random = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }; digest()
    }
    fun sha1(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").run {
        parts.forEach { update(it) }; digest()
    }
    fun randomBytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }

    /** Decodes a 33-byte compressed or 65-byte uncompressed point and rejects points off the curve. */
    fun point(encoded: ByteArray): ECPoint {
        val point = try { domain.curve.decodePoint(encoded) } catch (e: IllegalArgumentException) {
            throw Mfi4Exception("not a P-256 point: ${e.message}")
        }
        if (point.isInfinity || !point.isValid) throw Mfi4Exception("not a P-256 point")
        return point.normalize()
    }

    class Ephemeral private constructor(private val key: ECPrivateKeyParameters, val public: ByteArray) {
        fun shared(peer: ByteArray): ByteArray {
            val agreement = ECDHBasicAgreement().apply { init(key) }
            val x = agreement.calculateAgreement(ECPublicKeyParameters(point(peer), domain))
            return BigIntegers.asUnsignedByteArray(32, x)
        }
        companion object {
            fun generate(): Ephemeral {
                val generator = ECKeyPairGenerator().apply { init(ECKeyGenerationParameters(domain, random)) }
                val pair = generator.generateKeyPair()
                val public = (pair.public as ECPublicKeyParameters).q.getEncoded(true)
                return Ephemeral(pair.private as ECPrivateKeyParameters, public)
            }
        }
    }

    fun cmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = CMac(AESEngine.newInstance()).apply { init(KeyParameter(key)) }
        mac.update(data, 0, data.size)
        return ByteArray(16).also { mac.doFinal(it, 0) }
    }

    /** One direction of the CCM-protected exchange; the 12-byte nonce counts up after every message. */
    class Channel internal constructor(private val key: ByteArray, internal val nonce: ByteArray) {
        internal fun step() {
            for (i in nonce.indices.reversed()) {
                nonce[i] = (nonce[i] + 1).toByte()
                if (nonce[i].toInt() != 0) break
            }
        }
        fun seal(aad: ByteArray, plain: ByteArray): ByteArray = ccm(true, aad, plain)!!.also { step() }
        fun open(aad: ByteArray, sealed: ByteArray): ByteArray? {
            if (sealed.size < TAG) return null
            return ccm(false, aad, sealed)?.also { step() }
        }
        private fun ccm(encrypt: Boolean, aad: ByteArray, data: ByteArray): ByteArray? {
            val cipher = CCMBlockCipher.newInstance(AESEngine.newInstance())
            cipher.init(encrypt, AEADParameters(KeyParameter(key), TAG * 8, nonce.copyOf(), aad))
            val out = ByteArray(cipher.getOutputSize(data.size))
            return try {
                var n = cipher.processBytes(data, 0, data.size, out, 0)
                n += cipher.doFinal(out, n)
                if (n == out.size) out else out.copyOf(n)
            } catch (_: InvalidCipherTextException) { null }
        }
    }

    class Keys(
        val start: Channel, val phoneCert: Channel, val fromPhone: Channel, val finish: Channel,
        val ourCert: Channel, val ours: Channel, val phoneMac: ByteArray, val ourMac: ByteArray,
    )

    fun derive(shared: ByteArray, context: ByteArray): Keys {
        val salt = sha256(EXTRACTION).copyOf(16)
        val kdk = cmac(salt, shared)
        val bits = (EXPANDED * 8).let { byteArrayOf((it ushr 24).toByte(), (it ushr 16).toByte(), (it ushr 8).toByte(), it.toByte()) }
        val material = java.io.ByteArrayOutputStream()
        var block = 1
        while (material.size() < EXPANDED) {
            val counter = byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte())
            material.write(cmac(kdk, counter + EXPANSION + byteArrayOf(0) + context + bits))
            block++
        }
        val m = material.toByteArray()
        fun channel(keyAt: Int, nonceAt: Int) = Channel(m.copyOfRange(keyAt, keyAt + 16), m.copyOfRange(nonceAt, nonceAt + 12))
        return Keys(start = channel(0, 16), phoneMac = m.copyOfRange(28, 44), phoneCert = channel(44, 60),
            fromPhone = channel(72, 88), finish = channel(100, 116), ourMac = m.copyOfRange(128, 144),
            ourCert = channel(144, 160), ours = channel(172, 188))
    }

    fun signedData(ours: ByteArray, phone: ByteArray, mac: ByteArray) = byteArrayOf(SIGMA) + ours + phone + mac

    /** The two proofs that bind a pair-verify run to MFi4: (the phone's, ours). */
    fun transcriptProofs(shared: ByteArray, transcript: ByteArray): Pair<ByteArray, ByteArray> {
        fun proof(salt: String, info: String): ByteArray {
            val key = AirPlayCrypto.hkdfSha512(shared, salt.toByteArray(Charsets.US_ASCII), info.toByteArray(Charsets.US_ASCII), 32)
            val mac = HMac(SHA256Digest()).apply { init(KeyParameter(key)); update(transcript, 0, transcript.size) }
            return ByteArray(32).also { mac.doFinal(it, 0) }
        }
        return proof("MFi4-KT1-Salt", "MFi4-KT1-Info") to proof("MFi4-KT2-Salt", "MFi4-KT2-Info")
    }
}
