package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class OpackCodecTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decodesCapturedPhoneRequestAndAppleEncodedReplyWithoutLosingTransactionBits() {
        val captured = hex("e248706c7567696e4944084a706c7567696e44617461912ee247636f6d6d616e644d636f6e666967526571756573744d7472616e73616374696f6e49443362519ff50304094f")
        val outer = OpackCodec.decode(captured) as Map<*, *>
        val inner = OpackCodec.decode(outer["pluginData"] as ByteArray) as Map<*, *>
        assertEquals(5695087618838974818L, inner["transactionID"])
        val apple = hex("e4466572726f7273e131170231b9e54d7472616e73616374696f6e494433ba45f52e113eb69f47636f6d6d616e64507265676973746572526573706f6e73654676616c756573e5310d020b3102020231160236000000000000364031080236000000000080354031120201")
        val reply = OpackCodec.decode(apple) as Map<*, *>
        assertEquals(-6938289932394478150L, reply["transactionID"])
        assertEquals(21.5, (reply["values"] as Map<*, *>)[520L])
        assertEquals(false, (reply["values"] as Map<*, *>)[514L])
        assertEquals(mapOf(535L to -6727L), reply["errors"])
        assertEquals(reply, OpackCodec.decode(OpackCodec.encode(reply)))
    }

    @Test fun referencesAndOpenCollectionsPreserveNumbersAndStrings() {
        assertEquals(listOf("abc", "abc", 520L, 520L), OpackCodec.decode(hex("d443616263a0310802a1")))
        val values = (-10L..50L).toList() + listOf(Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(values, OpackCodec.decode(OpackCodec.encode(values)))
        val strings = List(20) { "Температура $it".repeat(8) }
        assertEquals(strings, OpackCodec.decode(OpackCodec.encode(strings)))
    }

    @Test fun rejectsMalformedOrUnboundedPackets() {
        for (packet in listOf("a0", "df", "4401", "42ffff", "e208010802", "0102", "6440000000")) {
            assertThrows(Exception::class.java) { OpackCodec.decode(hex(packet)) }
        }
        assertThrows(Exception::class.java) { OpackCodec.decode(ByteArray(OpackCodec.MAX_BYTES + 1)) }
        var nested: Any? = null
        repeat(40) { nested = listOf(nested) }
        assertThrows(Exception::class.java) { OpackCodec.encode(nested) }
    }
}
