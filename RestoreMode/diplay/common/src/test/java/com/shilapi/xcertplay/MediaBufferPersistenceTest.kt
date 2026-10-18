package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MediaBufferPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().apply()
    }

    @Test
    fun freshInstallUsesOneSecondForBothTransportsWithoutSavingAChoice() {
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertFalse(preferences.contains("media_buffer_ms"))
        assertFalse(preferences.contains("wired_media_buffer_ms"))
    }

    @Test
    fun initializedInstallWithoutABufferChoiceUsesOneSecond() {
        context.getSharedPreferences("voyah_carplay", Context.MODE_PRIVATE)
            .edit().putBoolean("initialized", true).apply()
        VoyahCarPlayDefaults.initialize(context)
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }

    @Test
    fun savedSharedPresetsSurviveInitializationIncludingThePreviousDefault() {
        for (millis in listOf(300, 500, 1000)) {
            preferences.edit().putInt("media_buffer_ms", millis).apply()
            VoyahCarPlayDefaults.initialize(context)
            assertEquals(millis, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
            assertEquals(millis, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        }
    }

    @Test
    fun wiredAndWirelessAreSavedSeparately() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = 50)
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 1000)
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test
    fun sharedDelayFromEarlierVersionsCarriesOverToWired() {
        preferences.edit().putInt("media_buffer_ms", 50).apply()
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        // Unsupported saved values keep the transport's compatibility fallback.
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test
    fun changingWirelessDoesNotMoveAnInheritedWiredDelay() {
        preferences.edit().putInt("media_buffer_ms", 500).apply()
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 1000)
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test
    fun changingWirelessKeepsTheNewWiredDefault() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 300)
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }

    @Test
    fun wirelessRejectsWiredOnlyPresets() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 100)
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }
}
