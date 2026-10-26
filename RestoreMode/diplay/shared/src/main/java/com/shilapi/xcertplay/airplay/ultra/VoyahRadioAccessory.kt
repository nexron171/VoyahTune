package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat as Format

data class UltraRadioStation(val id: String, val title: String, val shortName: String,
    val band: VoyahRadioOem.Band, val rawFrequency: Long, val oemIndex: Int? = null) {
    /** CarPlay's station list shows mediaItemName; mediaItemShortName is not a subtitle there. */
    val listTitle: String get() {
        val frequency = title.trim()
        val name = shortName.trim().takeIf { it.isNotEmpty() && !it.equals(frequency, ignoreCase = true) }
        return if (name == null) frequency else "$name · $frequency"
    }
    val frequencyHz: Long get() = rawFrequency * if (band == VoyahRadioOem.Band.FM) 10_000L else 1_000L
}

data class UltraRadioSnapshot(val connected: Boolean = false, val state: VoyahRadioOem.State? = null,
    val stations: List<UltraRadioStation> = emptyList()) {
    val currentId: String? get() = state?.let { s ->
        if (s.band != null && s.frequencyHz != null) "${s.band.name}:${s.rawFrequency}" else null
    }
    fun items(): List<UltraRadioStation> = currentItems().sortedWith(
        compareBy<UltraRadioStation> { it.band.ordinal }.thenBy { it.rawFrequency }.thenBy { it.id })
    private fun currentItems(): List<UltraRadioStation> {
        val current = currentId ?: return stations
        val s = state ?: return stations
        val band = s.band ?: return stations
        val rawFrequency = s.rawFrequency ?: return stations
        val currentName = s.stationName?.trim()?.takeIf { it.isNotEmpty() }?.take(128)
        if (stations.any { it.id == current }) return if (currentName == null) stations else stations.map {
            // A PS-name update can arrive before the OEM playlist is refreshed. Match the
            // band + frequency ID, never the queue index, and keep every tuning field intact.
            if (it.id == current) it.copy(shortName = currentName) else it
        }
        val title = s.title ?: when (band) {
            VoyahRadioOem.Band.FM -> "FM ${java.math.BigDecimal.valueOf(rawFrequency, 2).stripTrailingZeros().toPlainString()} MHz"
            VoyahRadioOem.Band.AM -> "AM $rawFrequency kHz"
        }
        return stations + UltraRadioStation(current, title, currentName ?: title, band, rawFrequency)
    }
}

data class UltraRadioRequest(val command: VoyahRadioOem.Command, val stationId: String? = null)

/**
 * Media/NowPlaying CAF wire shapes ported from f-io/LIVI vehicle_state.rs at
 * 3fb5210f71ebc4b15d9aed034df8f0c28eda4f54 (GPL-3.0-or-later). The original DAB semantic
 * value 6 and DAB item tags are deliberately omitted: the supplied vehicle has FM/AM, and
 * that reference does not establish the FM/AM enum values. The source is labelled explicitly.
 */
internal object VoyahRadioDescription {
    const val SOURCE = "VOYAH_RADIO"
    const val DISABLED = 4100L
    const val CURRENT = 4102L
    const val ITEMS = 4103L
    const val IMAGES = 4104L
    const val NOW_SOURCE = 4354L
    const val TITLE = 4355L
    const val ARTIST = 4356L
    const val ALBUM = 4357L
    const val ARTWORK = 4358L
    const val PLAYBACK = 4359L
    const val PLAY = 4360L
    const val PAUSE = 4361L
    const val NEXT = 4362L
    const val PREVIOUS = 4363L
    const val TUNE_ID = 4364L
    const val CHANGE_SOURCE = 4366L
    val controls = setOf(PLAY, PAUSE, NEXT, PREVIOUS, TUNE_ID, CHANGE_SOURCE)
    val ids = setOf(4098L, DISABLED, 4101L, CURRENT, ITEMS, IMAGES,
        NOW_SOURCE, TITLE, ARTIST, ALBUM, ARTWORK, PLAYBACK)

    fun accessories(radio: UltraRadioSnapshot?): List<Map<String, Any?>> = listOf(
        accessory(4096, 0x03000001, service(4097, 0x14000006, listOf(
            car(4098, 0x30000019, Format.STRING, radio, fixed = true),
            car(DISABLED, 0x32000011, Format.BOOL, radio),
            car(4101, 0x30000001, Format.STRING, radio, fixed = true),
            car(CURRENT, 0x32000026, Format.STRING, radio),
            car(ITEMS, 0x32000012, Format.ARRAY, radio),
            car(IMAGES, 0x32000031, Format.ARRAY, radio)))),
        accessory(4352, 0x0F000001, service(4353, 0x14000001, listOf(
            car(NOW_SOURCE, 0x32000023, Format.STRING, radio),
            car(TITLE, 0x32000003, Format.STRING, radio),
            car(ARTIST, 0x32000004, Format.STRING, radio),
            car(ALBUM, 0x32000005, Format.STRING, radio),
            car(ARTWORK, 0x32000020, Format.DATA, radio),
            car(PLAYBACK, 0x32000034, Format.UINT8, radio)), listOf(
            control(PLAY, 0x0F000032), control(PAUSE, 0x0F000033),
            control(NEXT, 0x0F000035), control(PREVIOUS, 0x0F000036),
            control(TUNE_ID, 0x0F00004B, "identifier", "sourceIdentifier"),
            control(CHANGE_SOURCE, 0x0F00004D, "identifier")))))

