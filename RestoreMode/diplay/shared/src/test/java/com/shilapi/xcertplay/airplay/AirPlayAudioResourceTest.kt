package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class AirPlayAudioResourceTest {
    private fun modes(entity: Any) = mapOf("resources" to listOf(mapOf("resourceID" to 2, "entity" to entity)))
    private fun reply(cseq: Int, body: Map<String, Any?>, status: Int = 200) = RtspMessage.Request(
        "RTSP/1.0", status.toString(), "OK", mapOf("cseq" to cseq.toString()), BplistCodec.encode(body))

    @Test fun selectionTakesOnlyAudioAndAllowsCallsAndNewMusic() {
        val p = AirPlayAudioResource.takeCommand()
        val params = p["params"] as Map<*, *>
        assertEquals("changeModes", p["type"])
        assertEquals(setOf("resources"), params.keys)
        assertEquals(listOf(mapOf("resourceID" to 2, "transferType" to 1, "transferPriority" to 500,
            "takeConstraint" to 100, "borrowConstraint" to 100)), params["resources"])
    }

    @Test fun aSocketWriteOrUnrelatedReplyDoesNotGrantAudio() {
        val sent = CountDownLatch(1)
        val r = AirPlayAudioResource { _, register -> register(42); sent.countDown(); true }
        val result = FutureTask { r.takeForRadio(2000) }
        Thread(result).apply { isDaemon = true; start() }
        try {
            assertTrue(sent.await(1, TimeUnit.SECONDS))
            r.reply(reply(41, mapOf("params" to modes(2))))
            r.reply(reply(42, emptyMap())) // HTTP/RTSP 200 alone is not an ownership grant.
            r.modesChanged(modes(1))
            assertFalse(result.isDone)
            r.modesChanged(mapOf("resources" to listOf(mapOf("resourceID" to 1, "entity" to 2))))
            assertFalse(result.isDone)
            r.modesChanged(modes("accessory"))
            assertTrue(result.get(1, TimeUnit.SECONDS))
        } finally { r.close() }
    }

    @Test fun correlatedReplyCanGrantOrRejectAndTimeoutAndDisconnectNeverGrant() {
        for (granted in listOf(true, false)) {
            lateinit var r: AirPlayAudioResource
            r = AirPlayAudioResource { _, register ->
                register(8)
                r.reply(reply(8, if (granted) mapOf("status" to 0, "params" to modes(2)) else mapOf("status" to -6727)))
                true
            }
            assertEquals(granted, r.takeForRadio(10))
            r.close()
        }
        AirPlayAudioResource { _, _ -> false }.use { assertFalse(it.takeForRadio(0)) }
        val timedOutCommands = mutableListOf<Map<String, Any?>>()
        AirPlayAudioResource { command, _ -> timedOutCommands += command; true }.use { assertFalse(it.takeForRadio(0)) }
        val resources = (timedOutCommands.last()["params"] as Map<*, *>)["resources"] as List<*>
        assertEquals(2, (resources.single() as Map<*, *>)["transferType"])
        val sent = CountDownLatch(1)
        val r = AirPlayAudioResource { _, register -> register(1); sent.countDown(); true }
        val result = FutureTask { r.takeForRadio(5000) }
        Thread(result).apply { isDaemon = true; start() }
        assertTrue(sent.await(1, TimeUnit.SECONDS))
        r.close()
        assertFalse(result.get(1, TimeUnit.SECONDS))
        assertFalse(r.takeForRadio(0))
    }

    @Test fun malformedAndDuplicateOwnershipNeverMasqueradesAsADeviceGrant() {
        for (entity in listOf(null, 0, 3, true, 2.5, "2"))
            assertNull(AirPlayAudioResource.mainAudioOwner(modes(entity ?: "unknown")))
        assertEquals(2, AirPlayAudioResource.mainAudioOwner(modes(2L)))
        assertNull(AirPlayAudioResource.mainAudioOwner(mapOf("resources" to listOf(
            mapOf("resourceID" to 2, "entity" to 2), mapOf("resourceID" to "mainAudio", "entity" to 1)))))
    }
}
