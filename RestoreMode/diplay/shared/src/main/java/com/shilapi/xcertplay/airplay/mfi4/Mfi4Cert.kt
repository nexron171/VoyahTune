package com.shilapi.xcertplay.airplay.mfi4

import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import java.math.BigInteger

/**
 * Apple's compressed accessory-attestation certificates, rebuilt to the DER the phone hashed
 * (ported from LIVI cert.rs). Without Apple's accessory root the chain is not verified; the
 * phone's leaf signature over the SIGMA-I data is always verified.
 */
class Mfi4Certificate internal constructor(
    val der: ByteArray, val leaf: Boolean, val point: ByteArray,
    val authority: ByteArray, val keyId: ByteArray, val property: ByteArray?,
) {
    /** ECDSA-P256-SHA256 over [data] with a raw 64-byte r||s signature. */
    fun signed(data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64) return false
        val verifier = ECDSASigner().apply { init(false, ECPublicKeyParameters(Mfi4Crypto.point(point), Mfi4Crypto.domain)) }
        return verifier.verifySignature(Mfi4Crypto.sha256(data),
            BigInteger(1, signature.copyOfRange(0, 32)), BigInteger(1, signature.copyOfRange(32, 64)))
    }

    companion object {
        private const val NAME_LEN = 8
        private const val TIME_LEN = 14
        const val PROPERTY_LEN = 32
        const val KEY_ID_LEN = 20
        private const val PLAN_ID_LEN = 36
        private const val WITH_PLAN_ID = 0x01
        private const val WITH_ACCESSORY_DATA = 0x02
        const val HELD_BY_APPLE_DEVICE: Byte = 0x03
        private val OID_COMMON_NAME = bytes(0x55, 0x04, 0x03)
        private val OID_UNIT = bytes(0x55, 0x04, 0x0b)
        private val OID_ORGANIZATION = bytes(0x55, 0x04, 0x0a)
        private val OID_STATE = bytes(0x55, 0x04, 0x08)
        private val OID_ECDSA_SHA256 = bytes(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02)
        private val OID_EC_PUBLIC_KEY = bytes(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01)
        private val OID_P256 = bytes(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07)
        private val OID_BASIC_CONSTRAINTS = bytes(0x55, 0x1d, 0x13)
        private val OID_KEY_USAGE = bytes(0x55, 0x1d, 0x0f)
        private val OID_SUBJECT_KEY_ID = bytes(0x55, 0x1d, 0x0e)
        private val OID_AUTHORITY_KEY_ID = bytes(0x55, 0x1d, 0x23)
        private val OID_APPLE_ACCESSORY = bytes(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x63, 0x64, 0x06, 0x47)
        private const val ORGANIZATION = "Apple Inc."
        private const val STATE = "California"
        private const val ROOT_PREFIX = "Apple Accessory Root CA - "
        private const val AUTHORITY_PREFIX = "Apple Accessory Host Attestation Authority - "
        private const val LEAF_UNIT = "Apple Accessory Host Attestations"

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        private class Reader(private val data: ByteArray) {
            var at = 0
            val left get() = data.size - at
            fun take(n: Int): ByteArray {
                if (n > left) throw Mfi4Exception("the certificate is cut short")
                return data.copyOfRange(at, at + n).also { at += n }
            }
            fun text(n: Int): String = take(n).let { raw ->
                String(raw, Charsets.UTF_8).also { if (!it.toByteArray(Charsets.UTF_8).contentEquals(raw)) throw Mfi4Exception("a name that is no text") }
            }
            fun sequence(): ByteArray {
                if (left < 2 || data[at] != 0x30.toByte() || (data[at + 1].toInt() and 0xff) >= 0x80)
                    throw Mfi4Exception("expected a short DER sequence")
                return take(2 + (data[at + 1].toInt() and 0xff))
            }
        }

        fun unpack(compressed: ByteArray): Mfi4Certificate {
            val r = Reader(compressed)
            val kind = r.take(1)[0].toInt() and 0xff
            val leaf = kind in 0xA0..0xA3
            if (!leaf && kind !in 0xA4..0xA5) throw Mfi4Exception("unknown certificate kind 0x${kind.toString(16)}")
            val issuer = r.text(NAME_LEN)
            val notBefore = r.text(TIME_LEN)
            val notAfter = r.text(TIME_LEN)
            val subject = if (leaf) null else r.text(NAME_LEN)
            val point = Mfi4Crypto.point(r.take(Mfi4Crypto.POINT)).getEncoded(false)
            val property = if (leaf) r.take(PROPERTY_LEN) else null
            val authority = r.take(KEY_ID_LEN)
            val planId = if (kind and WITH_PLAN_ID != 0) r.take(PLAN_ID_LEN) else null
            val signature = r.sequence()
            val accessoryData = if (leaf && kind and WITH_ACCESSORY_DATA != 0) r.sequence() else null
            if (r.left != 0) throw Mfi4Exception("${r.left} bytes past the certificate")

            val keyId = Mfi4Crypto.sha1(point)
            val (issuerName, subjectName) = if (subject == null) {
                listOf(rdn(OID_COMMON_NAME, AUTHORITY_PREFIX + issuer)) to
                    listOf(rdn(OID_COMMON_NAME, upperHex(Mfi4Crypto.sha256(point))), rdn(OID_UNIT, LEAF_UNIT))
            } else {
                listOf(rdn(OID_COMMON_NAME, ROOT_PREFIX + issuer)) to listOf(rdn(OID_COMMON_NAME, AUTHORITY_PREFIX + subject))
            }
            val extensions = ArrayList<ByteArray>()
            if (!leaf) extensions += extension(OID_BASIC_CONSTRAINTS, true, der(0x30, der(0x01, bytes(0xff))))
            extensions += extension(OID_AUTHORITY_KEY_ID, false, der(0x30, der(0x80, authority)))
            if (!leaf) {
                extensions += extension(OID_SUBJECT_KEY_ID, false, der(0x04, keyId))
                extensions += extension(OID_KEY_USAGE, true, der(0x03, bytes(0x01, 0x06)))
            }
            property?.let { extensions += extension(apple(1), true, it) }
            planId?.let { extensions += extension(apple(2), false, it) }
            accessoryData?.let { extensions += extension(apple(3), false, it) }

            val algorithm = der(0x30, der(0x06, OID_ECDSA_SHA256))
            val keyInfo = der(0x30, der(0x30, der(0x06, OID_EC_PUBLIC_KEY) + der(0x06, OID_P256)) + bitString(point))
            val tbs = der(0x30, der(0xa0, der(0x02, bytes(2))) + der(0x02, point.copyOf(KEY_ID_LEN)) + algorithm +
                withPlace(issuerName) + der(0x30, time(notBefore) + time(notAfter)) + withPlace(subjectName) +
                keyInfo + der(0xa3, der(0x30, extensions.fold(ByteArray(0)) { a, b -> a + b })))
            return Mfi4Certificate(der(0x30, tbs + algorithm + bitString(signature)), leaf, point, authority, keyId, property)
        }

        internal fun der(tag: Int, content: ByteArray): ByteArray {
            val n = content.size
            val length = when {
                n < 0x80 -> bytes(n)
                n < 0x100 -> bytes(0x81, n)
                else -> bytes(0x82, n shr 8, n and 0xff)
            }
            return byteArrayOf(tag.toByte()) + length + content
        }
        private fun bitString(content: ByteArray) = der(0x03, byteArrayOf(0) + content)
        private fun withPlace(entries: List<ByteArray>) =
            der(0x30, (entries + rdn(OID_ORGANIZATION, ORGANIZATION) + rdn(OID_STATE, STATE)).fold(ByteArray(0)) { a, b -> a + b })
        private fun rdn(oid: ByteArray, value: String) =
            der(0x31, der(0x30, der(0x06, oid) + der(0x0c, value.toByteArray(Charsets.UTF_8))))
        private fun extension(oid: ByteArray, critical: Boolean, value: ByteArray) =
            der(0x30, der(0x06, oid) + (if (critical) der(0x01, bytes(0xff)) else ByteArray(0)) + der(0x04, value))
        private fun apple(arc: Int) = OID_APPLE_ACCESSORY + byteArrayOf(arc.toByte())
        private fun time(digits: String): ByteArray {
            if (digits.length != TIME_LEN || !digits.all { it in '0'..'9' }) throw Mfi4Exception("a validity time $digits")
            val year = digits.substring(0, 4).toInt()
            return if (year in 1950 until 2050) der(0x17, (digits.substring(2) + "Z").toByteArray(Charsets.US_ASCII))
            else der(0x18, (digits + "Z").toByteArray(Charsets.US_ASCII))
        }
        internal fun upperHex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
    }
}
