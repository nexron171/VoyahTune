package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import org.junit.Assert.*
import org.junit.Test

/** Numeric envelopes and handshake from LIVI f999f557/ui_sync.rs, not Swift enum names. */
class CarPlayUiSyncSessionTest {
    private fun frame(type: Long, psn: Long = 1, ssn: Long = 1, extra: Map<String, Any?> = emptyMap(),
        panel: String = "Cluster_Display") = OpackCodec.encode(mapOf("displayUUID" to panel,
        "routingToken" to "preserved", "data" to BplistCodec.encode(mapOf("type" to type, "ssn" to ssn,
            "psn" to psn, "asn" to 0L) + extra)))
    private fun header(body: ByteArray): Map<*, *> {
        val outer = OpackCodec.decode(body) as Map<*, *>
        assertEquals("Cluster_Display", outer["displayUUID"])
        assertEquals("preserved", outer["routingToken"])
        return BplistCodec.decode(outer["data"] as ByteArray) as Map<*, *>
    }
    private fun command(body: ByteArray) = BplistCodec.decode(header(body)["data"] as ByteArray) as Map<*, *>
    private fun ready() = CarPlayUiSyncSession().also {
        it.receive(frame(0, extra = mapOf("id" to "phone-id", "version" to 3)))
        it.receive(frame(1, extra = mapOf("version" to 3)))
    }
    @Test fun phoneInitiatesAndCarEchoesIdNegotiatesV2AllowsLayoutsAndReturnsFocus() {
        val session = CarPlayUiSyncSession()
        assertFalse(session.ready)
        val reset = header(session.receive(frame(0, extra = mapOf("id" to "phone-id", "version" to 3))).single())
        assertEquals(0L, reset["type"]); assertEquals(1L, reset["ssn"]); assertEquals(1L, reset["psn"])
        assertEquals(0L, reset["asn"]); assertEquals(2L, reset["version"]); assertEquals("phone-id", reset["id"])
        assertFalse(reset.containsKey("vehicleID")); assertFalse(reset.containsKey("payload"))
        val out = session.receive(frame(1, extra = mapOf("version" to 3)))
        assertTrue(session.ready); assertEquals(3, out.size)
        val complete = header(out[0])
        assertEquals(1L, complete["type"]); assertEquals(2L, complete["psn"]); assertEquals(1L, complete["asn"])
        assertEquals(2L, complete["version"]); assertEquals("phone-id", complete["id"])
        assertEquals(0x11L, command(out[1])["type"])
        assertEquals(mapOf("allow" to true), command(out[1])["data"])
        assertEquals(7L, command(out[2])["type"])
        assertEquals(mapOf("tokenID" to 4711L), command(out[2])["data"])
    }
    @Test fun v1OmitsIdFromResetCompleteAndNoLayoutIdsAreInvented() {
        val s = CarPlayUiSyncSession()
        s.receive(frame(0, extra = mapOf("id" to "id")))
        val out = s.receive(frame(1))
        assertEquals(1L, header(out[0])["version"]); assertFalse(header(out[0]).containsKey("id"))
        assertEquals(listOf(0x11L, 7L), out.drop(1).map { command(it)["type"] })
    }
    @Test fun ackNoAckStaleSessionAndFocusReplayDoNotLoopOrReapply() {
        val s = ready()
        val focus = mapOf("data" to BplistCodec.encode(mapOf("type" to 8, "data" to mapOf("tokenID" to 17))))
        assertEquals(2, s.receive(frame(2, 2, extra = focus)).size)
        assertEquals(1, s.receive(frame(2, 2, extra = focus)).size)
        assertTrue(s.receive(frame(3, 3)).isEmpty())
        assertTrue(s.receive(frame(2, 4, extra = mapOf("noAck" to true))).isEmpty())
        assertTrue(s.receive(frame(2, 5, ssn = 9, extra = focus)).isEmpty())
        assertTrue(s.receive(frame(2, 5, extra = focus, panel = "Other_Display")).isEmpty())
    }
    @Test fun resetRetransmitDoesNotCreateAnotherSessionAndNewResetClearsState() {
        val s = CarPlayUiSyncSession()
        s.receive(frame(0)); assertTrue(s.receive(frame(0)).isEmpty())
        s.receive(frame(1))
        s.receive(frame(2, 2, extra = mapOf("data" to BplistCodec.encode(mapOf("type" to 3, "data" to mapOf("layoutID" to "phone-layout"))))))
        assertNotNull(s.latestCommand)
        val reset = header(s.receive(frame(0, ssn = 55)).single())
        assertEquals(2L, reset["ssn"]); assertFalse(s.ready); assertNull(s.latestCommand)
        assertTrue(s.receive(frame(1, ssn = 1)).isEmpty())
        s.receive(frame(1, ssn = 2)); assertTrue(s.ready)
        s.close(); assertFalse(s.ready)
        assertThrows(IllegalStateException::class.java) { s.receive(frame(0)) }
    }
    @Test fun malformedNumericAndNestedBodiesAreRejected() {
        for (body in listOf(byteArrayOf(1,2,3), BplistCodec.encode(mapOf("type" to "reset")),
            frame(0, -1), frame(0, extra = mapOf("type" to "reset")), ByteArray(262145))) {
            assertThrows(IllegalArgumentException::class.java) { CarPlayUiSyncSession().receive(body) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ready().receive(frame(2, 2, extra = mapOf("data" to byteArrayOf(0))))
        }
    }
}