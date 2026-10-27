package com.shilapi.xcertplay

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarPlaySettingsAccessTest {
    private val app
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun prepare() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("voyah_carplay_availability", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_carplay_dock", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After
    fun cleanup() {
        CarPlayBackgroundSession.clear()
    }

    @Test
    fun ultraDefaultsOnAndSurvivesFeatureToggle() {
        assertTrue(CarPlaySettingsAccess.ultra(app))
        CarPlaySettingsAccess.setUltra(app, false)
        CarPlaySettingsAccess.setEnabled(app, false)
        CarPlaySettingsAccess.setEnabled(app, true)
        assertFalse(CarPlaySettingsAccess.ultra(app))
        CarPlaySettingsAccess.setUltra(app, true)
        assertTrue(CarPlaySettingsAccess.ultra(app))
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
    fun interfaceScaleDefaultsTo100AndUsesTheSharedSanitizedPreference() {
        assertEquals(listOf(75, 85, 100, 115), CarPlaySettingsAccess.uiScalePresets())
        assertEquals(100, CarPlaySettingsAccess.uiScalePercent(app))
        CarPlaySettingsAccess.setUiScalePercent(app, 85)
        assertEquals(85, AirPlayPersistence.loadUiScalePercent(app))
        AirPlayPersistence.saveUiScalePercent(app, 115)
        assertEquals(115, CarPlaySettingsAccess.uiScalePercent(app))
        CarPlaySettingsAccess.setUiScalePercent(app, 90)
        assertEquals(100, CarPlaySettingsAccess.uiScalePercent(app))
    }

    @Test
    fun fullscreenDefaultsOffAndSurvivesFeatureToggle() {
        assertFalse(CarPlaySettingsAccess.fullscreen(app))
        CarPlaySettingsAccess.setFullscreen(app, true)
        CarPlaySettingsAccess.setEnabled(app, false)
        assertTrue(CarPlaySettingsAccess.fullscreen(app))
        CarPlaySettingsAccess.setEnabled(app, true)
        assertTrue(CarPlaySettingsAccess.fullscreen(app))
        CarPlaySettingsAccess.setFullscreen(app, false)
        assertTrue(CarPlaySettingsAccess.isEnabled(app))
        assertFalse(CarPlaySettingsAccess.fullscreen(app))
    }

    @Test
    fun systemBarsRequireFullscreenAndNeverRestoreAHiddenPreferenceAfterItIsDisabled() {
        assertFalse(CarPlaySettingsAccess.hideSystemBars(app))
        CarPlaySettingsAccess.setHideSystemBars(app, true)
        assertFalse(CarPlaySettingsAccess.hideSystemBars(app))
        CarPlaySettingsAccess.setFullscreen(app, true)
        assertFalse(CarPlaySettingsAccess.hideSystemBars(app))
        CarPlaySettingsAccess.setHideSystemBars(app, true)
        assertTrue(CarPlaySettingsAccess.hideSystemBars(app))
        CarPlaySettingsAccess.setFullscreen(app, false)
        CarPlaySettingsAccess.setFullscreen(app, true)
        assertFalse(CarPlaySettingsAccess.hideSystemBars(app))
    }

    @Test
    fun astonMartinEnablesFullscreenLocksSystemBarsAndRequiresUltra() {
        CarPlaySettingsAccess.setDock(app, CarPlayDock.BOTTOM)
        assertEquals(CarPlayDock.BOTTOM, CarPlayDock.load(app))
        CarPlaySettingsAccess.setDock(app, CarPlayDock.DUAL_SIDES)
        assertTrue(CarPlaySettingsAccess.fullscreen(app))
        assertTrue(CarPlaySettingsAccess.hideSystemBars(app))
        assertEquals(CarPlayDock.DUAL_SIDES, CarPlayDock.load(app))
        CarPlaySettingsAccess.setHideSystemBars(app, false)
        assertTrue(CarPlaySettingsAccess.hideSystemBars(app))
        CarPlaySettingsAccess.setFullscreen(app, false)
        assertEquals(CarPlayDock.DRIVER_SIDE, CarPlayDock.load(app))
        assertFalse(CarPlaySettingsAccess.hideSystemBars(app))
        CarPlaySettingsAccess.setDock(app, CarPlayDock.DUAL_SIDES)
        CarPlaySettingsAccess.setUltra(app, false)
        assertEquals(CarPlayDock.DRIVER_SIDE, CarPlayDock.load(app))
        CarPlaySettingsAccess.setDock(app, CarPlayDock.DUAL_SIDES)
        assertEquals(CarPlayDock.DRIVER_SIDE, CarPlayDock.load(app))
        CarPlaySettingsAccess.setUltra(app, true)
        assertEquals(CarPlayDock.DRIVER_SIDE, CarPlayDock.load(app))
    }

    @Test
    fun anInactiveLegacyThemeCannotReappearWhenFullscreenOrUltraIsEnabled() {
        app.getSharedPreferences("diplay_carplay_dock", 0)
            .edit()
            .putString("dock", "DUAL_SIDES")
            .commit()
        assertEquals(CarPlayDock.DRIVER_SIDE, CarPlaySettingsAccess.dock(app))
        CarPlaySettingsAccess.setFullscreen(app, true)
        assertEquals(CarPlayDock.DRIVER_SIDE, CarPlaySettingsAccess.dock(app))
        assertFalse(CarPlaySettingsAccess.hideSystemBars(app))
        CarPlaySettingsAccess.setUltra(app, false)
        app.getSharedPreferences("diplay_carplay_dock", 0)
            .edit()
            .putString("dock", "DUAL_SIDES")
            .commit()
        CarPlaySettingsAccess.setUltra(app, false)
        CarPlaySettingsAccess.setUltra(app, true)
        assertEquals(CarPlayDock.DRIVER_SIDE, CarPlaySettingsAccess.dock(app))
    }

    @Test
    fun statusTracksSessionAndDisconnectWaitsForActualStop() {
        assertEquals(
            CarPlaySettingsAccess.ConnectionState.DISCONNECTED,
            CarPlaySettingsAccess.connectionState(),
        )
        var finishStop: (() -> Unit)? = null
        val stop: ((() -> Unit) -> Unit) = { completion -> finishStop = completion }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        assertEquals(
            CarPlaySettingsAccess.ConnectionState.CONNECTING,
            CarPlaySettingsAccess.connectionState(),
        )
        CarPlayBackgroundSession.active = true
        assertEquals(
            CarPlaySettingsAccess.ConnectionState.CONNECTED,
            CarPlaySettingsAccess.connectionState(),
        )
        var completed = false
        CarPlaySettingsAccess.disconnect { completed = true }
        assertFalse(completed)
        CarPlayBackgroundSession.clear()
        finishStop!!.invoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(completed)
        assertEquals(
            CarPlaySettingsAccess.ConnectionState.DISCONNECTED,
            CarPlaySettingsAccess.connectionState(),
        )
    }
}
