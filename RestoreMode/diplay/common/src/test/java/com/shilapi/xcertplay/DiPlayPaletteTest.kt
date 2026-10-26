package com.shilapi.xcertplay

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DiPlayPaletteTest {
    @Test fun waitingScreenDelegationKeepsItsExactLightAndDarkColors() {
        assertEquals(
            WaitingScreenColors(Color.rgb(12, 17, 27), Color.rgb(241, 245, 252), Color.rgb(168, 182, 202)),
            WaitingScreenColors.of(night = true),
        )
        assertEquals(
            WaitingScreenColors(Color.rgb(233, 238, 246), Color.rgb(28, 28, 30), Color.rgb(90, 100, 116)),
            WaitingScreenColors.of(night = false),
        )
    }
}
