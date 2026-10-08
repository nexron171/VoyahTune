package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahSessionTransferTest {
    @Test
    fun retiringAnActivityTaskKeepsTheSessionServiceRunning() {
        val controller = Robolectric.buildService(DiPlaySessionService::class.java).create()
        try {
            val service = controller.get()
            service.onTaskRemoved(Intent())
            assertFalse(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }
}
