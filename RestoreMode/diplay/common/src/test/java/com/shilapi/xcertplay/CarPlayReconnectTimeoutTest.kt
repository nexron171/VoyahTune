package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayReconnectTimeoutTest {
    private var elapsedMillis = 0L
    private var expirations = 0
    private val timeout = CarPlayReconnectTimeout(
        Handler(Looper.getMainLooper()),
        { elapsedMillis },
        { expirations++ },
    )

    @Test
    fun retriesAreAllowedUntilExactlyFiveMinutes() {
        assertTrue(timeout.begin())
        elapsedMillis = 299_999L
        assertTrue(timeout.check())
        elapsedMillis = 300_000L
        assertFalse(timeout.check())
        assertEquals(1, expirations)
        assertFalse(timeout.begin())
        assertFalse(timeout.check())
        assertEquals(1, expirations)
    }

    @Test
    fun repeatedFailuresDoNotExtendTheDeadline() {
        timeout.begin()
        elapsedMillis = 240_000L
        assertTrue(timeout.begin())
        elapsedMillis = 300_000L
        assertFalse(timeout.check())
    }

    @Test
    fun timerStopsAnAttemptEvenWithoutAnotherFailure() {
        timeout.begin()
        elapsedMillis = 300_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertEquals(1, expirations)
        assertFalse(timeout.check())
    }

    @Test
    fun wakeAfterLongSleepRejectsRetryBeforeTheHandlerTimerRuns() {
        timeout.begin()
        elapsedMillis = Duration.ofHours(8).toMillis()
        assertFalse(timeout.check())
        assertEquals(1, expirations)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertEquals(1, expirations)
    }

    @Test
    fun successfulConnectionCancelsTheTimerAndTheNextLossGetsANewWindow() {
        timeout.begin()
        elapsedMillis = 240_000L
        timeout.reset()
        elapsedMillis = 600_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertEquals(0, expirations)
        assertTrue(timeout.check())
        assertTrue(timeout.begin())
        elapsedMillis = 899_999L
        assertTrue(timeout.check())
        elapsedMillis = 900_000L
        assertFalse(timeout.check())
    }

    @Test
    fun manualRetryCanStartANewWindowAfterExpiry() {
        timeout.begin()
        elapsedMillis = 300_000L
        assertFalse(timeout.check())
        timeout.reset()
        assertTrue(timeout.begin())
        elapsedMillis = 599_999L
        assertTrue(timeout.check())
        elapsedMillis = 600_000L
        assertFalse(timeout.check())
        assertEquals(2, expirations)
    }
}
