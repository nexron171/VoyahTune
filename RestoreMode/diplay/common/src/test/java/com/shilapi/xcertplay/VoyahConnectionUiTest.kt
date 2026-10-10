package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "ru-w1280dp-h720dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahConnectionUiTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var teardown: PausedExecutorService
    private lateinit var root: View

    @Before
    fun prepare() {
        CarPlayWindowUi.install(object : CarPlayWindowUi.Policy {
            override fun darkHostAppearance() = true
            override fun authenticationError(activity: Activity) = "Authentication unavailable"
            override fun insets(activity: Activity, view: View, system: androidx.core.graphics.Insets) = system
        })
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        teardown = PausedExecutorService()
        ReflectionHelpers.setField(activity, "teardownExecutor", teardown)
        ReflectionHelpers.setField(activity, "darkMode", false)
        root = ReflectionHelpers.callInstanceMethod(activity, "buildContentView")
        activity.setContentView(root)
    }

    @After
    fun cleanup() {
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        teardown.shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        CarPlayWindowUi.install(null)
    }

    @Test
    fun waitingScreenStaysDarkAndUsesCarPlayNamesEvenWithDayMode() {
        val panel = field("connectionPanel") as View
        val color = (panel.background as ColorDrawable).color
        assertTrue(Color.red(color) < 40 && Color.green(color) < 40 && Color.blue(color) < 40)
        val labels = views(field("connectionPanel") as View).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }.toList()
        assertTrue(labels.contains("CarPlay"))
        assertTrue(labels.contains("Вернуться в настройки"))
        assertTrue(labels.contains(activity.getString(android.R.string.cancel)))
        assertFalse(labels.any { it.contains("DiPlay", ignoreCase = true) })
        assertSame(button("Вернуться в настройки").parent, button(activity.getString(android.R.string.cancel)).parent)
    }

    @Test
    fun cancelClosesLiveControllerAndClearsSessionBeforeReturningHome() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.awaitClosed(anyLong())).thenReturn(true)
        val sink = mock(AndroidMediaSink::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        ReflectionHelpers.setField(activity, "sink", sink)
        CarPlayBackgroundSession.store(controller, sink, 1280, 720,
            activity, CarPlaySessionDisplay(1280, 720, 0, false, false, 1280, 720)) { it() }
        CarPlayBackgroundSession.active = true
        assertTrue(CarPlayBackgroundSession.hasSession())
        val cancel = button(activity.getString(android.R.string.cancel))
        cancel.performClick()
        assertFalse(cancel.isEnabled)
        assertTrue(field("startupRetryStopped") as Boolean)
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.active)
        assertNull(CarPlayBackgroundSession.snapshot())
        assertNull(field("controller"))
        assertNull(shadowOf(activity).nextStartedActivity)
        cancel.performClick()
        teardown.runAll()
        verify(sink, times(1)).close()
        verify(controller, times(1)).close()
        verify(controller).awaitClosed(anyLong())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        val home = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, home.component!!.className)
        assertEquals("home", home.getStringExtra("page"))
        verifyNoMoreInteractions(controller)
    }

    @Test
    fun returningToSettingsKeepsTheSessionAlive() {
        val controller = mock(CarPlayController::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1280, 720,
            activity, CarPlaySessionDisplay(1280, 720, 0, false, false, 1280, 720)) { it() }
        button("Вернуться в настройки").performClick()
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertSame(controller, CarPlayBackgroundSession.snapshot()!!.controller)
        verify(controller, never()).close()
        assertFalse(activity.isFinishing)
        assertEquals(DiPlayActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    private fun field(name: String): Any? = ReflectionHelpers.getField(activity, name)
    private fun button(label: String): Button = views(field("connectionPanel") as View).filterIsInstance<Button>().single { it.text == label }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
