package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioDuckingCommandTest {
    @Test fun decodesObservedIphoneCommands() {
        assertEquals(AudioDuckingCommand(-13.979399681091309, 500),
            AudioDuckingCommand.parse("duckAudio", mapOf("volume" to -13.979399681091309, "durationMs" to 500.0)))
        assertEquals(AudioDuckingCommand(0.0, 500),
            AudioDuckingCommand.parse("unduckAudio", mapOf("volume" to 0.0, "durationMs" to 500.0)))
    }

    @Test fun invalidCommandsCannotChangePlaybackGain() {
        assertNull(AudioDuckingCommand.parse("modesChanged", mapOf("volume" to -20, "durationMs" to 0)))
        assertNull(AudioDuckingCommand.parse("duckAudio", emptyMap()))
        for (volume in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.0)) {
            assertNull(AudioDuckingCommand.parse("duckAudio", mapOf("volume" to volume, "durationMs" to 0)))
        }
        assertNull(AudioDuckingCommand.parse("duckAudio", mapOf("volume" to -20, "durationMs" to -1)))
    }
}
