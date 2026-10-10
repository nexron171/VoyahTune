package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.BydBluetoothSuspend
import com.shilapi.xcertplay.hud.BydCallPopupGuard
import com.shilapi.xcertplay.media.AmbientMusicSettings
import com.shilapi.xcertplay.update.BackgroundUpdateScheduler
import com.shilapi.xcertplay.update.UpdateAvailability
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** New upstream entry points must not bypass the embedded application's policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahUpstreamGuardsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun defaultsPreserveExplicitThemeAndLocationChoices() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(CarPlayNightMode.VEHICLE, AirPlayPersistence.loadCarPlayNightMode(context))
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.DAY)
        AirPlayPersistence.saveLocationReportingEnabled(context, false)
        VoyahCarPlayDefaults.initialize(context)
        assertEquals(CarPlayNightMode.DAY, AirPlayPersistence.loadCarPlayNightMode(context))
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun standaloneUpdatesDoNotInitializeWorkManager() {
        UpdateAvailability.saveBackgroundChecksEnabled(context, true)
        assertFalse(UpdateAvailability.backgroundChecksEnabled(context))
        assertNull(UpdateAvailability.available(context, "0.0.0"))
        BackgroundUpdateScheduler.schedule(context)
    }

    @Test fun savedOemOptInsCannotEnableBydActions() {
        val preferences = context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("enabled", true).commit()
        assertFalse(AmbientMusicSettings.load(context).enabled)
        assertTrue(preferences.getBoolean("enabled", false))
        val owner = Any()
        BydBluetoothSuspend.suspend(context, owner, 0) { error("BYD lease must not start") }
        BydBluetoothSuspend.resume(context, owner)
        BydBluetoothSuspend.onAppOpened(context)
        assertFalse(BydBluetoothSuspend.resumeAndWait(context, null))
        assertFalse(BydBluetoothSuspend.isSuspendedByUs(context))
        BydCallPopupGuard.update(context, owner, true) { error("BYD popup lease must not start") }
        assertEquals("OFF", BydCallPopupGuard.status)
    }

    @Test fun nativeKeepsNavigationWheelOwnership() {
        VoyahCarPlayIntegration.install(object : VoyahCarPlayIntegration.Host {
            override fun start() = Unit
            override fun stop() = Unit
        })
        try {
            context.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", true).commit()
            assertFalse(NavigationWheelSettings.enabled(context))
        } finally {
            VoyahCarPlayIntegration.install(null)
        }
    }
}
