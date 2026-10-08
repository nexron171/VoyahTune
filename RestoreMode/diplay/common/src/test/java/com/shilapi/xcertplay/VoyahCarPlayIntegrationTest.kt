package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VoyahVehicleSpeedSource
import org.junit.Assert.*
import org.junit.Test

class VoyahCarPlayIntegrationTest {
    @Test fun speedWorksWithoutGpsOnlyWhenRequestedByPhone() {
        val speed = VoyahVehicleSpeedSource { 1000L }
        val noGps = object : com.shilapi.xcertplay.transport.Iap2LocationProvider {
            override fun start() = false
            override fun stop() = Unit
            override fun latestNmea(): String? = null
        }
        val provider = com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider(noGps, speed)
        assertFalse(provider.start())
        provider.onRequested(setOf(com.shilapi.xcertplay.transport.Iap2LocationMessages.VEHICLE_SPEED_DATA))
        assertTrue(provider.start())
        speed.update(36, 3, 1000)
        assertTrue(provider.latestNmea()!!.startsWith("\$PASCD,1.000,C,D,0,1,0.00,10.000*"))
        provider.stop()
        assertNull(provider.latestNmea())
    }

    @Test fun swReasonUsesOnlyKnownReasons() {
        for (reason in listOf(2, 3, 4)) assertEquals(true, VoyahCarPlayIntegration.nightForReason(reason))
        for (reason in listOf(0, 1)) assertEquals(false, VoyahCarPlayIntegration.nightForReason(reason))
        for (reason in listOf(-9999, -1, 5, 255)) assertNull(VoyahCarPlayIntegration.nightForReason(reason))
    }

    @Test fun speedConvertsUnitsAndDropsOldGearAndStaleSamples() {
        var now = 1000L
        val source = VoyahVehicleSpeedSource { now }
        source.start()
        source.update(36, 3, now)
        source.update(36, 3, now)
        val reading = source.drain()!!
        assertEquals(VehicleGear.DRIVE, reading.gear)
        assertEquals(1, reading.samples.size)
        assertEquals(10.0, reading.samples.single().metersPerSecond, 0.001)
        assertNull(source.drain())
        now += 100
        source.update(40, 3, now)
        now += 100
        source.update(5, 1, now)
        val reverse = source.drain()!!
        assertEquals(VehicleGear.REVERSE, reverse.gear)
        assertEquals(1, reverse.samples.size)
        now += 100
        source.update(10, 1, now)
        now += 3001
        assertNull(source.drain())
        source.update(36, -1, now)
        assertNull(source.drain())
        source.update(-9999, 3, now)
        assertNull(source.drain())
        source.stop()
        source.update(36, 3, now)
        assertNull(source.drain())
    }

    @Test fun disconnectResetsSamplesAndFutureTimestampsAreRejected() {
        val source = VoyahVehicleSpeedSource { 1000L }
        source.start()
        source.update(10, 0, 1100)
        assertNull(source.drain())
        source.update(0, 0, 1000)
        assertEquals(VehicleGear.PARK, source.drain()!!.gear)
        source.reset()
        assertNull(source.drain())
        source.update(0, 2, 1000)
        assertEquals(VehicleGear.NEUTRAL, source.drain()!!.gear)
    }
}
