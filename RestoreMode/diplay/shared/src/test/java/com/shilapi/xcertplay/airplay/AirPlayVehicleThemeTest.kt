package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import com.shilapi.xcertplay.transport.forWirelessLink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayVehicleThemeTest {
    private val theme = AirPlayVehicleTheme.DUAL_SIDE_DOCKS
    private val identity = Iap2IdentificationConfig(
        name = "test",
        modelIdentifier = "test",
        manufacturer = "test",
        serialNumber = "test",
        firmwareVersion = "1.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 1,
        carPlayThemeAssetIdentifier = theme.assetIdentifier,
    )

    @Test
    fun themeIsAnnouncedOnUsbAndBothWirelessIdentificationLinks() {
        val wired = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(identity))
        assertEquals("44C9FA31EC", wired.group(16).string(6))
        val wireless = Iap2WirelessIdentification("02:00:00:00:00:02", "test")
        Iap2WirelessLinkRole.entries.forEach { role ->
            val frame = Iap2IdentificationClient.identificationInformation(identity.forWirelessLink(role, wireless))
            val reader = Iap2BodyReader.of(frame)
            assertEquals("44C9FA31EC", reader.group(24).string(6))
            assertFalse(reader.has(16))
        }
        val ordinary = identity.copy(carPlayThemeAssetIdentifier = null)
        val reader = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(ordinary))
        assertFalse(reader.group(16).has(6))
    }

    @Test
    fun wiredAndWirelessStartCarryTheSamePublishedThemeVersion() {
        val wired = Iap2WiredControlClient.carPlayStartSession(
            Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf("fe80::2"),
                airPlayPort = 7000,
                publicKey = "abcd",
                sourceVersion = "950.7.1",
                sdkVersion = theme.sdkVersion,
                clusterAsset = theme.clusterAsset,
            ),
        )
        val wireless = Iap2WirelessControlClient.carPlayStartSession(
            Iap2WirelessCarPlayEndpoint(
                ssid = "test",
                passphrase = "test-password",
                channel = 36,
                security = Iap2WirelessSecurity.WPA_WPA2,
                ipAddresses = listOf("192.168.2.1"),
                airPlayPort = 7000,
                deviceIdentifier = "02:00:00:00:00:02",
                publicKey = "abcd",
                sourceVersion = "950.7.1",
                sdkVersion = theme.sdkVersion,
                clusterAsset = theme.clusterAsset,
            ),
        )
        listOf(wired, wireless).forEach { frame ->
            val reader = Iap2BodyReader.of(frame)
            assertEquals("19.0", reader.string(6))
            assertEquals("44C9FA31EC", reader.group(7).string(0))
            assertEquals(985L, reader.group(7).u32(1))
            assertEquals("950.7.1", reader.string(5))
        }
    }

    @Test
    fun videoPanelTouchAndViewAreaCommandShareTheThemeCoordinateSpace() {
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "950.7.1",
            main = theme.mainDisplay(60, 1),
            vehicleTheme = theme,
        )
        val info = AirPlayInfoPlist.build(config)
        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals("Center_Display", display["uuid"])
        assertEquals(1920, display["widthPixels"])
        assertEquals(720, display["heightPixels"])
        val area = (display["viewAreas"] as List<*>).single() as Map<*, *>
        assertEquals(2, area["viewAreaStatusBarEdge"])
        val safe = area["safeArea"] as Map<*, *>
        assertEquals(1920, safe["widthPixels"])
        assertEquals(720, safe["heightPixels"])
        assertFalse(display.containsKey("viewAreaTransitionControl"))

        val panels = (info["displayPanels"] as List<*>).map { it as Map<*, *> }
        assertEquals("Center_Display", panels.first()["uid"])
        val stream = (panels.first()["initialVideoStreams"] as List<*>).single() as Map<*, *>
        assertEquals("Center_Display", stream["uuid"])
        assertEquals(emptyList<Any>(), panels.last()["initialVideoStreams"])
        val devices = (info["hidDevices"] as List<*>).map { it as Map<*, *> }
        devices.forEach { assertEquals("Center_Display", it["displayUUID"]) }
        assertArrayEquals(
            AirPlayHid.touchHidDevice(1920, 720, "Center_Display")["hidDescriptor"] as ByteArray,
            devices.first()["hidDescriptor"] as ByteArray,
        )
        val command = AirPlayInfoPlist.viewAreaCommand(0, 1, config.mainDisplayIdentifier)
        assertEquals("Center_Display", (command["params"] as Map<*, *>)["uuid"])

        val ordinary = config.copy(vehicleTheme = null)
        assertNull(ordinary.vehicleTheme)
        assertEquals(AirPlayInfoPlist.MAIN_UUID, ordinary.mainDisplayIdentifier)
    }
}
