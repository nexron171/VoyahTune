package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarPlaySettingsAccessTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun prepare() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After
    fun cleanup() {
        CarPlayBackgroundSession.clear()
    }

    @Test
    fun settingUsesTheSameValueInBothDirections() {
        CarPlaySettingsAccess.setAutoConnect(app, true)
        assertTrue(DiPlayPreferences.autoConnect(app))
        DiPlayPreferences.saveAutoConnect(app, false)
        assertFalse(CarPlaySettingsAccess.autoConnect(app))
        assertFalse(CarPlaySettingsAccess.hasPhone(app))
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        assertTrue(CarPlaySettingsAccess.hasPhone(app))
        assertEquals("Test iPhone", CarPlaySettingsAccess.phoneName(app))
    }

    @Test
    fun statusTracksSessionAndDisconnectWaitsForActualStop() {
        assertEquals(CarPlaySettingsAccess.ConnectionState.DISCONNECTED, CarPlaySettingsAccess.connectionState())
        var finishStop: (() -> Unit)? = null
        val stop: ((() -> Unit) -> Unit) = { completion -> finishStop = completion }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        assertEquals(CarPlaySettingsAccess.ConnectionState.CONNECTING, CarPlaySettingsAccess.connectionState())
        CarPlayBackgroundSession.active = true
        assertEquals(CarPlaySettingsAccess.ConnectionState.CONNECTED, CarPlaySettingsAccess.connectionState())
        var completed = false
        CarPlaySettingsAccess.disconnect { completed = true }
        assertFalse(completed)
        CarPlayBackgroundSession.clear()
        finishStop!!.invoke()
        assertTrue(completed)
        assertEquals(CarPlaySettingsAccess.ConnectionState.DISCONNECTED, CarPlaySettingsAccess.connectionState())
    }
}
