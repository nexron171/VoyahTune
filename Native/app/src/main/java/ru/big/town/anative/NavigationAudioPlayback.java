package ru.big.town.anative;

import ru.big.town.common.NavigationAudioSettings;

import java.util.Set;

final class NavigationAudioPlayback {
    private NavigationAudioPlayback() {}

    static boolean matches(
            boolean active,
            int stream,
            int uid,
            Set<Integer> selectedUids,
            String selectedPackages,
            Iterable<?> tags) {
        if (!active || stream != NavigationAudioSettings.STREAM) return false;
        if (selectedUids.contains(uid)) return true;
        // TTS playback belongs to the engine UID, so use the caller's routed attributes.
        for (Object tag : tags) {
            String value = String.valueOf(tag);
            if (value.startsWith(NavigationAudioSettings.TAG_PREFIX)
                    && NavigationAudioSettings.contains(
                            selectedPackages,
                            value.substring(NavigationAudioSettings.TAG_PREFIX.length()))) {
                return true;
            }
        }
        return false;
    }
}
