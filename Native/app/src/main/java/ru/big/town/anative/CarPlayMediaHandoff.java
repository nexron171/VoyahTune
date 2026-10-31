package ru.big.town.anative;

import android.media.AudioAttributes;
import android.media.session.PlaybackState;

import java.util.Objects;

/** One explicit CarPlay pause per selected music-session transition. */
final class CarPlayMediaHandoff {
    static final String CARPLAY_PACKAGE = "ru.big.town.restoremode";

    private Object pausedForSource;
    private Object pausedCarPlay;

    boolean shouldPause(Object sourceToken, String sourcePackage, int sourceState, int sourceUsage,
            Object carPlayToken, int carPlayState) {
        if (sourceToken == null || sourceState != PlaybackState.STATE_PLAYING
                || CARPLAY_PACKAGE.equals(sourcePackage)) {
            pausedForSource = null;
            pausedCarPlay = null;
            return false;
        }
        if (sourcePackage == null || sourcePackage.isEmpty()
                || "ru.big.town.anative".equals(sourcePackage)
                || (sourceUsage != AudioAttributes.USAGE_MEDIA
                        && sourceUsage != AudioAttributes.USAGE_UNKNOWN)
                || carPlayToken == null || carPlayState != PlaybackState.STATE_PLAYING) {
            return false;
        }
        if (Objects.equals(sourceToken, pausedForSource) && Objects.equals(carPlayToken, pausedCarPlay)) {
            return false;
        }
        pausedForSource = sourceToken;
        pausedCarPlay = carPlayToken;
        return true;
    }
}
