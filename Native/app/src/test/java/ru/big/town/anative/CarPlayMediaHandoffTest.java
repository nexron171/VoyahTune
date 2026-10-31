package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioAttributes;
import android.media.session.PlaybackState;

import org.junit.Test;

public class CarPlayMediaHandoffTest {
    private final CarPlayMediaHandoff handoff = new CarPlayMediaHandoff();
    private final Object yandex = new Object();
    private final Object carPlay = new Object();

    @Test
    public void newMusicSourcePausesCarPlayOnlyOnceWhilePhoneAcknowledges() {
        assertTrue(observe(yandex, "ru.yandex.music", PlaybackState.STATE_PLAYING,
                AudioAttributes.USAGE_MEDIA, PlaybackState.STATE_PLAYING));
        assertFalse(observe(yandex, "ru.yandex.music", PlaybackState.STATE_PLAYING,
                AudioAttributes.USAGE_MEDIA, PlaybackState.STATE_PLAYING));
        assertFalse(observe(yandex, "ru.yandex.music", PlaybackState.STATE_PLAYING,
                AudioAttributes.USAGE_MEDIA, PlaybackState.STATE_PAUSED));
    }

    @Test
    public void waitingOrPausedPlayerDoesNotInterruptCarPlay() {
        for (int state : new int[] {PlaybackState.STATE_NONE, PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR}) {
            assertFalse(observe(yandex, "ru.yandex.music", state,
                    AudioAttributes.USAGE_MEDIA, PlaybackState.STATE_PLAYING));
        }
        assertTrue(observe(yandex, "ru.yandex.music", PlaybackState.STATE_PLAYING,
                AudioAttributes.USAGE_MEDIA, PlaybackState.STATE_PLAYING));
    }

    @Test
    public void callNavigationAndMirrorDoNotBecomeMusicOwners() {
        for (int usage : new int[] {AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.USAGE_ASSISTANT, AudioAttributes.USAGE_NOTIFICATION}) {
            assertFalse(observe(yandex, "other", PlaybackState.STATE_PLAYING,
                    usage, PlaybackState.STATE_PLAYING));
        }
        assertFalse(observe(carPlay, "ru.big.town.restoremode", PlaybackState.STATE_PLAYING,
                AudioAttributes.USAGE_MEDIA, PlaybackState.STATE_PLAYING));
        assertFalse(observe(yandex, "ru.big.town.anative", PlaybackState.STATE_PLAYING,
                AudioAttributes.USAGE_MEDIA, PlaybackState.STATE_PLAYING));
    }

    @Test
    public void returningFromCarPlayToTheSamePlayerIsANewHandoff() {
        assertTrue(observe(yandex, "ru.yandex.music", 3, 1, 3));
        assertFalse(observe(carPlay, "ru.big.town.restoremode", 3, 1, 3));
        assertTrue(observe(yandex, "ru.yandex.music", 3, 1, 3));
    }

    @Test
    public void newSessionTokensAndOemMusicAreSupported() {
        assertTrue(observe(yandex, "ru.yandex.music", 3, 1, 3));
        assertTrue(observe(new Object(), "ru.yandex.music", 3, 1, 3));
        assertTrue(observe(new Object(), "com.qinggan.media", 3, 0, 3));
        assertFalse(handoff.shouldPause(null, "ru.yandex.music", 3, 1, null, 3));
    }

    private boolean observe(Object token, String packageName, int state, int usage, int carPlayState) {
        return handoff.shouldPause(token, packageName, state, usage, carPlay, carPlayState);
    }
}
