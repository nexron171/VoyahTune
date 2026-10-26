package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbChargingCurrentTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun storedValuesAreStableKeysAndUnknownOnesFallBackToNormal() {
        assertEquals(listOf("normal", "reduced", "low"), UsbChargingCurrent.entries.map { it.key })
        assertEquals(UsbChargingCurrent.NORMAL, UsbChargingCurrent.fromKey(null))
        assertEquals(UsbChargingCurrent.NORMAL, UsbChargingCurrent.fromKey("2"))
    }

    @Test fun theChoiceIsSavedAndDefaultsToNormal() {
        assertEquals(UsbChargingCurrent.NORMAL, AirPlayPersistence.loadUsbChargingCurrent(context))
        AirPlayPersistence.saveUsbChargingCurrent(context, UsbChargingCurrent.LOW)
        assertEquals(UsbChargingCurrent.LOW, AirPlayPersistence.loadUsbChargingCurrent(context))
    }
}
