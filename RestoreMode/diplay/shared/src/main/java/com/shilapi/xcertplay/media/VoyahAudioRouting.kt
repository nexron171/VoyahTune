package com.shilapi.xcertplay.media

/** Qinggan volume groups from the SE 2025 rc5.1 and Sport+ 13.1 OEM SDKs. */
object VoyahAudioRouting {
    const val MEDIA_STREAM = 3
    const val NAVIGATION_STREAM = 1

    internal fun stream(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> MEDIA_STREAM
        AudioChannel.NAVIGATION -> NAVIGATION_STREAM
        AudioChannel.PHONE -> 6
        AudioChannel.ASSISTANT -> 22
    }

    // Some frameworks accept a vendor ID but silently translate it into STREAM_MUSIC.
    fun accepted(requested: Int, actual: Int, initialized: Boolean): Boolean =
        initialized && requested == actual
}
