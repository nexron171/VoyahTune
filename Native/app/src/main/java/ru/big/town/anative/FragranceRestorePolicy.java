package ru.big.town.anative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure Android-free contract for the H97C fragrance settings restored on wake. */
final class FragranceRestorePolicy {
    static final String DURATION_STATE = "FCM_DURATION_CONTROL";
    static final int DURATION_STATE_ID = 1067;

    static final String SWITCH_STATE = "FCM_SW_REQ";
    static final int SWITCH_STATE_ID = 774;
    static final int SWITCH_ON = 2;

    static final String TASTE_STATE = "IVI_FRAG_TASTE";
    static final int TASTE_STATE_ID = 775;

    // The misspelling is part of the Android 11 OEM VehicleState ABI.
    static final String INTENSITY_STATE = "IVI_FRAG_CONCERNTION";
    static final int INTENSITY_STATE_ID = 777;

    static final int DEFAULT_TASTE = 1;
    static final int DEFAULT_DURATION = 0;
    static final int DEFAULT_INTENSITY = 2;

    private FragranceRestorePolicy() {
    }

    static Settings normalize(int taste, int duration, int intensity) {
        return new Settings(
                inRange(taste, 1, 3) ? taste : DEFAULT_TASTE,
                inRange(duration, 0, 2) ? duration : DEFAULT_DURATION,
                inRange(intensity, 1, 3) ? intensity : DEFAULT_INTENSITY);
    }

    /**
     * One TX77 bundle matching the stock SceneIntentService. The duration is deliberately absent:
     * it is an OEM-service timer setting and must be sent first through TX58.
     */
    static Map<String, Integer> fragranceBundle(Settings settings) {
        if (settings == null) throw new IllegalArgumentException("Fragrance settings are null");
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        values.put(TASTE_STATE, settings.taste);
        values.put(INTENSITY_STATE, settings.intensity);
        // FCM is last conceptually: the OEM service arms the already-selected duration on enable.
        values.put(SWITCH_STATE, SWITCH_ON);
        return Collections.unmodifiableMap(values);
    }

    static Map<String, Integer> stableIds() {
        LinkedHashMap<String, Integer> ids = new LinkedHashMap<>();
        ids.put(DURATION_STATE, DURATION_STATE_ID);
        ids.put(SWITCH_STATE, SWITCH_STATE_ID);
        ids.put(TASTE_STATE, TASTE_STATE_ID);
        ids.put(INTENSITY_STATE, INTENSITY_STATE_ID);
        return Collections.unmodifiableMap(ids);
    }

    private static boolean inRange(int value, int min, int max) {
        return value >= min && value <= max;
    }

    static final class Settings {
        final int taste;
        final int duration;
        final int intensity;

        Settings(int taste, int duration, int intensity) {
            this.taste = taste;
            this.duration = duration;
            this.intensity = intensity;
        }
    }
}
