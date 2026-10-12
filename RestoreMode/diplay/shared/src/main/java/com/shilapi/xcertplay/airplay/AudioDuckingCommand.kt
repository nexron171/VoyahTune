package com.shilapi.xcertplay.airplay

/** CarPlay attenuation is expressed in decibels; duration is the transition time. */
internal data class AudioDuckingCommand(val volumeDb: Double, val durationMs: Long) {
    companion object {
        fun parse(type: String, parameters: Map<String, Any?>): AudioDuckingCommand? {
            if (type != "duckAudio" && type != "unduckAudio") return null
            val volume = (parameters["volume"] as? Number)?.toDouble() ?: return null
            val duration = (parameters["durationMs"] as? Number)?.toDouble() ?: return null
            if (!volume.isFinite() || volume > 0 || !duration.isFinite() || duration < 0) return null
            return AudioDuckingCommand(volume, duration.coerceAtMost(60_000.0).toLong())
        }
    }
}