    fun value(iid: Long, radio: UltraRadioSnapshot?): Any? {
        val live = radio?.takeIf { it.connected }
        return when (iid) {
            4098L, NOW_SOURCE -> SOURCE
            4101L -> "FM / AM"
            DISABLED -> live?.items().isNullOrEmpty()
            CURRENT -> live?.currentId ?: ""
            ITEMS -> live?.items()?.map { linkedMapOf("identifier" to it.id,
                "mediaItemName" to it.listTitle, "mediaItemShortName" to it.shortName,
                "mediaItemCategory" to 0L, "frequency" to it.frequencyHz) } ?: emptyList<Any>()
            IMAGES -> emptyList<Any>()
            // The phone already displays the current MediaItem's frequency + PS name as
            // its heading. Repeating them here produces three duplicate lines.
            TITLE -> if (live?.currentId == null) live?.state?.title ?: "" else ""
            ARTIST -> live?.state?.radioText ?: ""
            ALBUM -> ""
            ARTWORK -> EMPTY_ARTWORK
            PLAYBACK -> live?.state?.playing?.let { if (it) 1L else 0L }
            else -> null
        }
    }

    /** Strict validation precedes all OEM side effects. A frequency command is never advertised. */
    fun request(iid: Long, raw: Any?): UltraRadioRequest? {
        if (iid !in controls) return null
        val params = when (raw) { null -> emptyMap<Any?, Any?>(); is Map<*, *> -> raw; else -> return null }
        return when (iid) {
            TUNE_ID -> if (params.keys == setOf("identifier", "sourceIdentifier") && params["sourceIdentifier"] == SOURCE)
                (params["identifier"] as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?.let { UltraRadioRequest(VoyahRadioOem.Command.TUNE, it) } else null
            CHANGE_SOURCE -> if (params.keys == setOf("identifier") && params["identifier"] == SOURCE)
                UltraRadioRequest(VoyahRadioOem.Command.PLAY) else null
            else -> if (params.isNotEmpty()) null else UltraRadioRequest(when (iid) {
                PLAY -> VoyahRadioOem.Command.PLAY
                PAUSE -> VoyahRadioOem.Command.PAUSE
                NEXT -> VoyahRadioOem.Command.NEXT
                else -> VoyahRadioOem.Command.PREVIOUS
            })
        }
    }

    private val EMPTY_ARTWORK = byteArrayOf()
    private fun accessory(iid: Long, type: Long, service: Map<String, Any?>) = linkedMapOf<String, Any?>(
        "iid" to iid, "type" to type, "version" to "1.0", "services" to listOf(service))
    private fun service(iid: Long, type: Long, chars: List<Map<String, Any?>>, commands: List<Map<String, Any?>> = emptyList()) =
        linkedMapOf<String, Any?>("iid" to iid, "type" to type, "characteristics" to chars).apply {
            if (commands.isNotEmpty()) put("controls", commands)
        }
    private fun control(iid: Long, type: Long, vararg parameters: String) = linkedMapOf<String, Any?>(
        "iid" to iid, "type" to type, "sender" to 0L, "hasResponse" to true).apply {
            if (parameters.isNotEmpty()) put("requestParameters", parameters.map {
                mapOf("name" to it, "format" to Format.STRING.wireValue)
            })
        }
    private fun car(iid: Long, type: Long, format: Format, radio: UltraRadioSnapshot?, fixed: Boolean = false) =
        linkedMapOf<String, Any?>("iid" to iid, "type" to type, "format" to format.wireValue,
            "writable" to false, "oemWritable" to true, "mutable" to !fixed, "priority" to 1L).apply {
            value(iid, radio)?.let { put("initialValue", it) }
            if (!fixed) put("supportsInvalid", true)
            if (format == Format.UINT8) { put("minimumValue", 0L); put("maximumValue", 1L); put("stepValue", 1L) }
            if (format == Format.STRING || format == Format.ARRAY || format == Format.DATA) put("maximumLength", 65535L)
            if (format == Format.ARRAY || format == Format.DATA) put("largePayload", true)
        }
}
