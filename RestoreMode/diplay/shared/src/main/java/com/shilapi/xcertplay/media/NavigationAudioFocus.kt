package com.shilapi.xcertplay.media

/** A retained AirPlay stream must not leave the steering volume on navigation after a prompt. */
internal class NavigationAudioFocus(
    private val request: () -> Unit,
    private val abandon: () -> Unit,
    private val idleNanos: Long = 500_000_000L,
) {
    private var held = false
    private var lastPcmNanos = 0L
    private var lastAudibleFrame = 0L

    fun pcmQueued(pcm: ByteArray, offset: Int, length: Int, nowNanos: Long, endFrame: Long) {
        // Some phones keep sending digital silence between prompts. It must neither acquire
        // navigation focus nor prolong it; wait for the last non-silent PCM to leave the track.
        if ((offset until offset + length).none { pcm[it] != 0.toByte() }) return
        lastPcmNanos = nowNanos
        lastAudibleFrame = endFrame
        if (held) return
        held = true
        request()
    }

    fun advance(nowNanos: Long, playedFrames: Long) {
        if (held && playedFrames >= lastAudibleFrame && nowNanos - lastPcmNanos >= idleNanos) close()
    }

    fun close() {
        if (!held) return
        held = false
        abandon()
    }
}
