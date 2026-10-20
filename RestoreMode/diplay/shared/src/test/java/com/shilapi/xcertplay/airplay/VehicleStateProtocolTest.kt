package com.shilapi.xcertplay.airplay

import java.util.function.BooleanSupplier
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VehicleStateProtocolTest {
    private val replies = ArrayList<Map<*, *>>()
    private var commands = 0
    private var completion: VehicleClimate.Result? = null
    private var current: BooleanSupplier? = null
    private val protocol = VehicleStateProtocol { _, packet ->
        val outer = OpackCodec.decode(packet) as Map<*, *>
        replies.add(OpackCodec.decode(outer["pluginData"] as ByteArray) as Map<*, *>)
    }

    @After fun close() {
        protocol.close()
        VehicleClimate.install(null)
        VehicleClimate.update(emptyMap())
    }

    private fun request(command: String, transaction: Long = 1L, values: Any? = null) {
        val body = mapOf("command" to command, "transactionID" to transaction, "values" to values)
        protocol.receive(true, OpackCodec.encode(mapOf("pluginID" to 0, "pluginData" to OpackCodec.encode(body))))
    }

    private fun host() {
        VehicleClimate.install(object : VehicleClimate.Host {
            override fun write(field: String, value: Double, current: BooleanSupplier, result: VehicleClimate.Result) {
                assertEquals("driverTemperature", field)
                assertEquals(22.0, value, 0.0)
                commands++
                completion = result
                this@VehicleStateProtocolTest.current = current
            }
        })
    }

    @Test fun unknownStateHasNoSyntheticTemperatureAndNoCabinSensor() {
        VehicleClimate.update(emptyMap())
        request("configRequest")
        val accessories = (replies.last()["values"] as Map<*, *>)["accessories"] as List<*>
        val fields = accessories.flatMap { (it as Map<*, *>)["services"] as List<*> }
            .flatMap { (it as Map<*, *>)["characteristics"] as List<*> }.map { it as Map<*, *> }
        assertFalse(fields.first { it["iid"] == 520L }.containsKey("initialValue"))
        assertTrue(fields.none { it["iid"] == 521L || it["iid"] == 535L })
        request("registerRequest", values = listOf(520))
        assertEquals(mapOf(520L to -6727L), replies.last()["errors"])
    }

    @Test fun acknowledgedWriteUsesReadbackAndDuplicateTransactionNeverRepeatsCommand() {
        host()
        VehicleClimate.update(mapOf("driverTemperature" to 21.0))
        request("registerRequest", values = listOf(520))
        val transaction = Long.MIN_VALUE + 17
        request("writeRequest", transaction, mapOf(520 to 22.0))
        request("writeRequest", transaction, mapOf(520 to 22.0))
        assertEquals(1, commands)
        assertEquals(21.0, VehicleClimate.snapshot()["driverTemperature"]!!, 0.0)
        VehicleClimate.update(mapOf("driverTemperature" to 22.0))
        completion!!.complete(0)
        assertEquals(mapOf(520L to 0L), replies.first { it["command"] == "writeResponse" }["errors"])
        request("writeRequest", transaction, mapOf(520 to 22.0))
        assertEquals(1, commands)
        assertEquals(transaction, replies.last()["transactionID"])
        protocol.close()
        assertFalse(current!!.asBoolean)
    }

    @Test fun invalidOrReadonlyWritesDoNotReachNativeAndDisconnectInvalidatesState() {
        host()
        request("writeRequest", values = mapOf(520 to 21.25, 522 to "seat_front_right", 999 to true))
        assertEquals(0, commands)
        assertEquals(mapOf(520L to -6705L, 522L to -6705L, 999L to -6705L), replies.last()["errors"])
        VehicleClimate.update(mapOf("driverTemperature" to 21.0))
        request("registerRequest", 2, listOf(520))
        VehicleClimate.update(emptyMap())
        assertEquals("updateNotify", replies.last()["command"])
        assertTrue((replies.last()["values"] as Map<*, *>).containsKey(520L))
        assertNull((replies.last()["values"] as Map<*, *>)[520L])
    }
}
