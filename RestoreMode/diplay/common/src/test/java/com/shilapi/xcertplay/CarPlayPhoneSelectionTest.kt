package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayPhoneSelectionTest {
    private val first = CarPlayPhone("00:11:22:33:44:AA", "Driver")
    private val second = CarPlayPhone("00:11:22:33:44:BB", "Passenger")
    private var phones = listOf(first, second)
    private var saved: String? = second.address.lowercase()
    private var state: CarPlayPhoneSelection.State? = null
    private val savedPhones = mutableListOf<CarPlayPhone>()
    private var starts = 0
    private val selection = CarPlayPhoneSelection(
        Handler(Looper.getMainLooper()),
        { phones },
        { saved },
        { saved = it.address; savedPhones.add(it) },
        { state = it },
        { starts++ },
    )

    @After
    fun cleanup() {
        selection.close()
    }

    @Test
    fun savedPhoneGetsFiveVisibleSecondsAndStartsExactlyOnce() {
        selection.resume()
        assertTrue(selection.holdsConnection())
        assertEquals(second, state!!.selected)
        assertTrue(savedPhones.isEmpty())
        for (seconds in 5 downTo 1) {
            assertEquals(seconds, state!!.seconds)
            assertEquals(0, starts)
            advance(1000)
        }
        assertNull(state)
        assertEquals(1, starts)
        assertFalse(selection.holdsConnection())
        advance(10000)
        assertEquals(1, starts)
    }

    @Test
    fun noPairedPhonesBypassesTheChoiceWithoutErasingSavedPhone() {
        phones = emptyList()
        selection.resume()
        assertFalse(selection.holdsConnection())
        assertEquals(second.address.lowercase(), saved)
        assertTrue(savedPhones.isEmpty())
        advance(5000)
        assertEquals(0, starts)
    }

    @Test
    fun oneDistinctPhoneIsSelectedWithoutDelay() {
        phones = listOf(first, first.copy(address = first.address.lowercase()))
        selection.resume()
        assertFalse(selection.holdsConnection())
        assertEquals(first.address, saved)
        assertEquals(listOf(first), savedPhones)
        assertNull(state)
    }

    @Test
    fun missingSavedPhoneSelectsFirstCandidateWithFullDelay() {
        saved = "FF:FF:FF:FF:FF:FF"
        selection.resume()
        assertTrue(selection.holdsConnection())
        assertEquals(first, state!!.selected)
        assertEquals(first.address, saved)
        advance(4999)
        assertEquals(0, starts)
        advance(1)
        assertEquals(1, starts)
    }

    @Test
    fun choosingAnotherPhonePausesAndRestartsTheCountdown() {
        selection.resume()
        selection.holdsConnection()
        advance(4000)
        assertEquals(1, state!!.seconds)
        assertEquals(listOf(first, second), selection.beginChoice()!!.phones)
        advance(20000)
        assertEquals(0, starts)
        selection.choose(first.address)
        selection.endChoice()
        assertEquals(first.address, saved)
        assertEquals(first, state!!.selected)
        assertEquals(5, state!!.seconds)
        advance(4999)
        assertEquals(0, starts)
        advance(1)
        assertEquals(1, starts)
    }

    @Test
    fun invalidChoiceAndDismissKeepTheSelectedPhoneAndRestartDelay() {
        selection.resume()
        selection.holdsConnection()
        advance(3000)
        selection.beginChoice()
        selection.choose("missing")
        selection.endChoice()
        assertEquals(second, state!!.selected)
        assertEquals(5, state!!.seconds)
        advance(5000)
        assertEquals(1, starts)
        assertTrue(savedPhones.isEmpty())
    }

    @Test
    fun returningFromBackgroundRequiresAVisibleCountdown() {
        selection.resume()
        selection.holdsConnection()
        advance(4000)
        selection.pause()
        advance(10000)
        assertEquals(0, starts)
        selection.resume()
        assertEquals(5, state!!.seconds)
        advance(5000)
        assertEquals(1, starts)
    }

    @Test
    fun disappearingSelectedPhoneResetsDelayForReplacement() {
        selection.resume()
        selection.holdsConnection()
        advance(4000)
        phones = listOf(first)
        advance(1000)
        assertEquals(first, state!!.selected)
        assertEquals(5, state!!.seconds)
        assertEquals(0, starts)
        advance(5000)
        assertEquals(1, starts)
    }

    @Test
    fun emptyListDuringCountdownWaitsUntilAChoiceReturns() {
        selection.resume()
        selection.holdsConnection()
        phones = emptyList()
        advance(10000)
        assertEquals(0, starts)
        assertNull(state!!.selected)
        assertNull(state!!.seconds)
        phones = listOf(second)
        advance(1000)
        assertEquals(5, state!!.seconds)
        advance(5000)
        assertEquals(1, starts)
    }

    @Test
    fun closingCancelsPendingConnectionAndCannotBeResumed() {
        selection.resume()
        selection.holdsConnection()
        advance(4000)
        selection.close()
        selection.resume()
        assertTrue(selection.holdsConnection())
        advance(10000)
        assertEquals(0, starts)
        assertNull(state)
    }

    @Test
    fun switchingToUsbCancelsWirelessTimer() {
        selection.resume()
        selection.holdsConnection()
        advance(4000)
        selection.bypass()
        assertFalse(selection.holdsConnection())
        advance(10000)
        assertEquals(0, starts)
        assertNull(state)
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }
}
