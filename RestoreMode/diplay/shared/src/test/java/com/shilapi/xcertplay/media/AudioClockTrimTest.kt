package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.PlaybackParams
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Music clock calibration, tempo recovery and AudioTrack failure handling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioClockTrimTest.RateTrack::class])
class AudioClockTrimTest {
    private val reports = mutableListOf<String>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private lateinit var renderer: Any
    private lateinit var track: AudioTrack

    @Before
    fun setUp() {
        MediaClockTrim.learnedTrim = 0.0
        RateTrack.rates.clear()
        RateTrack.writtenBytes = 0
        RateTrack.result = AudioTrack.SUCCESS
        RateTrack.ignoreParameters = false
        track =
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().build())
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(48_000)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(96_000)
                .build()
        renderer =
            type.declaredConstructors
                .single { it.parameterCount == 14 }
                .apply { isAccessible = true }
                .newInstance(
                    AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
                    false,
                    false,
                    0,
                    0,
                    AudioFocusCoordinator(null, false, false),
                    0,
                    500,
                    { message: String -> reports.add(message) },
                    0L,
                    false,
                    null,
                    false,
                    true,
                )
        set("track", track)
        set("mappedChannel", AudioChannel.MEDIA)
        set("playbackStarted", true)
        set("startThresholdBytes", 57_600)
        set("bytesPerSecond", 48_000 * 4)
        // 300 ms of music is the target; createTrack sets these up for music only.
        set("musicPlayback", MusicPlaybackControl(48_000, 14_400))
        set("baseStartThresholdBytes", 57_600)
        set("maxStartThresholdBytes", 172_800)
        RateTrack.parameters = PlaybackParams().allowDefaults()
    }

    @After
    fun tearDown() {
        track.release()
        MediaClockTrim.learnedTrim = 0.0
    }

    private fun set(name: String, value: Any?) =
        type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)

    private fun get(name: String): Any? =
        type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

    private fun write(bytes: Int) =
        type
            .getDeclaredMethod(
                "writePcm",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .invoke(renderer, ByteArray(bytes), 0, bytes)

    @Test
    fun aLowLevelLeavesTheRateAloneAndTheStatsShowIt() {
        // 150 ms queued against a 300 ms target: Wi-Fi, not the head unit's clock.
        write(28_800)
        write(28_800)
        assertTrue(RateTrack.rates.toString(), RateTrack.rates.isEmpty())
        type
            .getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(renderer, true)
        assertTrue(
            reports.toString(),
            reports.any {
                it.startsWith("Audio: music timing") &&
                    it.contains("clockRate=48000") &&
                    it.contains("pcmSkipMs=0")
            },
        )
    }

    @Test
    fun aLearnedDriftIsApplied() {
        MediaClockTrim.learnedTrim = -0.0075
        set("musicPlayback", MusicPlaybackControl(48_000, 14_400))
        write(2_048)
        assertEquals(listOf(47_640), RateTrack.rates)
    }

    @Test
    fun restartingPlaybackPreservesTheLearnedClockWithoutReactingToTheNewBuffer() {
        MediaClockTrim.learnedTrim = -0.0075
        set("musicPlayback", MusicPlaybackControl(48_000, 14_400))
        write(2_048)

        type
            .getDeclaredMethod("startPlayback", AudioTrack::class.java)
            .apply { isAccessible = true }
            .invoke(renderer, track)
        write(28_800)

        assertEquals(listOf(47_640), RateTrack.rates)
        assertEquals(-0.0075, MediaClockTrim.learnedTrim, 0.0)
    }

    @Test
    fun aTrackThatRefusesTheRateKeepsPlayingWithoutTheTrim() {
        MediaClockTrim.learnedTrim = -0.0075
        set("musicPlayback", MusicPlaybackControl(48_000, 14_400))
        RateTrack.result = AudioTrack.ERROR_INVALID_OPERATION
        write(2_048)
        assertEquals(48_000, (get("musicPlayback") as MusicPlaybackControl).applied.clockRate)
        assertTrue(
            reports.toString(),
            reports.contains("Audio: clock trim unavailable audioType=media rate=47640 result=-3"),
        )
        write(2_048)
        assertEquals(listOf(47_640), RateTrack.rates)
    }

    @Test
    fun musicPcmIsWrittenInFullInsteadOfBeingSkipped() {
        write(28_800)
        assertEquals(28_800, RateTrack.writtenBytes)
        type
            .getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(renderer, true)
        assertTrue(
            reports.toString(),
            reports.any { it.startsWith("Audio: music timing") && it.contains("pcmSkipMs=0") },
        )
    }

    @Test
    fun tempoRecoveryUsesPitchPreservingPlaybackParameters() {
        val control = get("musicPlayback") as MusicPlaybackControl
        type
            .getDeclaredMethod(
                "applyMusicPlayback",
                AudioTrack::class.java,
                MusicPlaybackControl::class.java,
                MusicPlaybackControl.Adjustment::class.java,
            )
            .apply { isAccessible = true }
            .invoke(renderer, track, control, MusicPlaybackControl.Adjustment(48_000, 1.005))
        assertEquals(1.005f, RateTrack.parameters.speed, 0.00001f)
        assertEquals(1.0f, RateTrack.parameters.pitch, 0.0f)
        assertEquals(
            PlaybackParams.AUDIO_FALLBACK_MODE_FAIL,
            RateTrack.parameters.audioFallbackMode,
        )
    }

    @Test
    fun nothingIsTrimmedBeforeMusicPlays() {
        set("playbackStarted", false)
        write(2_048)
        assertTrue(RateTrack.rates.isEmpty())
    }

    @Test
    fun aRejectedTempoResetDoesNotStartASecondConflictingRateController() {
        val control = get("musicPlayback") as MusicPlaybackControl
        val apply =
            type
                .getDeclaredMethod(
                    "applyMusicPlayback",
                    AudioTrack::class.java,
                    MusicPlaybackControl::class.java,
                    MusicPlaybackControl.Adjustment::class.java,
                )
                .apply { isAccessible = true }
        apply.invoke(renderer, track, control, MusicPlaybackControl.Adjustment(48_000, 1.005))
        RateTrack.result = AudioTrack.ERROR_INVALID_OPERATION
        apply.invoke(renderer, track, control, MusicPlaybackControl.Adjustment(48_000, 1.006))
        apply.invoke(renderer, track, control, MusicPlaybackControl.Adjustment(48_000, 1.0))
        assertEquals(1.005, control.applied.tempo, 0.0)
        assertEquals(listOf(48_000), RateTrack.rates)
        assertTrue(reports.any { it.contains("adjustment disabled") })
    }

    @Test
    fun silentlyIgnoredPlaybackParametersDoNotPassTheNeutralReadbackCheck() {
        val control = get("musicPlayback") as MusicPlaybackControl
        val apply =
            type
                .getDeclaredMethod(
                    "applyMusicPlayback",
                    AudioTrack::class.java,
                    MusicPlaybackControl::class.java,
                    MusicPlaybackControl.Adjustment::class.java,
                )
                .apply { isAccessible = true }
        apply.invoke(renderer, track, control, MusicPlaybackControl.Adjustment(48_000, 1.005))
        RateTrack.ignoreParameters = true
        apply.invoke(renderer, track, control, MusicPlaybackControl.Adjustment(48_000, 1.006))
        assertEquals(1.005, control.applied.tempo, 0.0)
        assertEquals(1.005f, RateTrack.parameters.speed, 0.00001f)
        assertEquals(listOf(48_000), RateTrack.rates)
        assertTrue(reports.any { it.contains("adjustment disabled") })
    }

    @Implements(AudioTrack::class)
    class RateTrack {
        @Implementation fun getPlaybackHeadPosition() = 0

        @Implementation fun getUnderrunCount() = 0

        @Implementation fun play() = Unit

        @Implementation fun pause() = Unit

        @Implementation
        fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            writtenBytes += size
            return size
        }

        @Implementation
        fun setPlaybackRate(rate: Int): Int {
            rates += rate
            return result
        }

        @Implementation
        fun setPlaybackParams(value: PlaybackParams) {
            if (result != AudioTrack.SUCCESS)
                throw IllegalArgumentException("unsupported playback parameters")
            if (ignoreParameters) return
            parameters = value
            rates += (value.pitch * 48_000).toInt()
        }

        @Implementation fun getPlaybackParams(): PlaybackParams = parameters

        companion object {
            lateinit var parameters: PlaybackParams
            val rates = mutableListOf<Int>()
            var result = AudioTrack.SUCCESS
            var ignoreParameters = false
            var writtenBytes = 0
        }
    }
}
