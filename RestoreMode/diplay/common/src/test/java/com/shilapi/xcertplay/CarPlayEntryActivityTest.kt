package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarPlayEntryActivityTest {
    @After
    fun clearSession() {
        CarPlayBackgroundSession.clear()
    }

    @Test
    fun runningSessionOpensProjectionWithoutStoppingTransport() {
        val stop: ((() -> Unit) -> Unit) = { error("Routing must not stop the transport") }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        checkEntry(CarPlayHostActivity::class.java.name)
    }

    @Test
    fun idleEntryOpensTheDiPlayHomeScreen() {
        CarPlayBackgroundSession.clear()
        checkEntry(DiPlayActivity::class.java.name)
    }

    @Test
    fun displayTransferWaitsForTeardownButSameDisplayPreservesSession() {
        ReflectionHelpers.setField(CarPlayBackgroundSession, "ownerDisplayId", 9)
        var shutdownFinished: (() -> Unit)? = null
        var restarts = 0
        val stop: ((() -> Unit) -> Unit) = { done -> shutdownFinished = done }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        assertFalse(CarPlayBackgroundSession.stopForDisplayChange(9) { restarts++ })
        assertNull(shutdownFinished)
        assertTrue(CarPlayBackgroundSession.stopForDisplayChange(0) { restarts++ })
        assertEquals(0, restarts)
        CarPlayBackgroundSession.clear()
        shutdownFinished!!.invoke()
        assertEquals(1, restarts)
    }

    private fun checkEntry(expected: String) {
        val controller = Robolectric.buildActivity(CarPlayEntryActivity::class.java).create()
        try {
            val activity = controller.get()
            assertEquals(expected, shadowOf(activity).nextStartedActivity.component!!.className)
            assertTrue(activity.isFinishing)
        } finally {
            controller.destroy()
        }
    }
}
