package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayReconnectLifecycleTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private lateinit var teardown: PausedExecutorService

    @Before
    fun prepare() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controller = mock(CarPlayController::class.java)
        ReflectionHelpers.setField(activity, "controller", controller)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        teardown = PausedExecutorService()
        ReflectionHelpers.setField(activity, "teardownExecutor", teardown)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        CarPlaySessionHosts.register(activity)
    }

    @After
    fun cleanup() {
        CarPlaySessionHosts.unregister(activity)
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.getField<Handler>(activity, "mainHandler")
            .removeCallbacksAndMessages(null)
        teardown.shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
    }

    @Test
    fun bluetoothAfterEightHoursStopsTheOldAttemptWithoutRunningItsRetry() {
        var retries = 0
        CarPlayBackgroundSession.reconnectTimeout.begin()
        ReflectionHelpers.setField(activity, "pendingReconnect", Runnable { retries++ })
        ReflectionHelpers.setField(activity, "reconnectScheduled", true)
        ReflectionHelpers.setField(activity, "reconnectWaitsForBluetooth", true)
        ShadowSystemClock.advanceBy(Duration.ofHours(8))

        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(activity, "bluetoothOnReceiver")
        receiver.onReceive(activity, Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(
            BluetoothAdapter.EXTRA_STATE,
            BluetoothAdapter.STATE_ON,
        ))

        assertTrue(activity.isFinishing)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, retries)
        teardown.runAll()
        verify(controller).close()
    }

    @Test
    fun bluetoothWithinFiveMinutesRunsThePendingRetry() {
        var retries = 0
        CarPlayBackgroundSession.reconnectTimeout.begin()
        ReflectionHelpers.setField(activity, "pendingReconnect", Runnable { retries++ })
        ReflectionHelpers.setField(activity, "reconnectScheduled", true)
        ReflectionHelpers.setField(activity, "reconnectWaitsForBluetooth", true)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))

        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(activity, "bluetoothOnReceiver")
        receiver.onReceive(activity, Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(
            BluetoothAdapter.EXTRA_STATE,
            BluetoothAdapter.STATE_ON,
        ))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, retries)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun connectedSessionCancelsRecoveryBeforeTheOldDeadline() {
        CarPlayBackgroundSession.reconnectTimeout.begin()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        val listener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(
            activity,
            "createSessionListener",
            ReflectionHelpers.ClassParameter(Int::class.javaPrimitiveType, 0),
        )
        listener.onSessionActive(mock(AirPlaySession::class.java))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))

        assertTrue(CarPlayBackgroundSession.reconnectTimeout.check())
        assertFalse(activity.isFinishing)
        assertTrue(CarPlayBackgroundSession.active)
    }

    @Test
    fun sessionArrivingAfterTheDeadlineIsClosedInsteadOfResettingTheTimeout() {
        CarPlayBackgroundSession.reconnectTimeout.begin()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        val listener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(
            activity,
            "createSessionListener",
            ReflectionHelpers.ClassParameter(Int::class.javaPrimitiveType, 0),
        )
        listener.onSessionActive(mock(AirPlaySession::class.java))

        assertTrue(activity.isFinishing)
        assertFalse(CarPlayBackgroundSession.active)
        teardown.runAll()
        verify(controller).close()
    }

    @Test
    fun controllerReplacementKeepsTheOriginalRecoveryDeadline() {
        CarPlayBackgroundSession.reconnectTimeout.begin()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        CarPlayBackgroundSession.clear(keepOwner = true)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))

        assertFalse(CarPlayBackgroundSession.reconnectTimeout.check())
        assertTrue(activity.isFinishing)
        teardown.runAll()
        verify(controller).close()
    }
}
