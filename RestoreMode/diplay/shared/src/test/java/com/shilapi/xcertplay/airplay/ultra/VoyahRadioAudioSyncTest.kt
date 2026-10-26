package com.shilapi.xcertplay.airplay.ultra

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoyahRadioAudioSyncTest {
    private fun radio(playing: Boolean?, text: String? = null) = UltraRadioSnapshot(true,
        VoyahRadioOem.decode(mapOf(VoyahRadioOem.BAND to "FM", VoyahRadioOem.FREQUENCY to 10170L),
            when (playing) { true -> 3; false -> 2; null -> 6 }).copy(radioText = text))

    @Test fun anAlreadyPlayingRadioWaitsForTheSessionAndMetadataCannotStealAudioAgain() {
        val takes = AtomicInteger()
        val results = LinkedBlockingQueue<String>()
        VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, { results.offer(it) }).use { sync ->
            sync.observe(radio(true))
            assertEquals(0, takes.get())
            sync.attach()
            awaitResult(results)
            assertEquals(1, takes.get())
            sync.observe(radio(true, "New track"))
            sync.observe(radio(null))
            sync.observe(radio(true))
            assertNull(results.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(1, takes.get())
            sync.observe(radio(false))
            sync.observe(radio(true))
            awaitResult(results)
            assertEquals(2, takes.get())
        }
    }

    @Test fun aNativeStopBeforeAttachmentCancelsThePendingSelection() {
        val takes = AtomicInteger()
        VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, {}).use { sync ->
            sync.observe(radio(true))
            sync.observe(radio(false))
            sync.attach()
            sync.control(false) { assertEquals(0, takes.get()) }
        }
    }

    @Test fun cafPlaybackEchoIsIgnoredButTheNextNativeStartStillWorks() {
        val takes = AtomicInteger()
        val results = LinkedBlockingQueue<String>()
        VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, { results.offer(it) }).use { sync ->
            sync.attach()
            sync.control(true) { sync.observe(radio(true)) }
            assertEquals(0, takes.get())
            sync.observe(radio(false))
            sync.observe(radio(true))
            awaitResult(results)
            assertEquals(1, takes.get())
        }
    }

    @Test fun aStopWhileWaitingForThePhoneReleasesALateGrantWithoutBlockingTheCallback() {
        val requested = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val released = CountDownLatch(1)
        VoyahRadioAudioSync({ requested.countDown(); answer.await(2, TimeUnit.SECONDS) },
            { released.countDown() }, {}).use { sync ->
            sync.attach()
            sync.observe(radio(true))
            assertTrue(requested.await(1, TimeUnit.SECONDS))
            sync.observe(radio(false)) // Must return while takeAudio is still waiting.
            assertEquals(1L, answer.count)
            answer.countDown()
            assertTrue(released.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun deniedNativeStartDoesNotRetryOnPollingOrRdsUpdates() {
        val takes = AtomicInteger()
        val results = LinkedBlockingQueue<String>()
        VoyahRadioAudioSync({ takes.incrementAndGet(); false }, {}, { results.offer(it) }).use { sync ->
            sync.attach()
            sync.observe(radio(true))
            assertTrue(awaitResult(results).contains("accessoryGranted=false"))
            repeat(20) { sync.observe(radio(true, "Track $it")) }
            assertNull(results.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(1, takes.get())
        }
    }

    @Test fun disconnectInvalidatesAPendingSelectionAndClosedSessionsIgnoreCallbacks() {
        val takes = AtomicInteger()
        val sync = VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, {})
        sync.observe(radio(true))
        sync.observe(UltraRadioSnapshot())
        sync.attach()
        sync.close()
        sync.observe(radio(true))
        sync.attach()
        assertEquals(0, takes.get())
    }

    private fun awaitResult(results: LinkedBlockingQueue<String>): String {
        while (true) {
            val result = results.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("No ownership result")
            if (result.startsWith("RADIO EXTERNAL AUDIO")) return result
        }
    }
}
