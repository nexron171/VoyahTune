package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun vehicleSubscriptionSurvivesUiTransferAndStopsWithService() {
        var starts = 0
        var stops = 0
        VoyahCarPlayIntegration.install(object : VoyahCarPlayIntegration.Host {
            override fun start() { starts++ }
            override fun stop() { stops++ }
        })
        val controller = Robolectric.buildService(DiPlaySessionService::class.java)
        try {
            controller.create()
            assertEquals(1, starts)
            controller.get().onTaskRemoved(Intent())
            assertEquals(0, stops)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            controller.destroy()
            assertEquals(1, stops)
        } finally {
            VoyahCarPlayIntegration.install(null)
        }
    }

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
