package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayBackgroundRecoveryTest {
    private val hosts = mutableListOf<CarPlayHostActivity>()
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private lateinit var teardown: PausedExecutorService

    @Before
    fun prepare() {
        CarPlayBackgroundSession.clear()
        VoyahCarPlayIntegration.install(object : VoyahCarPlayIntegration.Host {
            override fun start() = Unit
            override fun stop() = Unit
        })
        host = newHost()
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        store(host)
        ReflectionHelpers.setField(host, "controller", controller)
        ReflectionHelpers.setField(host, "sink", sink)
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = ReflectionHelpers.callConstructor<Any>(sizeClass,
            ClassParameter.from(Int::class.javaPrimitiveType, 1280),
            ClassParameter.from(Int::class.javaPrimitiveType, 720))
        ReflectionHelpers.setField(host, "activeDisplaySize", size)
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
        teardown = PausedExecutorService()
        ReflectionHelpers.setField(host, "teardownExecutor", teardown)
    }

    @After
    fun cleanup() {
        CarPlayBackgroundSession.clear()
        hosts.forEach { activity ->
            ReflectionHelpers.getField<Handler>(activity, "mainHandler")
                .removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        }
        VoyahCarPlayIntegration.install(null)
    }

    @Test
    fun destroyedHostReleasesRecoveryOwnershipAfterTransportTeardown() {
        ReflectionHelpers.setField(host, "mDestroyed", true)
        restart()
        assertTrue(CarPlayBackgroundSession.hasSession())
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()

        verify(controller).close()
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.isOwner(host))
        assertNull(CarPlayBackgroundSession.snapshot())
        assertEquals(DiPlaySessionService::class.java.name,
            shadowOf(host.application).nextStoppedService?.component?.className)
        assertTrue(teardown.isShutdown)
        assertTrue(ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").isShutdown)
        val replacement = newHost()
        store(replacement)
        assertTrue(CarPlayBackgroundSession.isOwner(replacement))
    }

    @Test
    fun oldRestartCompletionCannotClearANewerBackgroundSession() {
        host.finish()
        restart()
        val replacement = newHost()
        val current = mock(CarPlayController::class.java)
        store(replacement, current)
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(CarPlayBackgroundSession.isOwner(replacement))
        assertSame(current, CarPlayBackgroundSession.snapshot()?.controller)
        verify(current, never()).close()
        assertNull(shadowOf(host.application).nextStoppedService)
    }

    @Test
    fun lateStopActionFromTheOldHostCannotCloseAnAdoptedSession() {
        assertTrue(ReflectionHelpers.callInstanceMethod(host, "adoptBackgroundSession"))
        val oldStop = ReflectionHelpers.getField<((() -> Unit) -> Unit)>(
            CarPlayBackgroundSession, "stopAction")
        val replacement = newHost()
        store(replacement)
        var completed = false

        oldStop { completed = true }
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(completed)
        assertFalse(host.isFinishing)
        assertTrue(CarPlayBackgroundSession.isOwner(replacement))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
        assertNull(shadowOf(host.application).nextStoppedService)
    }

    @Test
    fun backgroundLayoutsCannotLeaveAResizeBlockingTheNextConnection() {
        ReflectionHelpers.callInstanceMethod<Unit>(host, "scheduleDisplaySize",
            ClassParameter.from(Int::class.javaPrimitiveType, 1920),
            ClassParameter.from(Int::class.javaPrimitiveType, 720))
        assertNull(ReflectionHelpers.getField(host, "pendingDisplaySize"))

        ReflectionHelpers.setField(host, "isActivityStarted", true)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "scheduleDisplaySize",
            ClassParameter.from(Int::class.javaPrimitiveType, 1920),
            ClassParameter.from(Int::class.javaPrimitiveType, 720))
        ReflectionHelpers.callInstanceMethod<Unit>(host, "onStop")
        assertNull(ReflectionHelpers.getField(host, "pendingDisplaySize"))
    }

    @Test
    fun aCloseFailureDoesNotLeaveTheDestroyedOwnerHoldingTheStartupSlot() {
        ReflectionHelpers.setField(host, "mDestroyed", true)
        doThrow(IllegalStateException("controller teardown failed")).`when`(controller).close()
        restart()
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.isOwner(host))
    }

    @Test
    fun anIdleReleasedOwnerCanStartAgainAfterItsRecoveryDeadlineExpires() {
        assertTrue(CarPlayBackgroundSession.reconnectTimeout.begin())
        CarPlayBackgroundSession.clear(controller, keepOwner = true)
        assertTrue(CarPlayBackgroundSession.releaseRecoveryOwner(host))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))

        assertFalse(CarPlayBackgroundSession.hasSession())
        assertTrue(CarPlayBackgroundSession.reconnectTimeout.check())
        assertTrue(CarPlayBackgroundSession.reconnectTimeout.begin())
        val replacement = newHost()
        store(replacement)
        assertTrue(CarPlayBackgroundSession.isOwner(replacement))
    }

    @Test
    fun releasingAnOwnerForHandoffDoesNotExtendItsActiveRecoveryDeadline() {
        assertTrue(CarPlayBackgroundSession.reconnectTimeout.begin())
        CarPlayBackgroundSession.clear(controller, keepOwner = true)
        assertTrue(CarPlayBackgroundSession.releaseRecoveryOwner(host))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        val replacement = newHost()
        store(replacement)

        assertTrue(CarPlayBackgroundSession.reconnectTimeout.check())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        assertFalse(CarPlayBackgroundSession.reconnectTimeout.check())
    }

    @Test
    fun aLateIdleDisconnectCompletionDoesNotClearANewOwnerOrResetItsDeadline() {
        CarPlayBackgroundSession.clear(controller, keepOwner = true)
        assertTrue(CarPlayBackgroundSession.releaseRecoveryOwner(host))
        var completed = false
        CarPlaySessionHosts.disconnect { completed = true }
        val replacement = newHost()
        val current = mock(CarPlayController::class.java)
        store(replacement, current)
        assertTrue(CarPlayBackgroundSession.reconnectTimeout.begin())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))

        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(completed)
        assertTrue(CarPlayBackgroundSession.isOwner(replacement))
        assertSame(current, CarPlayBackgroundSession.snapshot()?.controller)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        assertFalse(CarPlayBackgroundSession.reconnectTimeout.check())
    }

    private fun restart() {
        ReflectionHelpers.callInstanceMethod<Unit>(host, "restartCarPlay",
            ClassParameter.from(String::class.java, "test transport loss"))
    }

    private fun newHost(): CarPlayHostActivity =
        Robolectric.buildActivity(CarPlayHostActivity::class.java).get().also(hosts::add)

    private fun store(owner: CarPlayHostActivity, value: CarPlayController = controller) {
        CarPlayBackgroundSession.store(value, sink, 1280, 720, owner,
            CarPlaySessionDisplay(1280, 720, 0, false, false, 1280, 720)) { completion ->
                CarPlayBackgroundSession.clear(value)
                completion()
            }
    }
}
