package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE, shadows = [AudioRebufferCapacityTest.CapacityTrack::class])
class AudioRebufferCapacityTest {
    @Test fun residualAudioCountsTowardRestartBeforeAPausedTrackFills() {
        for (millis in MediaAudioBuffer.presets) for (rebuffers in listOf(0, 7)) {
            val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, millis)
            // After several rebuffers the restart level is the whole cushion above the chosen delay.
            val threshold = if (rebuffers == 0) MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 4096)
            else MediaAudioBuffer.startBytesFor(plan.maxStartBytes, plan.trackBufferBytes, 4096)
            CapacityTrack.capacity = plan.trackBufferBytes
            CapacityTrack.queued = threshold / 2
            CapacityTrack.playing = true
            CapacityTrack.blocked = false
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(plan.trackBufferBytes).build()
            val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
            val renderer = type.declaredConstructors.single { it.parameterCount == 14 }
                .apply { isAccessible = true }.newInstance(
                    AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"), false, false, 0, 0,
                    AudioFocusCoordinator(null, false, false), 0, millis, { _: String -> }, 0L, false, null, false, true)
            fun set(name: String, value: Any) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
            fun get(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
            try {
                set("track", track)
                set("mappedChannel", AudioChannel.MEDIA)
                set("playbackStarted", true)
                set("startThresholdBytes", threshold)
                set("maxStartThresholdBytes", MediaAudioBuffer.startBytesFor(plan.maxStartBytes, plan.trackBufferBytes, 4096))
                set("bytesPerSecond", 192_000)
                set("lastPcmWriteNs", System.nanoTime())
                (get("bufferProgress") as AudioBufferProgress).written(CapacityTrack.queued)
                type.getDeclaredMethod("maintainPlaybackBuffer").apply { isAccessible = true }.invoke(renderer)
                assertFalse(CapacityTrack.playing)
                val restart = get("startThresholdBytes") as Int
                // A rebuffer restarts 100 ms later, but never above the whole cushion.
                assertEquals("${millis}ms", minOf(threshold + 19_200, get("maxStartThresholdBytes") as Int), restart)
                type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(renderer, ByteArray(restart), 0, restart)
                assertFalse("${millis}ms buffer blocked before play()", CapacityTrack.blocked)
                assertTrue("${millis}ms buffer never resumed", CapacityTrack.playing)
            } finally { track.release() }
        }
    }

    @Implements(AudioTrack::class)
    class CapacityTrack {
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 1
        @Implementation fun pause() { playing = false }
        @Implementation fun play() { playing = true }
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            if (!playing && queued + size > capacity) {
                blocked = true
                return 0 // A real blocking AudioTrack.write waits here indefinitely.
            }
            if (!playing) queued += size
            return size
        }
        companion object {
            var capacity = 0
            var queued = 0
            var playing = true
            var blocked = false
        }
    }
}
