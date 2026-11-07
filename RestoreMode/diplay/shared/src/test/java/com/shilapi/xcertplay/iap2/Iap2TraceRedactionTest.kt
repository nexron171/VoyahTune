package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2TraceRedactionTest {
    private val passphrase = "Car-Hotspot-Pass-4821"

    @Test
    fun accessoryWifiConfigurationHidesThePasswordAndPreservesUsefulFields() {
        val frame = accessoryWifiConfiguration("DIRECT-xy-DiPlay")

        val formatted = format(frame)

        assertPasswordHidden(formatted)
        assertTrue(formatted, "SSID: \"DIRECT-xy-DiPlay\"" in formatted)
        assertTrue(formatted, "securityType: u8=3" in formatted)
        assertEquals(passphrase, Iap2BodyReader.of(frame).string(2))
    }

    @Test
    fun wirelessStartSessionHidesTheNestedPasswordAndPreservesUsefulFields() {
        val frame = wirelessStartSession("DIRECT-xy-DiPlay")

        val formatted = format(frame)

        assertPasswordHidden(formatted)
        assertTrue(formatted, "SSID: \"DIRECT-xy-DiPlay\"" in formatted)
        assertTrue(formatted, "securityType: u8=3" in formatted)
        assertEquals(passphrase, Iap2BodyReader.of(frame).group(1).string(1))
    }

    @Test
    fun receivedWifiInformationAlsoHidesThePassword() {
        val frame = Iap2Messages.build(Iap2Endpoints.WIFI_INFORMATION) {
            u8(0, 0)
            u8(1, 3)
            string(2, "Home")
            string(3, passphrase)
        }

        val formatted = Iap2FrameFormatter.format(Iap2TraceDirection.RX, "wireless", frame)

        assertPasswordHidden(formatted)
        assertTrue(formatted, "WiFiInformation" in formatted)
        assertEquals(passphrase, Iap2BodyReader.of(frame).string(3))
    }

    @Test
    fun accessoryPasswordCannotLeakIntoTheHexPreviewWithAShortSsid() {
        assertPasswordHidden(format(accessoryWifiConfiguration("Home")))
    }

    @Test
    fun nestedPasswordCannotLeakIntoTheHexPreviewWithAShortSsid() {
        assertPasswordHidden(format(wirelessStartSession("Home")))
    }

    @Test
    fun malformedTopLevelBodyHidesItsHexPreview() {
        val frame = accessoryWifiConfiguration("Home")
        val truncated = Iap2Frame(frame.messageId, frame.payload.copyOf(frame.payload.size - 1))

        val formatted = format(truncated)

        assertTrue(formatted, "body=malformed TLV" in formatted)
        assertSecretBytesHidden(formatted)
    }

    @Test
    fun malformedWirelessGroupCannotExposeThePasswordThroughOpaqueFallback() {
        val frame = wirelessStartSession("Home")
        val parameters = Iap2ParameterList.parse(frame.payload).asList().map { parameter ->
            if (parameter.id == 1) {
                Iap2Parameter(parameter.id, parameter.payload.copyOf(parameter.payload.size - 1))
            } else {
                parameter
            }
        }
        val malformed = Iap2Frame(frame.messageId, Iap2ParameterList.of(parameters).encode())

        val formatted = format(malformed)

        assertTrue(formatted, "wireless: <redacted>" in formatted)
        assertSecretBytesHidden(formatted)
    }

    @Test
    fun malformedPasswordStringIsHiddenBeforeStringDecoding() {
        val frame = Iap2Frame(
            Iap2Endpoints.ACCESSORY_WIFI_CONFIGURATION.id,
            Iap2ParameterList.of(listOf(Iap2Parameter(2, passphrase.encodeToByteArray()))).encode(),
        )

        assertPasswordHidden(format(frame))
    }

    @Test
    fun anEmptyPasswordRemainsVisibleForAnOpenNetwork() {
        val frame = Iap2WirelessMessages.accessoryWiFiConfiguration(
            ssid = "Home",
            passphrase = "",
            channel = 6,
            securityType = 0,
        )

        val formatted = format(frame)

        assertTrue(formatted, "passphrase: \"\"" in formatted)
        assertTrue(formatted, "securityType: u8=0" in formatted)
    }

    @Test
    fun framesWithoutSecretsKeepTheirHexPreview() {
        val frame = Iap2ControlMessages.locationInformation("\$GPGGA,1")

        val formatted = format(frame)

        assertTrue(formatted, "raw-body=${hex(frame.payload)}" in formatted)
        assertFalse(formatted, "<redacted>" in formatted)
    }

    private fun accessoryWifiConfiguration(ssid: String): Iap2Frame =
        Iap2WirelessMessages.accessoryWiFiConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            channel = 36,
            securityType = 3,
        )

    private fun wirelessStartSession(ssid: String): Iap2Frame =
        Iap2CarPlayMessages.startSession(
            wireless = Iap2WirelessSessionParameters(
                ssid = ssid,
                passphrase = passphrase,
                channel = 36,
                ipAddresses = listOf("192.168.49.1"),
                securityType = 3,
            ),
            airPlayPort = 7000,
            publicKey = "pub",
            sourceVersion = "1.0",
        )

    private fun format(frame: Iap2Frame): String =
        Iap2FrameFormatter.format(Iap2TraceDirection.TX, "wireless", frame)

    private fun assertPasswordHidden(formatted: String) {
        assertTrue(formatted, "passphrase: <redacted>" in formatted)
        assertSecretBytesHidden(formatted)
    }

    private fun assertSecretBytesHidden(formatted: String) {
        assertFalse(formatted, passphrase in formatted)
        assertTrue(formatted, "raw-body=<redacted>" in formatted)
        // Shorter hex runs may coincide with unrelated protocol fields.
        passphrase.windowed(4).forEach { fragment ->
            assertFalse(formatted, hex(fragment.encodeToByteArray()) in formatted.lowercase())
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
}
