package com.shilapi.xcertplay.airplay.mfi4

/*
 * MFi 4.0 mutual authentication, ported from LIVI (GPL-3.0-or-later):
 * https://github.com/f-io/LIVI/tree/3fb5210f71ebc4b15d9aed034df8f0c28eda4f54/native/livi-helperd/crates/livi-cp/src/mfi4
 */

class Mfi4Exception(message: String) : Exception(message)

/** One MFi4 message: start word, message id and length-prefixed parameters, all big-endian. */
class Mfi4Message(val start: Int, val id: Int, val params: List<Pair<Int, ByteArray>> = emptyList()) {
    fun with(param: Int, value: ByteArray) = Mfi4Message(start, id, params + (param to value.copyOf()))
    fun param(param: Int): ByteArray? = params.firstOrNull { it.first == param }?.second
    fun all(param: Int): List<ByteArray> = params.filter { it.first == param }.map { it.second }
    fun u8(param: Int): Int? = param(param)?.takeIf { it.size == 1 }?.let { it[0].toInt() and 0xff }
    fun u16(param: Int): Int? = param(param)?.takeIf { it.size == 2 }?.let { Mfi4Wire.u16(it, 0) }
    fun exact(param: Int, size: Int): ByteArray? = param(param)?.takeIf { it.size == size }
    fun ids(): List<Int> = params.map { it.first }

    fun encodeParams(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((param, value) in params) {
            out.write(Mfi4Wire.be16(value.size + Mfi4Wire.PARAM_HEADER)); out.write(Mfi4Wire.be16(param)); out.write(value)
        }
        return out.toByteArray()
    }

    fun header(bodyLength: Int): ByteArray =
        Mfi4Wire.be16(start) + Mfi4Wire.be16(Mfi4Wire.HEADER + bodyLength) + Mfi4Wire.be16(id)

    fun encode(): ByteArray = encodeParams().let { header(it.size) + it }
}

object Mfi4Wire {
    const val HEADER = 6
    internal const val PARAM_HEADER = 4
    const val CLEAR = 0x0000
    const val TUNNELED = 0x4040

    internal fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
    internal fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    /** Start word and message id when [buffer] is exactly one message. */
    fun peek(buffer: ByteArray): Pair<Int, Int>? {
        if (buffer.size < HEADER) return null
        return if (u16(buffer, 2) == buffer.size) u16(buffer, 0) to u16(buffer, 4) else null
    }

    fun decode(buffer: ByteArray): Mfi4Message {
        val (start, id) = peek(buffer) ?: throw Mfi4Exception("not one whole message")
        return Mfi4Message(start, id, decodeParams(buffer.copyOfRange(HEADER, buffer.size)))
    }

    fun decodeParams(buffer: ByteArray): List<Pair<Int, ByteArray>> {
        val params = ArrayList<Pair<Int, ByteArray>>()
        var at = 0
        while (at < buffer.size) {
            if (buffer.size - at < PARAM_HEADER) throw Mfi4Exception("a parameter cut short")
            val length = u16(buffer, at)
            if (length < PARAM_HEADER || length > buffer.size - at)
                throw Mfi4Exception("a parameter of length $length in ${buffer.size - at} bytes")
            params += u16(buffer, at + 2) to buffer.copyOfRange(at + PARAM_HEADER, at + length)
            at += length
        }
        return params
    }
}
