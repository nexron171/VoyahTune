package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VoyahAudioRoutingTest {
    @Test fun simultaneousCarPlayRolesHaveSeparateOemVolumeRoutes() {
        val inputs = listOf("media", "default", "telephony", "speechRecognition")
        val routes = inputs.map {
            VoyahAudioRouting.stream(AudioChannelMapper.map(it, 100, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel)
        }
        assertEquals(listOf(3, 1, 6, 22), routes)
        assertEquals(4, routes.toSet().size)
    }

    @Test fun silentlyRemappedAssistantTrackIsReleasedBeforeUsageFallback() {
        val events = mutableListOf<String>()
        val result = LegacyAudioFallback.build(
            createLegacy = { 3 },
            isInitialized = { VoyahAudioRouting.accepted(22, it, true) },
            release = { events += "release:$it" },
            createFallback = { events += "fallback"; 16 },
        )
        assertEquals(16, result)
        assertEquals(listOf("release:3", "fallback"), events)
    }

    @Test fun acceptedRouteRequiresBothCorrectStreamAndInitializedTrack() {
        assertTrue(VoyahAudioRouting.accepted(22, 22, true))
        assertFalse(VoyahAudioRouting.accepted(22, 22, false))
        assertFalse(VoyahAudioRouting.accepted(6, 3, true))
    }
}
