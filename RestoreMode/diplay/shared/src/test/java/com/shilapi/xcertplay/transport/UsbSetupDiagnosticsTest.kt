package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.network.CarPlayVpnService
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSetupDiagnosticsTest {
    @Test
    fun ncmSetupRetriesALostClaimAndPausesBetweenAttempts() {
        val pauses = mutableListOf<Long>()
        var calls = 0
        assertEquals(3, NcmUsbBridge.retryUsbSetup(pause = { pauses += it }) { ++calls == 3 })
        assertEquals(listOf(100L, 100L), pauses)

        pauses.clear()
        assertNull(NcmUsbBridge.retryUsbSetup(pause = { pauses += it }) { false })
        assertEquals(NcmUsbBridge.USB_SETUP_ATTEMPTS - 1, pauses.size)

        pauses.clear()
        assertEquals(1, NcmUsbBridge.retryUsbSetup(pause = { pauses += it }) { true })
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun rejectedModeRequestRecordsTheIphonesModeAndConfigurations() {
        val mode = byteArrayOf(3, 3, 3, 0)
        assertEquals(
            "CarPlay USB mode request rejected; current mode=3:3:3:0 usbVersion=15.04 configurations=[1[0/0:6.1.1x3]]",
            IphoneUsbHost.usbModeDiagnostic(4, mode, "15.04", listOf("1[0/0:6.1.1x3]")),
        )
        assertTrue(IphoneUsbHost.usbModeDiagnostic(-1, ByteArray(4), null, emptyList()).contains("current mode=unreadable(-1) usbVersion=unknown"))
    }

    @Test
    fun vpnAttachFailureNamesTheExceptionChain() {
        val failure = IllegalStateException("Cannot set address", IOException("Invalid argument"))
        val detail = CarPlayVpnService.attachFailure(failure)
        assertTrue(detail, detail.startsWith("IllegalStateException: Cannot set address <- IOException: Invalid argument at "))
    }
}
