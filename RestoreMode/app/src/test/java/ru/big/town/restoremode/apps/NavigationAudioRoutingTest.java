package ru.big.town.restoremode.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.common.NavigationAudioRouting;
import ru.big.town.common.NavigationAudioSettings;
import ru.big.town.restoremode.integration.config.SplitConfigSync;

import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public final class NavigationAudioRoutingTest {
    @Test
    public void routesMusicAttributesToNavigationWithoutMutatingTheCaller() throws Exception {
        AudioAttributes original =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
        AudioAttributes routed =
                NavigationAudioRouting.attributes(original, "ru.yandex.yandexnavi");

        assertNotSame(original, routed);
        assertEquals(1, routed.getVolumeControlStream());
        assertEquals(AudioAttributes.USAGE_MEDIA, original.getUsage());
        Set<?> tags = (Set<?>) AudioAttributes.class.getMethod("getTags").invoke(routed);
        assertTrue(tags.contains(NavigationAudioSettings.TAG_PREFIX + "ru.yandex.yandexnavi"));
    }

    @Test
    public void speechKeepsVoiceParametersAndCarriesTheCallerAcrossTheEngineProcess()
            throws Exception {
        Bundle original = new Bundle();
        original.putString("voice", "saved");
        original.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, 3);
        Bundle routed = NavigationAudioRouting.speechParameters(original, "ru.yandex.yandexmaps");

        assertEquals(3, original.getInt(TextToSpeech.Engine.KEY_PARAM_STREAM));
        assertEquals(1, routed.getInt(TextToSpeech.Engine.KEY_PARAM_STREAM));
        assertEquals("saved", routed.getString("voice"));
        AudioAttributes attributes = routed.getParcelable("audioAttributes");
        assertEquals(1, attributes.getVolumeControlStream());
        Set<?> tags = (Set<?>) AudioAttributes.class.getMethod("getTags").invoke(attributes);
        assertTrue(tags.contains(NavigationAudioSettings.TAG_PREFIX + "ru.yandex.yandexmaps"));
        assertEquals(
                1,
                NavigationAudioRouting.speechParameters(null, "app.nav")
                        .getInt(TextToSpeech.Engine.KEY_PARAM_STREAM));
    }

    @Test
    public void clearsOriginalScoRouteWhilePreservingCaptureRestrictions() throws Exception {
        AudioAttributes original =
                new AudioAttributes.Builder()
                        .setLegacyStreamType(6)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        .build();
        int originalVolumeStream = original.getVolumeControlStream();
        AudioAttributes routed = NavigationAudioRouting.attributes(original, "app.nav");
        assertEquals(1, routed.getVolumeControlStream());
        assertEquals(AudioAttributes.ALLOW_CAPTURE_BY_NONE, routed.getAllowedCapturePolicy());
        assertEquals(originalVolumeStream, original.getVolumeControlStream());
    }

    @Test
    public void savesNormalizedSelectionAndAllowsClearingIt() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("navigation-audio-test", 0);
        NavigationAudioAppStore.save(
                preferences, List.of("ru.yandex.yandexnavi", "ru.yandex.yandexnavi", "invalid"));
        assertEquals(List.of("ru.yandex.yandexnavi"), NavigationAudioAppStore.load(preferences));
        NavigationAudioAppStore.save(preferences, List.of());
        assertEquals("", NavigationAudioAppStore.snapshot(preferences));
    }

    @Test
    public void publishesSelectionAndRemovalToTheProtectedNativeConfigurationReceiver() {
        android.app.Application context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("navigation-audio-sync", 0);
        NavigationAudioAppStore.save(preferences, List.of("ru.yandex.yandexnavi"));
        SplitConfigSync.pushNavigationAudio(context, preferences);
        NavigationAudioAppStore.save(preferences, List.of());
        SplitConfigSync.pushNavigationAudio(context, preferences);
        List<Intent> sent = shadowOf(context).getBroadcastIntents();
        assertEquals(2, sent.size());
        assertEquals(NavigationAudioSettings.ACTION_CONFIG, sent.get(0).getAction());
        assertEquals(
                "ru.big.town.anative.SetModesConfigReceiver",
                sent.get(0).getComponent().getClassName());
        assertEquals(
                "ru.yandex.yandexnavi",
                sent.get(0).getStringExtra(NavigationAudioSettings.EXTRA_PACKAGES));
        assertEquals("", sent.get(1).getStringExtra(NavigationAudioSettings.EXTRA_PACKAGES));
    }
}
