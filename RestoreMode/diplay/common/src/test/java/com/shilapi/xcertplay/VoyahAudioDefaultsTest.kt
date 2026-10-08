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
class VoyahAudioDefaultsTest {
    @Test fun previouslyInitializedInstallStopsInheritingMusicForNavigation() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("voyah_carplay", Context.MODE_PRIVATE)
            .edit().putBoolean("initialized", true).commit()
        AirPlayPersistence.saveNavigationStreamType(context, 3)
        VoyahCarPlayDefaults.initialize(context)
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun explicitAudioChannelsSurviveInitializationAndReopening() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveNavigationAudioChannel(context, 7)
        AirPlayPersistence.saveMediaAudioChannel(context, 4)
        repeat(2) { VoyahCarPlayDefaults.initialize(context) }
        assertEquals(7, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(4, AirPlayPersistence.loadMediaAudioChannel(context))
    }
}
