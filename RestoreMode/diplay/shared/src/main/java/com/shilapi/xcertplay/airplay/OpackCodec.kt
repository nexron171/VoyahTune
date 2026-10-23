// SPDX-License-Identifier: GPL-3.0-or-later
// Wire format adapted from f-io/LIVI, Copyright 2026 Lasse Heitgres.
package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Bounded OPACK codec. Integer dictionary keys and transaction IDs retain all 64 bits. */
object OpackCodec {
    const val MAX_BYTES = 256 * 1024
    private const val MAX_VALUES = 16384
    private const val MAX_DEPTH = 32

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size <= MAX_BYTES) { "OPACK payload too large" }
        return Reader(bytes).run {
            val result = value(0)
            require(position == bytes.size) { "Trailing OPACK bytes" }
            result
        }
    }

    fun encode(value: Any?): ByteArray = Writer().run {
        write(value, 0)
        output.toByteArray()
    }

    private class Reader(val bytes: ByteArray) {
        var position = 0
        private var count = 0
        private val references = ArrayList<Any?>()

        private fun take(length: Int): ByteArray {
            require(length >= 0 && length <= bytes.size - position) { "Truncated OPACK" }
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        private fun unsigned(width: Int): Long {
            require(width in 1..8 && width <= bytes.size - position) { "Truncated OPACK number" }
            var result = 0L
            repeat(width) { result = result or ((bytes[position++].toLong() and 255) shl (8 * it)) }
            return result
        }

        private fun length(tag: Int, base: Int): Int {
            val short = tag - base
            val length = if (short <= 32) short.toLong() else unsigned(short - 32)
            require(length in 0..MAX_BYTES.toLong()) { "Invalid OPACK length" }
            return length.toInt()
        }

        private fun remember(value: Any?): Any? = value.also { references.add(it) }

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH && ++count <= MAX_VALUES) { "OPACK complexity limit" }
            val tag = unsigned(1).toInt()
            return when (tag) {
                1 -> true
                2 -> false
                4 -> null
                5 -> ByteBuffer.wrap(take(16)).let { remember(UUID(it.long, it.long)) }
                6, 0x36 -> remember(Double.fromBits(unsigned(8)))
                0x35 -> remember(Float.fromBits(unsigned(4).toInt()).toDouble())
                in 0x08..0x2f -> (tag - 0x08).toLong()
                in 0x30..0x33 -> {
                    val width = 1 shl (tag - 0x30)
                    val bits = unsigned(width)
                    val shift = 64 - width * 8
                    remember((bits shl shift) shr shift)
                }
                in 0x40..0x64 -> {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    remember(decoder.decode(ByteBuffer.wrap(take(length(tag, 0x40)))).toString())
                }
                in 0x70..0x94 -> remember(take(length(tag, 0x70)))
                in 0xa0..0xc4 -> {
                    val index = if (tag <= 0xc0) (tag - 0xa0).toLong() else unsigned(tag - 0xc0)
                    require(index >= 0 && index < references.size) { "Invalid OPACK reference" }
                    references[index.toInt()]
                }
                in 0xd0..0xef -> {
                    val dictionary = tag >= 0xe0
                    val declared = tag and 15
                    val array = ArrayList<Any?>()
                    val map = LinkedHashMap<Any?, Any?>()
                    var entries = 0
                    while (declared == 15 || entries < declared) {
                        require(position < bytes.size) { "Unterminated OPACK collection" }
                        if (declared == 15 && bytes[position].toInt() == 3) {
                            position++
                            break
                        }
                        val first = value(depth + 1)
                        if (dictionary) {
                            require(first is String || first is Long) { "Unsupported OPACK key" }
                            require(!map.containsKey(first)) { "Duplicate OPACK key" }
                            map[first] = value(depth + 1)
                        } else {
                            array.add(first)
                        }
                        entries++
                    }
                    if (dictionary) map else array
                }
                else -> throw IllegalArgumentException("Unsupported OPACK tag $tag")
            }
        }
    }

    private class Writer {
        val output = ByteArrayOutputStream()
        private var count = 0

        private fun number(value: Long, width: Int) {
            repeat(width) { output.write((value ushr (it * 8)).toInt() and 255) }
        }

        private fun blob(base: Int, bytes: ByteArray) {
            require(bytes.size <= MAX_BYTES) { "OPACK payload too large" }
            if (bytes.size <= 32) {
                output.write(base + bytes.size)
            } else {
                val width = if (bytes.size <= 255) 1 else if (bytes.size <= 65535) 2 else 3
                output.write(base + 32 + width)
                number(bytes.size.toLong(), width)
            }
            output.write(bytes)
        }

        fun write(value: Any?, depth: Int) {
            require(depth <= MAX_DEPTH && ++count <= MAX_VALUES) { "OPACK complexity limit" }
            when (value) {
                null -> output.write(4)
                is Boolean -> output.write(if (value) 1 else 2)
                is Float, is Double -> {
                    output.write(0x36)
                    number((value as Number).toDouble().toBits(), 8)
                }
                is Byte, is Short, is Int, is Long -> {
                    val integer = (value as Number).toLong()
                    if (integer in 0..39) {
                        output.write(8 + integer.toInt())
                    } else {
                        val width = when (integer) {
                            in -128..127 -> 1
                            in -32768..32767 -> 2
                            in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> 4
                            else -> 8
                        }
                        output.write(0x30 + Integer.numberOfTrailingZeros(width))
                        number(integer, width)
                    }
                }
                is String -> blob(0x40, value.toByteArray(Charsets.UTF_8))
                is ByteArray -> blob(0x70, value)
                is UUID -> {
                    output.write(5)
                    output.write(ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array())
                }
                is List<*> -> {
                    output.write(0xd0 + if (value.size <= 14) value.size else 15)
                    value.forEach { write(it, depth + 1) }
                    if (value.size > 14) output.write(3)
                }
                is Map<*, *> -> {
                    output.write(0xe0 + if (value.size <= 14) value.size else 15)
                    value.forEach { (key, child) ->
                        require(key is String || key is Int || key is Long) { "Unsupported OPACK key" }
                        write(key, depth + 1)
                        write(child, depth + 1)
                    }
                    if (value.size > 14) output.write(3)
                }
                else -> throw IllegalArgumentException("Unsupported OPACK value")
            }
            require(output.size() <= MAX_BYTES) { "OPACK payload too large" }
        }
    }
}
