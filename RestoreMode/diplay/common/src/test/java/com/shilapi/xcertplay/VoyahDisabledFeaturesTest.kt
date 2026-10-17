package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.BydCarMusicRhythm
import com.shilapi.xcertplay.media.AmbientMusicSettings
import com.shilapi.xcertplay.network.CarHotspotConfigRead
import com.shilapi.xcertplay.network.CarHotspotConfigWrite
import com.shilapi.xcertplay.network.HotspotCredentials
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahDisabledFeaturesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun savedBubbleCannotShowEvenWithOverlayPermission() {
        AirPlayPersistence.saveLauncherBubble(context, true)
        ShadowSettings.setCanDrawOverlays(true)
        try {
            LauncherBubble.attach(context)
            LauncherBubble.scheduleShow()
            assertFalse(LauncherBubble.show())
            assertFalse(LauncherBubble.shown)
            assertFalse(AirPlayPersistence.loadLauncherBubble(context))
            assertTrue(context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
                .getBoolean("launcher_bubble", false))
        } finally {
            ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun savedRhythmCannotStartVendorWorkersOrErasePreferences() {
        val preferences = context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("enabled", true).putBoolean("carRhythm", true)
            .putBoolean("carRhythmFeed", true).commit()
        val effective = AmbientMusicSettings.load(context)
        assertFalse(effective.enabled)
        assertFalse(effective.carRhythm)
        assertFalse(effective.carRhythmFeed)
        val support = BydCarMusicRhythm.checkSupport(context)
        assertTrue(support.isDone)
        assertFalse(support.get())
        BydCarMusicRhythm.setPlaying(context, true)
        BydCarMusicRhythm.sendBands(context, IntArray(16) { 8 })
        assertTrue(preferences.getBoolean("carRhythm", false))
        assertTrue(preferences.getBoolean("carRhythmFeed", false))
    }

    @Test
    fun hotspotImportAndWriteStayUnavailableBeforeAdbOrSystemAccess() {
        val credentials = HotspotCredentials("Car", "password1", ManualHotspotSecurity.WPA2)
        assertFalse(CarHotspotConfigWrite.supported())
        assertEquals(CarHotspotConfigRead.Reason.UNSUPPORTED_SDK,
            CarHotspotConfigRead.read(context, log = {}).reason)
        assertEquals(CarHotspotConfigWrite.Result.UNSUPPORTED_SDK,
            CarHotspotConfigWrite.write(context, credentials,
                isCancelled = { error("No worker should start") }, log = {}).result)
    }
}
