package com.shilapi.xcertplay.airplay.ultra

import org.junit.Assert.*
import org.junit.Test

class VoyahRadioOemTest {
    @Test fun radioTextIsStationSpecificAndNeverSubstitutesADuplicatePsNameOrFrequency() {
        val state = VoyahRadioOem.decode(mapOf(VoyahRadioOem.BAND to "FM", VoyahRadioOem.FREQUENCY to 9880,
            VoyahRadioOem.TITLE to "FM 98.8 MHz", VoyahRadioOem.STATION_NAME to "ROMANTIK"), 3)
        fun reading(text: String?, band: VoyahRadioOem.Band = VoyahRadioOem.Band.FM, frequency: Long = 9880) =
            VoyahRadioOem.RadioText(band, frequency, text)
        val playing = VoyahRadioOem.withRadioText(state, reading("Bee Gees - Stayin Alive\u0000  "))
        assertEquals("Bee Gees - Stayin Alive", playing.radioText)
        for (r in listOf(null, reading(null), reading("\u0000  "), reading("ROMANTIK"), reading("FM 98.8 MHz"),
            reading("Previous song", frequency = 10050), reading("AM text", band = VoyahRadioOem.Band.AM)))
            assertNull(VoyahRadioOem.withRadioText(playing, r).radioText)
    }

    @Test fun fixedWidthRdsPaddingDoesNotReachStationLabelsOrBecomeAnEmptyName() {
        val state = VoyahRadioOem.decode(mapOf(VoyahRadioOem.STATION_NAME to "Like FM\u0000"), 3)
        assertEquals("Like FM", state.stationName)
        val station = VoyahRadioOem.station("8790", "FM 87.9 MHz", "Like FM\u0000", 1)!!
        assertEquals("Like FM · FM 87.9 MHz", station.listTitle)
        assertFalse(station.listTitle.contains('\u0000'))
        assertNull(VoyahRadioOem.decode(mapOf(VoyahRadioOem.STATION_NAME to "\u0000\u0000  "), 3).stationName)
    }
    @Test fun fmFrequencyUsesHundredthsOfMHzAndRdsNameComesFromArtist() {
        val state = VoyahRadioOem.decode(mapOf(
            VoyahRadioOem.BAND to "FM", VoyahRadioOem.FREQUENCY to 10170L,
            VoyahRadioOem.TITLE to "FM 101.7 MHz", VoyahRadioOem.STATION_NAME to " Station ",
            VoyahRadioOem.FAVORITE to "true"), 3)
        assertEquals(101_700_000L, state.frequencyHz)
        assertEquals("Station", state.stationName)
        assertEquals("FM 101.7 MHz", state.title)
        assertEquals(true, state.playing)
        assertEquals(true, state.favorite)
    }

    @Test fun amFrequencyUsesKHz() {
        val state = VoyahRadioOem.decode(mapOf(VoyahRadioOem.BAND to "AM", VoyahRadioOem.FREQUENCY to 999), 2)
        assertEquals(999_000L, state.frequencyHz)
        assertEquals(false, state.playing)
        assertNull(state.stationName)
        assertNull(state.favorite)
    }

    @Test fun initialSentinelMissingAndMalformedFrequencyStayUnknown() {
        for (raw in listOf(null, -1L, 0L, 10170.5, "10170", Long.MAX_VALUE)) {
            val state = VoyahRadioOem.decode(mapOf(VoyahRadioOem.BAND to "FM", VoyahRadioOem.FREQUENCY to raw), null)
            assertNull("raw=$raw", state.frequencyHz)
        }
        assertNull(VoyahRadioOem.decode(emptyMap(), null).frequencyHz)
        assertNull(VoyahRadioOem.decode(mapOf(VoyahRadioOem.FREQUENCY to 10170L), 3).frequencyHz)
        assertNull(VoyahRadioOem.decode(mapOf(VoyahRadioOem.BAND to "DAB", VoyahRadioOem.FREQUENCY to 10170L), 3).frequencyHz)
    }

    @Test fun bufferingErrorsAndUnknownPlayStatesAreNotCommandConfirmations() {
        for (state in listOf(null, 0, 4, 5, 6, 7, 8, 9, 10, 11, 100))
            assertNull("state=$state", VoyahRadioOem.decode(emptyMap(), state).playing)
        assertEquals(false, VoyahRadioOem.decode(emptyMap(), 1).playing)
    }

    @Test fun radioMetadataDoesNotDependOnIncorrectGenreOrLegacyFavoriteKey() {
        // Utils.qinMediaInfo2metaData writes KW_MUSIC even for RdsPlayer. It also writes favorite
        // as a string in KW_MEDIA_INFO_FAV, unlike BasePlayer's obsolete initial metadata keys.
        val state = VoyahRadioOem.decode(mapOf(
            "android.media.metadata.GENRE" to "KW_MUSIC", "metadata_radio_collected" to 1L,
            VoyahRadioOem.BAND to "FM", VoyahRadioOem.FREQUENCY to 8750L,
            VoyahRadioOem.STATION_NAME to "  ", VoyahRadioOem.FAVORITE to "false"), 3)
        assertEquals(87_500_000L, state.frequencyHz)
        assertNull(state.stationName)
        assertEquals(false, state.favorite)
        assertNull(VoyahRadioOem.decode(mapOf("metadata_radio_collected" to 1L), null).favorite)
    }
}
