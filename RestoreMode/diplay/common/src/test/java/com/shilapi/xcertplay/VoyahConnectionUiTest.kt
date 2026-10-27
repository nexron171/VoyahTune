package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
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
        CarPlaySessionHosts.unregister(activity)
        phoneSelection().close()
        VoyahCarPlayIntegration.install(null)
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
        val labels = views(panel)
            .filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE }
            .map { it.text.toString() }
            .toList()
        assertTrue(labels.contains("CarPlay"))
        assertTrue(labels.contains("Вернуться в настройки"))
        assertTrue(labels.contains(activity.getString(android.R.string.cancel)))
        assertFalse(labels.any { it.contains("DiPlay", ignoreCase = true) })
        assertSame(
            button("Вернуться в настройки").parent,
            button(activity.getString(android.R.string.cancel)).parent,
        )
    }

    @Test
    fun cancelFinishesImmediatelyAndClosesTheSessionWithoutOpeningHome() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.awaitClosed(anyLong())).thenReturn(true)
        val sink = mock(AndroidMediaSink::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        ReflectionHelpers.setField(activity, "sink", sink)
        CarPlayBackgroundSession.store(
            controller, sink, 1280, 720, activity,
            CarPlaySessionDisplay(1280, 720, 0, false, false, 1280, 720),
        ) { it() }
        CarPlayBackgroundSession.active = true
        assertTrue(CarPlayBackgroundSession.hasSession())
        val cancel = button(activity.getString(android.R.string.cancel))
        cancel.performClick()
        assertTrue(activity.isFinishing)
        assertFalse(cancel.isEnabled)
        assertTrue(field("startupRetryStopped") as Boolean)
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.active)
        assertNull(CarPlayBackgroundSession.snapshot())
        assertNull(field("controller"))
        assertNull(shadowOf(activity).nextStartedActivity)
        verify(controller, never()).close()
        cancel.performClick()
        teardown.runAll()
        verify(sink, times(1)).close()
        verify(controller, times(1)).close()
        verify(controller).awaitClosed(anyLong())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        verifyNoMoreInteractions(controller)
    }

    @Test
    fun cancelFinishesBeforeAControllerExists() {
        button(activity.getString(android.R.string.cancel)).performClick()

        assertTrue(activity.isFinishing)
        assertTrue(field("startupRetryStopped") as Boolean)
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun settingsDisconnectClosesTheRegisteredHostBeforeControllerCreation() {
        val closedDisplays = mutableListOf<Int>()
        VoyahCarPlayIntegration.install(object : VoyahCarPlayIntegration.Host {
            override fun start() {}
            override fun stop() {}
            override fun closeProjection(displayId: Int) { closedDisplays.add(displayId) }
        })
        CarPlaySessionHosts.register(activity)
        var completed = false

        CarPlaySettingsAccess.disconnect { completed = true }

        assertTrue(activity.isFinishing)
        assertNull(field("controller"))
        assertTrue(closedDisplays.isEmpty())
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(completed)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun notificationDisconnectAlsoClosesAPendingActivity() {
        CarPlaySessionHosts.register(activity)
        val lifecycle = Robolectric.buildService(DiPlaySessionService::class.java).create()
        try {
            val service = lifecycle.get()
            service.onStartCommand(Intent(DiPlaySessionService.ACTION_STOP), 0, 1)
            assertTrue(activity.isFinishing)
            assertTrue(shadowOf(service).isStoppedBySelf)
            teardown.runAll()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(CarPlayBackgroundSession.hasSession())
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun disconnectRetiresTheVirtualDisplayOwnerWithoutWaitingForTransport() {
        val controller = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        CarPlayBackgroundSession.store(
            controller, sink, 1280, 720, activity,
            CarPlaySessionDisplay(1280, 720, 0, false, false, 1280, 720),
        ) { completion ->
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "shutdown",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                ReflectionHelpers.ClassParameter.from(String::class.java, "test disconnect"),
                ReflectionHelpers.ClassParameter.from(kotlin.jvm.functions.Function0::class.java, completion),
            )
        }
        ReflectionHelpers.setField(activity, "controller", controller)
        ReflectionHelpers.setField(activity, "sink", sink)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "ownerDisplayId", 42)
        val closedDisplays = mutableListOf<Int>()
        VoyahCarPlayIntegration.install(object : VoyahCarPlayIntegration.Host {
            override fun start() {}
            override fun stop() {}
            override fun closeProjection(displayId: Int) { closedDisplays.add(displayId) }
        })
        CarPlaySessionHosts.register(activity)
        var completed = false

        CarPlaySettingsAccess.disconnect { completed = true }

        assertEquals(listOf(42), closedDisplays)
        assertTrue(activity.isFinishing)
        assertNull(field("controller"))
        assertNull(CarPlayBackgroundSession.snapshot())
        assertFalse(completed)
        verify(controller, never()).close()
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        verify(controller).close()
        verify(sink).close()
        assertTrue(completed)
    }

    @Test
    fun phonePickerChangesTheSavedPhoneAndRestartsVisibleCountdown() {
        val first = CarPlayPhone("00:11:22:33:44:55", "First iPhone")
        val second = CarPlayPhone("00:11:22:33:44:66", "Second iPhone")
        activity.connectionPhones = { listOf(first, second) }
        DiPlayPreferences.savePhone(activity, first.address, first.name)
        phoneSelection().resume()
        assertTrue(phoneSelection().holdsConnection())
        val countdown = field("phoneCountdownView") as TextView
        assertEquals(View.VISIBLE, countdown.visibility)
        assertEquals(activity.getString(R.string.voyah_carplay_phone_countdown, first.name, 5), countdown.text)

        button(activity.getString(R.string.choose_your_iphone)).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertNull(field("controller"))
        dialog.listView.performItemClick(null, 1, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
        assertEquals(second.address, DiPlayPreferences.phoneAddress(activity))
        assertEquals(activity.getString(R.string.voyah_carplay_phone_countdown, second.name, 5), countdown.text)
        button(activity.getString(android.R.string.cancel)).performClick()
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(activity.isFinishing)
        assertNull(field("controller"))
        assertEquals(View.GONE, countdown.visibility)
    }

    @Test
    fun returningToSettingsKeepsTheSessionAlive() {
        val controller = mock(CarPlayController::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        CarPlayBackgroundSession.store(
            controller, mock(AndroidMediaSink::class.java), 1280, 720, activity,
            CarPlaySessionDisplay(1280, 720, 0, false, false, 1280, 720),
        ) { it() }
        button("Вернуться в настройки").performClick()
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertSame(controller, CarPlayBackgroundSession.snapshot()!!.controller)
        verify(controller, never()).close()
        assertFalse(activity.isFinishing)
        assertEquals(
            DiPlayActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component!!.className,
        )
    }

    private fun field(name: String): Any? = ReflectionHelpers.getField(activity, name)

    private fun phoneSelection(): CarPlayPhoneSelection =
        ReflectionHelpers.callInstanceMethod(activity, "getPhoneSelection")

    private fun button(label: String): Button = views(field("connectionPanel") as View)
        .filterIsInstance<Button>()
        .single { it.text == label }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                yieldAll(views(view.getChildAt(index)))
            }
        }
    }
}
