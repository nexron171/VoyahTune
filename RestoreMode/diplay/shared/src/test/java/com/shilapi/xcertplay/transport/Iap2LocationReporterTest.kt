package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LocationReporterTest {
    private val provider = FakeProvider()
    private val sent = mutableListOf<Iap2Frame>()
    private val progress = mutableListOf<String>()
    private var nowMillis = 0L

    // What the iPhone requested in the car: GGA, RMC, PASCD and their intervals.
    private val start = Iap2Messages.buildRaw(Iap2LocationMessages.START_LOCATION_INFORMATION) {
        u16(0, 0)
        void(1)
        void(2)
        void(4)
        u32(0x8001, 1000)
    }
    private val stop = Iap2Messages.buildRaw(Iap2LocationMessages.STOP_LOCATION_INFORMATION) {}

    private fun link(source: FakeProvider = provider) =
        Iap2LocationReporter(source, { progress += it }, nanoTime = { nowMillis * 1_000_000 })

    @Test
    fun continuousReportingStartsWithoutPhoneRequestAndKeepsItsCadence() {
        val link = link()
        link.startContinuous(vehicleSpeedEnabled = true) { sent += it }

        assertTrue(provider.started)
        assertEquals(setOf(1, 2, 4), provider.requested)
        assertEquals(1, sent.size)
        nowMillis += 400
        link.handle(stop) { sent += it }
        link.handle(start) { sent += it }
        link.tick { sent += it }

        assertTrue(provider.started)
        assertEquals(1, sent.size)
        assertEquals(600L, link.pollTimeout(60_000L))
        nowMillis += 600
        link.tick { sent += it }
        assertEquals(2, sent.size)
    }

    @Test
    fun continuousReportingSendsFreshVehicleSpeedWithoutGpsOrPhoneRequest() {
        provider.nmea = null
        val speed = VoyahVehicleSpeedSource { nowMillis }
        val combined = VehicleSpeedLocationProvider(provider, speed)
        val link = Iap2LocationReporter(combined, {}, nanoTime = { nowMillis * 1_000_000 })
        link.startContinuous(vehicleSpeedEnabled = true) { sent += it }
        assertTrue(sent.isEmpty())

        nowMillis = 1_000
        speed.update(36, 3, nowMillis)
        link.tick { sent += it }
        val nmea = sent.single().body().asList().single().payload.toString(Charsets.UTF_8)
        assertTrue(nmea.startsWith("\$PASCD,1.000,C,D,0,1,0.00,10.000*"))

        nowMillis = 2_000
        speed.update(36, 3, nowMillis)
        nowMillis = 6_000
        link.tick { sent += it }
        assertEquals(1, sent.size)

        combined.stop()
        assertFalse(provider.started)
        speed.update(36, 3, nowMillis)
        assertEquals(null, combined.latestNmea())
    }

    @Test
    fun continuousReportingDoesNotEnableUndeclaredSpeed() {
        val link = link()
        link.startContinuous(vehicleSpeedEnabled = false) { sent += it }
        link.handle(start) { sent += it }

        assertEquals(setOf(1, 2), provider.requested)
        assertEquals(1, sent.size)
    }

    @Test
    fun continuousReportingWaitsForRealDataAndToleratesMissingProvider() {
        provider.nmea = null
        val link = link()
        link.startContinuous(vehicleSpeedEnabled = true) { sent += it }
        assertTrue(sent.isEmpty())

        provider.nmea = "\$GPRMC\r\n"
        nowMillis += 1_000
        link.tick { sent += it }
        assertEquals(1, sent.size)

        val unavailable = Iap2LocationReporter(null, {})
        unavailable.startContinuous(vehicleSpeedEnabled = true) { sent += it }
        unavailable.tick { sent += it }
        assertEquals(1, sent.size)
        assertEquals(60_000L, unavailable.pollTimeout(60_000L))
    }

    @Test
    fun aLinkSendsOnlyBetweenItsOwnStartAndStop() {
        val link = link()

        assertTrue(link.handle(start) { sent += it })
        assertEquals(setOf(0, 1, 2, 4, 0x8001), provider.requested)
        assertTrue(provider.started)
        nowMillis += 1_000
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.messageId == Iap2LocationMessages.LOCATION_INFORMATION })

        assertTrue(link.handle(stop) { sent += it })
        assertFalse(provider.started)
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertEquals(60_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun aSeparateLinkDoesNotInheritAnotherLinksRequest() {
        val bluetoothProvider = FakeProvider()
        link(bluetoothProvider).handle(start) { }
        bluetoothProvider.stop() // Bluetooth closed without 0xFFFC.

        val wifiProvider = FakeProvider()
        val wifi = link(wifiProvider)
        wifi.tick { sent += it }
        nowMillis += 1_000
        wifi.tick { sent += it }

        assertFalse(wifiProvider.started)
        assertTrue(sent.isEmpty())
        assertEquals(60_000L, wifi.pollTimeout(60_000L))
    }

    @Test
    fun anInactiveLinkSendsNothingAndDoesNotPoll() {
        val link = link()
        link.tick { sent += it }

        assertFalse(provider.started)
        assertTrue(sent.isEmpty())
        assertEquals(60_000L, link.pollTimeout(60_000L))
        assertEquals(60_000L, Iap2LocationReporter(null, {}).pollTimeout(60_000L))
    }

    @Test
    fun aBurstOfIncomingMessagesStillSendsOneFixASecond() {
        // The loop ticks after every incoming message; at session start the iPhone sends dozens at once.
        val link = link()
        link.handle(start) { sent += it }
        repeat(12) { link.tick { sent += it } }
        assertEquals(1, sent.size)

        nowMillis += 400
        repeat(5) { link.tick { sent += it } }
        assertEquals(1, sent.size)
        assertEquals(600L, link.pollTimeout(60_000L))

        nowMillis += 600
        link.tick { sent += it }
        assertEquals(2, sent.size)
        assertEquals(1_000L, link.pollTimeout(60_000L))
    }

    @Test
    fun noFixMeansNothingIsSent() {
        provider.nmea = null
        val link = link()
        link.handle(start) { sent += it }
        link.tick { sent += it }

        assertTrue(provider.started)
        assertTrue(sent.isEmpty())
    }

    private class FakeProvider : Iap2LocationProvider {
        var started = false
        var requested: Set<Int>? = null
        var nmea: String? = "\$GPGGA,123519.00,4807.0380,N,01131.0000,E,1,08,1.0,545.4,M,0.0,M,,*00\r\n"
        override fun onRequested(components: Set<Int>) { requested = components }
        override fun start(): Boolean { started = true; return true }
        override fun stop() { started = false }
        override fun latestNmea() = nmea
    }
}
