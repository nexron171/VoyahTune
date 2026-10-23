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

    @After
    fun close() {
        protocol.close()
        VehicleClimate.install(null)
        VehicleClimate.update(emptyMap())
    }

    private fun request(command: String, transaction: Long = 1L, values: Any? = null) {
        val body = mapOf("command" to command, "transactionID" to transaction, "values" to values)
        protocol.receive(
            true,
            OpackCodec.encode(mapOf("pluginID" to 0, "pluginData" to OpackCodec.encode(body))),
        )
    }

    private fun host() {
        VehicleClimate.install(object : VehicleClimate.Host {
            override fun write(
                field: String,
                value: Double,
                current: BooleanSupplier,
                result: VehicleClimate.Result,
            ) {
                assertEquals("driverTemperature", field)
                assertEquals(22.0, value, 0.0)
                commands++
                completion = result
                this@VehicleStateProtocolTest.current = current
            }
        })
    }

    @Test
    fun unknownStateHasNoSyntheticTemperatureAndNoCabinSensor() {
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

    @Test
    fun acknowledgedWriteUsesReadbackAndDuplicateTransactionNeverRepeatsCommand() {
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
        assertEquals(
            mapOf(520L to 0L),
            replies.first { it["command"] == "writeResponse" }["errors"],
        )
        request("writeRequest", transaction, mapOf(520 to 22.0))
        assertEquals(1, commands)
        assertEquals(transaction, replies.last()["transactionID"])
        protocol.close()
        assertFalse(current!!.asBoolean)
    }

    @Test
    fun invalidOrReadonlyWritesDoNotReachNativeAndDisconnectInvalidatesState() {
        host()
        request("writeRequest", values = mapOf(520 to 21.25, 522 to "seat_front_right", 999 to true))
        assertEquals(0, commands)
        assertEquals(
            mapOf(520L to -6705L, 522L to -6705L, 999L to -6705L),
            replies.last()["errors"],
        )
        VehicleClimate.update(mapOf("driverTemperature" to 21.0))
        request("registerRequest", 2, listOf(520))
        VehicleClimate.update(emptyMap())
        assertEquals("updateNotify", replies.last()["command"])
        assertTrue((replies.last()["values"] as Map<*, *>).containsKey(520L))
        assertNull((replies.last()["values"] as Map<*, *>)[520L])
    }

    @Test
    fun fanRangeAndWritesUseReportedLevelsRatherThanOemCommandValues() {
        val received = ArrayList<Double>()
        VehicleClimate.install(object : VehicleClimate.Host {
            override fun write(
                field: String,
                value: Double,
                current: BooleanSupplier,
                result: VehicleClimate.Result,
            ) {
                assertEquals("fan", field)
                received.add(value)
                VehicleClimate.update(mapOf("fan" to value))
                result.complete(0)
            }
        })
        VehicleClimate.update(mapOf("fan" to 3.0))

        request("configRequest")
        val accessories = (replies.last()["values"] as Map<*, *>)["accessories"] as List<*>
        val services = accessories.flatMap { (it as Map<*, *>)["services"] as List<*> }
        val fan = services.flatMap { (it as Map<*, *>)["characteristics"] as List<*> }
            .map { it as Map<*, *> }
            .single { it["iid"] == 525L }
        assertEquals(0L, fan["minimumValue"])
        assertEquals(7L, fan["maximumValue"])
        assertEquals(1L, fan["stepValue"])
        assertEquals(3L, fan["initialValue"])

        for (invalid in listOf(8.0, 3.5, -1.0)) {
            request("writeRequest", 100L + replies.size, mapOf(525 to invalid))
            assertEquals(mapOf(525L to -6705L), replies.last()["errors"])
        }
        assertTrue(received.isEmpty())

        for (level in listOf(4.0, 3.0, 7.0, 0.0)) {
            request("writeRequest", 200L + replies.size, mapOf(525 to level))
            assertEquals(mapOf(525L to 0L), replies.last()["errors"])
            request("readRequest", values = listOf(525))
            assertEquals(mapOf(525L to level.toLong()), replies.last()["values"])
        }
        assertEquals(listOf(4.0, 3.0, 7.0, 0.0), received)
    }

    @Test
    fun recirculationExposesReadonlyAutoModeWithoutConfusingItWithHvacAuto() {
        VehicleClimate.update(mapOf("recirculation" to 0.0, "recirculationAuto" to 1.0, "auto" to 0.0))
        request("configRequest")
        val accessories = (replies.last()["values"] as Map<*, *>)["accessories"] as List<*>
        val services = accessories.flatMap { (it as Map<*, *>)["services"] as List<*> }
        val service = services.map { it as Map<*, *> }.single { it["type"] == 0x11000011L }
        val fields = (service["characteristics"] as List<*>).map { it as Map<*, *> }
        assertEquals(setOf(528L, 566L), fields.map { it["iid"] }.toSet())
        val automatic = fields.single { it["iid"] == 566L }
        assertEquals(0x3000005fL, automatic["type"])
        assertEquals(0L, automatic["format"])
        assertEquals(true, automatic["mutable"])
        assertEquals(false, automatic["writable"])
        assertEquals(true, automatic["initialValue"])
        assertEquals(false, fields.single { it["iid"] == 528L }["initialValue"])

        request("readRequest", 2, listOf(528, 566, 530))
        assertEquals(mapOf(528L to false, 566L to true, 530L to false), replies.last()["values"])
        host()
        request("writeRequest", 3, mapOf(566 to false, 528 to 2))
        assertEquals(mapOf(566L to -6705L, 528L to -6705L), replies.last()["errors"])
        assertEquals(0, commands)

        request("registerRequest", 4, listOf(528, 566))
        VehicleClimate.update(emptyMap())
        assertEquals("updateNotify", replies.last()["command"])
        assertEquals(mapOf(528L to null, 566L to null), replies.last()["values"])
        request("readRequest", 5, listOf(528, 566))
        assertEquals(mapOf(528L to -6727L, 566L to -6727L), replies.last()["errors"])
    }

    @Test
    fun recirculationTapCyclesRegardlessOfCachedOnBitAndRetransmissionNeverCyclesTwice() {
        VehicleClimate.install(object : VehicleClimate.Host {
            override fun write(
                field: String,
                value: Double,
                current: BooleanSupplier,
                result: VehicleClimate.Result,
            ) {
                assertEquals("cycleRecirculation", field)
                assertEquals(1.0, value, 0.0)
                commands++
                completion = result
                this@VehicleStateProtocolTest.current = current
            }
        })
        val modes = listOf(
            mapOf("recirculation" to 1.0, "recirculationAuto" to 0.0),
            mapOf("recirculation" to 0.0, "recirculationAuto" to 0.0),
            mapOf("recirculation" to 0.0, "recirculationAuto" to 1.0),
            mapOf("recirculation" to 1.0, "recirculationAuto" to 0.0),
        )
        VehicleClimate.update(modes.first())
        request("registerRequest", values = listOf(528, 566))
        for (index in 0..2) {
            val transaction = 100L + index
            val press = mapOf(528 to (modes[index].getValue("recirculation") == 0.0))
            request("writeRequest", transaction, press)
            request("writeRequest", transaction, press)
            assertEquals(index + 1, commands)
            assertEquals(modes[index], VehicleClimate.snapshot())
            assertTrue(replies.none { it["command"] == "writeResponse" && it["transactionID"] == transaction })

            VehicleClimate.update(modes[index + 1])
            completion!!.complete(0)
            assertEquals(mapOf(528L to 0L), replies.last { it["command"] == "writeResponse" }["errors"])
            request("readRequest", 200L + index, listOf(528, 566))
            assertEquals(
                mapOf(
                    528L to (modes[index + 1].getValue("recirculation") == 1.0),
                    566L to (modes[index + 1].getValue("recirculationAuto") == 1.0),
                ),
                replies.last()["values"],
            )
            request("writeRequest", transaction, press)
            assertEquals(index + 1, commands)
        }
        protocol.close()
        assertFalse(current!!.asBoolean)
    }

    @Test
    fun ventsDescribeReadonlyDirectionArraysAndShareFrontAutoMode() {
        VehicleClimate.update(mapOf("ventUpper" to 0.0, "ventMiddle" to 1.0, "ventLower" to 1.0, "auto" to 0.0))
        request("configRequest")
        val accessories = (replies.last()["values"] as Map<*, *>)["accessories"] as List<*>
        val climate = accessories.map { it as Map<*, *> }.single { it["iid"] == 512L }
        val vents = (climate["services"] as List<*>).map { it as Map<*, *> }
            .filter { it["type"] == 0x11000006L }
        assertEquals(3, vents.size)
        val fields = vents.flatMap { it["characteristics"] as List<*> }.map { it as Map<*, *> }
        for ((id, direction) in mapOf(551L to 2L, 556L to 4L, 561L to 16L)) {
            val field = fields.single { it["iid"] == id }
            assertEquals(13L, field["format"])
            assertEquals(listOf(direction), field["initialValue"])
            assertEquals(false, field["writable"])
        }
        request("readRequest", values = listOf(549, 554, 559, 550, 555, 560))
        assertEquals(
            mapOf(549L to false, 554L to true, 559L to true, 550L to false, 555L to false, 560L to false),
            replies.last()["values"],
        )
        request("writeRequest", 2, mapOf(551 to listOf(16)))
        assertEquals(mapOf(551L to -6705L), replies.last()["errors"])
    }

    @Test
    fun seatsHaveIndependentPositionsThreeLevelsAndNoSyntheticInitialState() {
        request("configRequest")
        val accessories = (replies.last()["values"] as Map<*, *>)["accessories"] as List<*>
        val seats = accessories.map { it as Map<*, *> }.single { it["type"] == 0x01500001L }
        val services = (seats["services"] as List<*>).map { it as Map<*, *> }
        assertEquals(4, services.count { it["type"] == 0x22000001L })
        assertEquals(2, services.count { it["type"] == 0x22000002L })
        for (service in services) {
            val fields = (service["characteristics"] as List<*>).map { it as Map<*, *> }
            val level = fields.first()
            assertEquals(0L, level["minimumValue"])
            assertEquals(3L, level["maximumValue"])
            assertEquals(1L, level["stepValue"])
            assertEquals(if (service["type"] == 0x22000001L) 7L else 1L, level["format"])
            assertFalse(level.containsKey("initialValue"))
        }
        val positions = services.associate { service ->
            val layout = (service["characteristics"] as List<*>).map { it as Map<*, *> }
                .single { it["type"] == 0x36000065L }
            service["iid"] to layout["initialValue"]
        }
        assertEquals("seat_2ndRow_left", positions[1033L])
        assertEquals("seat_2ndRow_right", positions[1037L])
        assertEquals("seat_front_left", positions[1041L])
        assertEquals("seat_front_right", positions[1045L])
        val allIds = accessories.flatMap { (it as Map<*, *>)["services"] as List<*> }
            .flatMap { (it as Map<*, *>)["characteristics"] as List<*> }
            .map { (it as Map<*, *>)["iid"] }
        assertEquals(allIds.size, allIds.toSet().size)
        assertTrue(allIds.size <= 64)
        request("readRequest", values = listOf(770, 1034, 1042))
        assertEquals(mapOf(770L to "5Seater"), replies.last()["values"])
        assertEquals(mapOf(1034L to -6727L, 1042L to -6727L), replies.last()["errors"])
    }

    @Test
    fun seatAndVentWritesPreserveFieldRoutingAndPublishOnlyObservedChanges() {
        val received = ArrayList<Pair<String, Double>>()
        val pending = ArrayList<VehicleClimate.Result>()
        VehicleClimate.install(object : VehicleClimate.Host {
            override fun write(
                field: String,
                value: Double,
                current: BooleanSupplier,
                result: VehicleClimate.Result,
            ) {
                received.add(field to value)
                pending.add(result)
            }
        })
        val before = mapOf(
            "rearLeftSeatHeatingLevel" to 0.0,
            "rearLeftSeatHeatingOn" to 0.0,
            "driverSeatVentilationLevel" to 0.0,
            "ventUpper" to 0.0,
        )
        VehicleClimate.update(before)
        request("registerRequest", values = listOf(1034, 1035, 1042, 549))
        request("writeRequest", 2, mapOf(1034 to 3, 1042 to 2, 549 to true))
        assertEquals(
            listOf("rearLeftSeatHeatingLevel" to 3.0, "driverSeatVentilationLevel" to 2.0, "ventUpper" to 1.0),
            received,
        )
        assertEquals(before, VehicleClimate.snapshot())
        assertTrue(replies.none { it["command"] == "writeResponse" })
        VehicleClimate.update(
            before + mapOf("rearLeftSeatHeatingLevel" to 3.0, "rearLeftSeatHeatingOn" to 1.0),
        )
        pending[0].complete(0)
        pending[1].complete(-6722)
        pending[2].complete(-6727)
        assertEquals(
            mapOf(1034L to 0L, 1042L to -6722L, 549L to -6727L),
            replies.last { it["command"] == "writeResponse" }["errors"],
        )
        request("readRequest", values = listOf(1034, 1035, 1042))
        assertEquals(mapOf(1034L to 3L, 1035L to true, 1042L to 0L), replies.last()["values"])
        request("writeRequest", 3, mapOf(1034 to -1, 1042 to 4, 1035 to 2, 1046 to 1.5))
        assertEquals(
            mapOf(1034L to -6705L, 1042L to -6705L, 1035L to -6705L, 1046L to -6705L),
            replies.last { it["command"] == "writeResponse" }["errors"],
        )
        assertEquals(3, received.size)
    }

    @Test
    fun steeringHeatingUsesAppleClimateServiceWithOneIntegerStage() {
        VehicleClimate.update(emptyMap())
        request("configRequest")
        val accessories = (replies.last()["values"] as Map<*, *>)["accessories"] as List<*>
        val climate = accessories.map { it as Map<*, *> }.single { it["type"] == 0x01000001L }
        val steering = (climate["services"] as List<*>).map { it as Map<*, *> }
            .single { it["type"] == 0x11000003L }
        assertEquals(563L, steering["iid"])
        val level = (steering["characteristics"] as List<*>).single() as Map<*, *>
        assertEquals(564L, level["iid"])
        assertEquals(0x31000021L, level["type"])
        assertEquals(7L, level["format"])
        assertEquals(0L, level["minimumValue"])
        assertEquals(1L, level["maximumValue"])
        assertEquals(1L, level["stepValue"])
        assertEquals(true, level["writable"])
        assertEquals(true, level["supportsInvalid"])
        assertFalse(level.containsKey("initialValue"))

        request("readRequest", values = listOf(564))
        assertEquals(mapOf(564L to -6727L), replies.last()["errors"])
        host()
        for ((index, invalid) in listOf(-1, 2, 0.5, true).withIndex()) {
            request("writeRequest", 100L + index, mapOf(564 to invalid))
            assertEquals(mapOf(564L to -6705L), replies.last()["errors"])
        }
        assertEquals(0, commands)
    }

    @Test
    fun steeringWritesWaitForReadbackAndFaultClearsTheLastKnownLevel() {
        val received = ArrayList<Double>()
        VehicleClimate.install(object : VehicleClimate.Host {
            override fun write(
                field: String,
                value: Double,
                current: BooleanSupplier,
                result: VehicleClimate.Result,
            ) {
                assertEquals("steeringWheelHeatingLevel", field)
                received.add(value)
                completion = result
            }
        })
        val otherClimate = mapOf("driverTemperature" to 21.0)
        VehicleClimate.update(otherClimate + ("steeringWheelHeatingLevel" to 0.0))
        request("registerRequest", values = listOf(564))

        for ((index, level) in listOf(1.0, 0.0).withIndex()) {
            val before = VehicleClimate.snapshot()
            val transaction = 10L + index
            request("writeRequest", transaction, mapOf(564 to level.toLong()))
            request("writeRequest", transaction, mapOf(564 to level.toLong()))
            assertEquals(index + 1, received.size)
            assertEquals(before, VehicleClimate.snapshot())
            assertTrue(replies.none { it["command"] == "writeResponse" && it["transactionID"] == transaction })
            VehicleClimate.update(otherClimate + ("steeringWheelHeatingLevel" to level))
            completion!!.complete(0)
            assertEquals(
                mapOf(564L to 0L),
                replies.last { it["command"] == "writeResponse" }["errors"],
            )
            request("readRequest", values = listOf(564))
            assertEquals(mapOf(564L to level.toLong()), replies.last()["values"])
        }
        assertEquals(listOf(1.0, 0.0), received)

        // Native omits the field when the OEM reports a heating fault.
        VehicleClimate.update(otherClimate)
        assertEquals("updateNotify", replies.last()["command"])
        assertEquals(mapOf(564L to null), replies.last()["values"])
        request("writeRequest", 20, mapOf(564 to 1))
        completion!!.complete(-6727)
        assertEquals(
            mapOf(564L to -6727L),
            replies.single { it["command"] == "writeResponse" && it["transactionID"] == 20L }["errors"],
        )
        request("readRequest", values = listOf(564))
        assertEquals(mapOf(564L to -6727L), replies.last()["errors"])
        assertEquals(otherClimate, VehicleClimate.snapshot())
    }
}
