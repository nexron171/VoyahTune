package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahVehicleControlsTest {
    private var time = 1000L
    private var levels = mapOf(
        UltraVehicleControl.DRIVER_MASSAGE to 0,
        UltraVehicleControl.PASSENGER_MASSAGE to 2,
        UltraVehicleControl.DRIVER_MASSAGE_PROGRAM to 1,
    )
    private var confirmed = true
    private val calls = mutableListOf<Pair<UltraVehicleControl, Int>>()
    private val transactions = UltraWriteTransactions()

    private fun accessory() = VoyahClimateAccessory(
        sample = { UltraClimateSample(Double.NaN, 1000, vehicleControlValues = levels) },
        now = { time },
        supportedVehicleControls = levels.keys,
        vehicleControlWriter = { control, action ->
            calls.add(control to action)
            UltraClimateWriteResult(confirmed, if (confirmed) "Confirmed" else "Unavailable")
        },
        writeTransactions = transactions,
    )

    private fun send(accessory: VoyahClimateAccessory, frame: CafRcsFrame): CafMessageReader =
        CarAccessoryMessages.reader(requireNotNull(accessory.handle(CarAccessoryMessages.reader(frame.rcsBody))).rcsBody)

    private fun write(accessory: VoyahClimateAccessory, transaction: Long, values: Map<Long, Any?>) =
        send(accessory, CarAccessoryMessages.writeRequest(1, transaction, values))

    @Test
    fun massageButtonsUseActionTokensAndShowObservedLevelSeparately() {
        val accessory = accessory()
        val config = send(accessory, CarAccessoryMessages.configRequest(1, 1)).values() as Map<*, *>
        val services = (config["accessories"] as List<*>).flatMap { (it as Map<*, *>)["services"] as List<*> }
            .map { it as Map<*, *> }
        for (control in listOf(UltraVehicleControl.DRIVER_MASSAGE, UltraVehicleControl.PASSENGER_MASSAGE)) {
            val service = services.single { it["iid"] == control.serviceIid }
            assertEquals(0x16000002L, service["type"])
            val fields = (service["characteristics"] as List<*>).map { it as Map<*, *> }
            val action = fields.single { it["iid"] == control.valueIid }
            assertEquals(0x36000010L, action["type"])
            assertEquals(0L, action["initialValue"])
            assertEquals(1L, action["format"])
            assertTrue(fields.none { it["type"] == 0x30000017L })
            val display = fields.single { it["iid"] == control.serviceIid + 2 }
            assertEquals(true, display["mutable"])
            assertEquals(if (control == UltraVehicleControl.DRIVER_MASSAGE) "Driver massage: Off" else "Passenger massage: 2 / 3", display["initialValue"])
        }
        val program = services.single { it["iid"] == UltraVehicleControl.DRIVER_MASSAGE_PROGRAM.serviceIid }
        assertEquals(0x16000007L, program["type"])
        assertTrue(calls.isEmpty())
    }

    @Test
    fun buttonEchoReturnsToReadyAndOnlyObservedIntensityChangesTheLabel() {
        val accessory = accessory()
        val driver = UltraVehicleControl.DRIVER_MASSAGE
        val passenger = UltraVehicleControl.PASSENGER_MASSAGE
        val displayId = driver.serviceIid + 2
        send(accessory, CarAccessoryMessages.registerRequest(1, 1,
            CafRegistration.Identifiers(listOf(driver.valueIid, displayId, passenger.serviceIid + 2))))
        var transaction = 10L
        for (observed in listOf(3, 2, 1, 0)) {
            val previous = levels.getValue(driver)
            assertEquals(mapOf(driver.valueIid to 0L), write(accessory, transaction++, mapOf(driver.valueIid to 1)).errors())
            val echo = CarAccessoryMessages.reader(requireNotNull(accessory.changed()).rcsBody).valuesMap()
            assertEquals(1L, echo[driver.valueIid])
            val beforeReadback = send(accessory, CarAccessoryMessages.readRequest(1, 2, listOf(displayId))).valuesMap()
            assertEquals(if (previous == 0) "Driver massage: Off" else "Driver massage: $previous / 3", beforeReadback[displayId])
            levels = levels + (driver to observed)
            val update = CarAccessoryMessages.reader(requireNotNull(accessory.changed()).rcsBody).valuesMap()
            assertEquals(0L, update[driver.valueIid])
            assertEquals(if (observed == 0) "Driver massage: Off" else "Driver massage: $observed / 3", update[displayId])
            val passengerValue = send(accessory, CarAccessoryMessages.readRequest(1, 3, listOf(passenger.serviceIid + 2))).valuesMap()
            assertEquals("Passenger massage: 2 / 3", passengerValue[passenger.serviceIid + 2])
            assertNull(accessory.changed())
        }
        assertEquals(List(4) { driver to 1 }, calls)
    }

    @Test
    fun retransmissionAcrossChannelsNeverExecutesAnActionTwiceAndChangedPayloadIsRejected() {
        val first = accessory()
        val second = accessory()
        val driver = UltraVehicleControl.DRIVER_MASSAGE
        val request = mapOf(driver.valueIid to 1)
        val transaction = Long.MIN_VALUE + 41
        assertEquals(mapOf(driver.valueIid to 0L), write(first, transaction, request).errors())
        assertEquals(mapOf(driver.valueIid to 0L), write(second, transaction, request).errors())
        assertEquals(listOf(driver to 1), calls)
        val conflicting = write(second, transaction, mapOf(driver.valueIid to 2))
        assertEquals(CafCommand.GENERAL_ERROR, conflicting.command)
        assertEquals(-50L, conflicting.requireError())
        assertEquals(1, calls.size)
        write(second, transaction + 1, request)
        assertEquals(2, calls.size)
    }

    @Test
    fun invalidActionsAreRejectedAndFailedWritesAreNotRetriedOrShownAsSuccess() {
        val accessory = accessory()
        val driver = UltraVehicleControl.DRIVER_MASSAGE
        var transaction = 10L
        for (invalid in listOf(-1, 256, 1.5, true, "1")) {
            assertEquals(mapOf(driver.valueIid to -50L), write(accessory, transaction++, mapOf(driver.valueIid to invalid)).errors())
        }
        assertTrue(calls.isEmpty())
        confirmed = false
        val request = mapOf(driver.valueIid to 1)
        val failure = write(accessory, 100, request).errors()
        assertNotEquals(0L, failure[driver.valueIid])
        confirmed = true
        assertEquals(failure, write(accessory, 100, request).errors())
        assertEquals(1, calls.size)
        val read = send(accessory, CarAccessoryMessages.readRequest(1, 2, listOf(driver.valueIid, driver.serviceIid + 2)))
        assertEquals(mapOf(driver.valueIid to 0L, driver.serviceIid + 2 to "Driver massage: Off"), read.valuesMap())
        time = 100_000
        val stale = send(accessory, CarAccessoryMessages.readRequest(1, 3, listOf(driver.serviceIid + 2)))
        assertEquals("Driver massage: No data", stale.valuesMap()[driver.serviceIid + 2])
    }

    @Test
    fun programSelectionRemainsAbsoluteAndRejectsOtherLevels() {
        val accessory = accessory()
        val program = UltraVehicleControl.DRIVER_MASSAGE_PROGRAM
        for (target in listOf(2, 1)) {
            assertEquals(mapOf(program.valueIid to 0L), write(accessory, target.toLong(), mapOf(program.valueIid to target)).errors())
            assertEquals(program to target, calls.last())
        }
        for ((index, invalid) in listOf(0, 3, 1.5).withIndex()) {
            assertEquals(mapOf(program.valueIid to -50L), write(accessory, 10L + index, mapOf(program.valueIid to invalid)).errors())
        }
        assertEquals(2, calls.size)
    }

    @Test
    fun cachedRouteWritesStillEchoUnchangedAndNullValues() {
        val accessory = accessory()
        send(accessory, CarAccessoryMessages.registerRequest(1, 1, CafRegistration.Identifiers(listOf(258))))
        for ((index, value) in listOf(true, null).withIndex()) {
            val transaction = 10L + index
            repeat(2) {
                assertEquals(mapOf(258L to 0L), write(accessory, transaction, mapOf(258L to value)).errors())
                val echo = CarAccessoryMessages.reader(requireNotNull(accessory.changed()).rcsBody)
                assertEquals(mapOf(258L to value), echo.valuesMap())
                assertNull(accessory.changed())
            }
        }
        assertTrue(calls.isEmpty())
    }
}
