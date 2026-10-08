package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahCarBrandingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun freshInstallAndBlankLabelUseVoyahFree() {
        VoyahCarPlayDefaults.initialize(context)
        assertEquals("Voyah Free", AirPlayPersistence.loadOemLabel(context))
        AirPlayPersistence.saveOemLabel(context, "")
        assertEquals("Voyah Free", AirPlayPersistence.loadOemLabel(context))
    }

    @Test
    fun existingVoyahTuneLabelMigratesOnlyOnce() {
        context.getSharedPreferences("voyah_carplay", Context.MODE_PRIVATE)
            .edit().putBoolean("initialized", true).commit()
        AirPlayPersistence.saveOemLabel(context, "VoyahTune")
        VoyahCarPlayDefaults.initialize(context)
        assertEquals("Voyah Free", AirPlayPersistence.loadOemLabel(context))

        AirPlayPersistence.saveOemLabel(context, "VoyahTune")
        VoyahCarPlayDefaults.initialize(context)
        assertEquals("VoyahTune", AirPlayPersistence.loadOemLabel(context))
    }

    @Test
    fun customNameSurvivesInitialization() {
        AirPlayPersistence.saveOemLabel(context, "My car")
        repeat(2) { VoyahCarPlayDefaults.initialize(context) }
        assertEquals("My car", AirPlayPersistence.loadOemLabel(context))
    }
}
