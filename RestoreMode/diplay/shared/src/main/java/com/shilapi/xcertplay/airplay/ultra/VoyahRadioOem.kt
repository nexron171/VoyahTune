package com.shilapi.xcertplay.airplay.ultra

/** Vehicle-side contract verified in the supplied com.pateo.rdsapp APK; this is not CAF metadata. */
object VoyahRadioOem {
    const val PACKAGE = "com.pateo.rdsapp"
    const val SERVICE = "com.pateo.midrds.media.RdsMediaBrowserService"
    const val BAND = "metadata_radio_type"
    const val FREQUENCY = "metadata_radio_frequency"
    const val TITLE = "android.media.metadata.TITLE"
    const val STATION_NAME = "android.media.metadata.ARTIST"
    const val FAVORITE = "KW_MEDIA_INFO_FAV"

    enum class Command { PLAY, PAUSE, NEXT, PREVIOUS, TUNE }
    enum class Band { FM, AM }

    data class State(
        val band: Band?,
        val rawFrequency: Long?,
        /** FM: raw / 100 MHz; AM: raw kHz, as RadioStation and RdsInfoUtils implement them. */
        val frequencyHz: Long?,
        val title: String?,
        val stationName: String?,
        val favorite: Boolean?,
        /** Unknown, buffering and error states must not become a confirmed play/pause result. */
        val playing: Boolean?,
        /** Current station's RT, read separately because the OEM MediaSession omits it. */
        val radioText: String? = null,
    )

    data class RadioText(val band: Band, val rawFrequency: Long, val text: String?)

    /** Never carry text from the previous station across tuning or loss of the read-only source. */
    fun withRadioText(state: State, reading: RadioText?): State = state.copy(radioText = reading
        ?.takeIf { state.frequencyHz != null && it.band == state.band && it.rawFrequency == state.rawFrequency }
        ?.text?.let(::text)?.take(256)?.takeUnless {
            it.equals(state.title, ignoreCase = true) || it.equals(state.stationName, ignoreCase = true)
        })

    fun decode(metadata: Map<String, Any?>, playbackState: Int?): State {
        val band = when (metadata[BAND]) { "FM" -> Band.FM; "AM" -> Band.AM; else -> null }
        // The APK writes a signed int through MediaMetadata.putLong. Do not accept a truncated
        // floating-point value, an absent key's default 0, or the initial -1 sentinel as a station.
        val raw = when (val value = metadata[FREQUENCY]) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
        return State(band, raw, raw?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.let { when (band) {
            Band.FM -> it * 10_000
            Band.AM -> it * 1_000
            null -> null
        } }, text(metadata[TITLE]), text(metadata[STATION_NAME]),
            when (metadata[FAVORITE]) { "true" -> true; "false" -> false; else -> null },
            when (playbackState) { 1, 2 -> false; 3 -> true; else -> null })
    }

    /** The OEM pads fixed-width RDS PS names with NUL bytes. They are not part of the label. */
    private fun text(value: Any?): String? = (value as? String)
        ?.trim { it <= ' ' || it.isWhitespace() }?.takeIf { it.isNotEmpty() }

    /** RdsPlayer returns QueueItems with RdsInfoUtils' band-prefixed title and raw frequency ID. */
    fun station(mediaId: String?, title: String?, subtitle: String?, index: Int): UltraRadioStation? {
        val name = text(title) ?: return null
        val band = when { name.startsWith("FM ") -> Band.FM; name.startsWith("AM ") -> Band.AM; else -> return null }
        val raw = mediaId?.toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return null
        if (index !in 0..511) return null
        return UltraRadioStation("${band.name}:$raw", name.take(256), text(subtitle)?.take(128) ?: name.take(128), band, raw, index)
    }
}
