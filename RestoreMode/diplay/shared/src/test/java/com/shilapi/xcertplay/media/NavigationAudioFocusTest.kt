package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class NavigationAudioFocusTest {
    private val events = mutableListOf<String>()
    private val focus = NavigationAudioFocus({ events += "request" }, { events += "abandon" }, idleNanos = 500)
    private val speech = byteArrayOf(0, 1, 0, -1)

    @Test fun noFocusForAnEmptyStreamOrDigitalSilence() {
        focus.advance(1000, 0)
        focus.pcmQueued(ByteArray(32), 0, 32, 0, 16)
        focus.advance(1000, 16)
        focus.close()
        assertTrue(events.isEmpty())
    }
    @Test fun aPromptHoldsFocusUntilItsPcmDrainsAndTheGracePeriodEnds() {
        focus.pcmQueued(speech, 0, 4, 0, 2)
        focus.pcmQueued(speech, 0, 4, 100, 4)
        focus.advance(1000, 3)
        assertEquals(listOf("request"), events)
        focus.advance(599, 4)
        assertEquals(listOf("request"), events)
        focus.advance(600, 4)
        focus.close()
        assertEquals(listOf("request", "abandon"), events)
    }
    @Test fun aRetainedStreamSendingSilenceDoesNotKeepNavigationVolumeSelected() {
        focus.pcmQueued(speech, 0, 4, 0, 2)
        focus.pcmQueued(ByteArray(32), 0, 32, 490, 18)
        focus.advance(500, 2)
        assertEquals(listOf("request", "abandon"), events)
    }
    @Test fun theNextPromptReacquiresAndExplicitStopReleasesOnce() {
        focus.pcmQueued(speech, 0, 4, 0, 2)
        focus.advance(500, 2)
        focus.pcmQueued(speech, 0, 4, 1000, 4)
        focus.close()
        focus.close()
        assertEquals(listOf("request", "abandon", "request", "abandon"), events)
    }
    @Test fun onlyTheWrittenPcmSliceAffectsTheFocusLease() {
        focus.pcmQueued(byteArrayOf(1, 0, 0, 1), 1, 2, 0, 1)
        assertTrue(events.isEmpty())
        focus.pcmQueued(speech, 1, 1, 0, 1)
        assertEquals(listOf("request"), events)
    }
}
