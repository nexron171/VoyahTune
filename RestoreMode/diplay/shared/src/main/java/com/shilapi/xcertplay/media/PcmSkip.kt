package com.shilapi.xcertplay.media

/**
 * Drops decoded 16-bit PCM for a [MusicCatchUp] skip. The audio fades out over [fadeFrames] before the cut
 * and back in after it, so the jump has no click.
 */
internal class PcmSkip(private val channels: Int, private val fadeFrames: Int) {
    private val frameBytes = channels * 2
    private var dropFrames = 0L
    private var fadeOutDone = true
    private var fadeInFrom = fadeFrames

    /** Bytes at the start of the last chunk to write before the cut. */
    var headBytes = 0
        private set
    /** Where in the last chunk writing resumes after the cut; the rest of the chunk is written. */
    var tailOffset = 0
        private set

    val active get() = dropFrames > 0 || !fadeOutDone || fadeInFrom < fadeFrames

    fun start(frames: Long) {
        if (frames <= 0 || active) return
        dropFrames = frames
        fadeOutDone = false
    }

    /** Fades [data] in place and sets [headBytes] and [tailOffset] for this chunk. */
    fun process(data: ByteArray, offset: Int, length: Int) {
        val frames = length / frameBytes
        var frame = 0
        headBytes = 0
        if (!fadeOutDone) {
            val count = minOf(fadeFrames, frames)
            for (i in 0 until count) scale(data, offset + i * frameBytes, count - 1 - i, count)
            frame = count
            headBytes = count * frameBytes
            fadeOutDone = true
            fadeInFrom = 0
        }
        val dropped = minOf(dropFrames, (frames - frame).toLong()).toInt()
        dropFrames -= dropped
        frame += dropped
        tailOffset = offset + frame * frameBytes
        if (dropFrames > 0) {
            tailOffset = offset + length
            return
        }
        while (fadeInFrom < fadeFrames && frame < frames) {
            scale(data, offset + frame * frameBytes, fadeInFrom + 1, fadeFrames)
            fadeInFrom++
            frame++
        }
    }

    private fun scale(data: ByteArray, position: Int, numerator: Int, denominator: Int) {
        for (channel in 0 until channels) {
            val at = position + channel * 2
            val sample = (data[at].toInt() and 0xff) or (data[at + 1].toInt() shl 8)
            val scaled = sample * numerator / denominator
            data[at] = scaled.toByte()
            data[at + 1] = (scaled shr 8).toByte()
        }
    }
}
