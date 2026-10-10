package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.Window
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DiPlayProjectionLaunchTest {
    @After fun cleanup() { VoyahCarPlayIntegration.install(null) }

    private fun activity(displayId: Int): DiPlayActivity {
        val activity = spy(Robolectric.buildActivity(DiPlayActivity::class.java).get())
        val window = mock(Window::class.java)
        val view = mock(View::class.java)
        val display = mock(Display::class.java)
        `when`(display.displayId).thenReturn(displayId)
        `when`(view.display).thenReturn(display)
        `when`(window.decorView).thenReturn(view)
        doReturn(window).`when`(activity).window
        return activity
    }

    private fun open(activity: DiPlayActivity) {
        DiPlayActivity::class.java.getDeclaredMethod("openProjection").apply { isAccessible = true }
            .invoke(activity)
    }

    @Test fun virtualDisplayDelegatesOnceAndDenialAllowsRetry() {
        val activity = activity(9)
        var requests = 0
        var reply: VoyahCarPlayIntegration.ProjectionResult? = null
        VoyahCarPlayIntegration.install(object : VoyahCarPlayIntegration.Host {
            override fun start() = Unit
            override fun stop() = Unit
            override fun openProjection(owner: Activity, displayId: Int,
                    result: VoyahCarPlayIntegration.ProjectionResult): Boolean {
                assertSame(activity, owner)
                assertEquals(9, displayId)
                requests++
                reply = result
                return true
            }
        })
        open(activity)
        open(activity)
        assertEquals(1, requests)
        verify(activity, never()).startActivity(any(Intent::class.java), any(Bundle::class.java))
        reply!!.complete(false)
        assertEquals(activity.getString(R.string.voyah_carplay_projection_launch_failed),
            ShadowToast.getTextOfLatestToast())
        open(activity)
        assertEquals(2, requests)
        reply!!.complete(true)
    }

    @Test fun physicalScreensKeepTheirDisplayAndExplicitProjectionComponent() {
        for (display in listOf(0, 1)) {
            val activity = activity(display)
            doNothing().`when`(activity).startActivity(any(Intent::class.java), any(Bundle::class.java))
            open(activity)
            val intent = ArgumentCaptor.forClass(Intent::class.java)
            val options = ArgumentCaptor.forClass(Bundle::class.java)
            verify(activity).startActivity(intent.capture(), options.capture())
            assertEquals(CarPlayHostActivity::class.java.name, intent.value.component!!.className)
            val launchOptions = ActivityOptions::class.java.getDeclaredMethod("fromBundle", Bundle::class.java)
                .invoke(null, options.value) as ActivityOptions
            assertEquals(display, launchOptions.launchDisplayId)
        }
    }

    @Test fun directSecurityExceptionDoesNotCrashTheApplication() {
        val activity = activity(9)
        doThrow(SecurityException("launchDisplayId=9 denied")).`when`(activity)
            .startActivity(any(Intent::class.java), any(Bundle::class.java))
        open(activity)
        assertEquals(activity.getString(R.string.voyah_carplay_projection_launch_failed),
            ShadowToast.getTextOfLatestToast())
    }
}
