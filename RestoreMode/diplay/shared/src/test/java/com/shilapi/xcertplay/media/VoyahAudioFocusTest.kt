package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, shadows = [VoyahAudioFocusTest.RoutedAudioTrack::class])
class VoyahAudioFocusTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val tracks = mutableListOf<AudioTrack>()
    private val streams = mutableListOf<Int>()
    private val coordinators = mutableListOf<AudioFocusCoordinator>()
    @Before fun grantFocus() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }
    private fun coordinator(voyah: Boolean = true) = AudioFocusCoordinator(context, true,
        navigationFocusEnabled = voyah, onFocusStreamChanged = { streams += it }).also(coordinators::add)
    private fun track(stream: Int) = AudioTrack.Builder().setAudioAttributes(mediaAttributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build().also {
            tracks += it; RoutedAudioTrack.routes[it] = stream
        }
    private fun request() = checkNotNull(shadowOf(manager).lastAudioFocusRequest.audioFocusRequest)
    @After fun cleanup() {
        coordinators.forEach(AudioFocusCoordinator::close)
        tracks.forEach(AudioTrack::release)
        RoutedAudioTrack.routes.clear()
    }
    @Test fun legacyStreamOneRequestsSemanticNavigationFocusAndThenRestoresMedia() {
        val focus = coordinator()
        val media = track(3); val nav = track(1)
        focus.acquire(media, AudioChannel.MEDIA, mediaAttributes)
        val musicVolume = manager.getStreamVolume(3)
        val navVolume = manager.getStreamVolume(1)
        focus.acquire(nav, AudioChannel.NAVIGATION,
            AudioAttributes.Builder().setLegacyStreamType(AudioManager.STREAM_SYSTEM).build())
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, request().audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request().audioAttributes.contentType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request().focusGain)
        focus.release(nav)
        assertEquals(AudioAttributes.USAGE_MEDIA, request().audioAttributes.usage)
        assertEquals(listOf(3, 1, 3), streams)
        assertEquals(musicVolume, manager.getStreamVolume(3))
        assertEquals(navVolume, manager.getStreamVolume(1))
    }
    @Test fun phoneAndSiriOutrankNavigationAndReleaseRestoresTheRemainingRole() {
        val focus = coordinator()
        val media = track(3); val nav = track(1); val siri = track(22); val phone = track(6)
        focus.acquire(media, AudioChannel.MEDIA, mediaAttributes)
        focus.acquire(nav, AudioChannel.NAVIGATION, mediaAttributes)
        focus.acquire(siri, AudioChannel.ASSISTANT, mediaAttributes)
        focus.acquire(phone, AudioChannel.PHONE, mediaAttributes)
        focus.release(phone); focus.release(siri); focus.release(nav); focus.release(media)
        assertEquals(listOf(3, 1, 22, 6, 22, 1, 3, -1), streams)
    }
    @Test fun remappedAudioDoesNotSelectAnOemVolumeGroupThatCannotAffectTheTrack() {
        val focus = coordinator()
        val nav = track(1); val siri = track(3)
        focus.acquire(nav, AudioChannel.NAVIGATION, mediaAttributes)
        focus.acquire(siri, AudioChannel.ASSISTANT, mediaAttributes)
        focus.release(siri)
        assertEquals(listOf(1, -1, 1), streams)
    }
    @Test fun standaloneStillSkipsNavigationFocusAndClosedSinkRejectsLateWorkers() {
        val standalone = coordinator(false)
        val media = track(3); val nav = track(1)
        standalone.acquire(media, AudioChannel.MEDIA, mediaAttributes)
        val original = request()
        standalone.acquire(nav, AudioChannel.NAVIGATION, mediaAttributes)
        assertSame(original, request())
        assertTrue(streams.isEmpty())
        val voyah = coordinator()
        voyah.acquire(nav, AudioChannel.NAVIGATION, mediaAttributes)
        voyah.close()
        val abandoned = shadowOf(manager).lastAbandonedAudioFocusRequest
        voyah.acquire(media, AudioChannel.MEDIA, mediaAttributes)
        assertSame(abandoned, shadowOf(manager).lastAbandonedAudioFocusRequest)
        assertEquals(listOf(1, -1), streams)
    }
    @Implements(AudioTrack::class)
    class RoutedAudioTrack {
        @RealObject private lateinit var track: AudioTrack
        @Implementation fun getStreamType(): Int = routes[track] ?: 3
        companion object { val routes = mutableMapOf<AudioTrack, Int>() }
    }
}
