package ru.big.town.restoremode;

/**
 * Persisted fragrance restore contract shared by the VoyahTune UI and its settings provider.
 *
 * <p>The numeric values intentionally match the Android 11 Qinggan {@code VehicleState} values:
 * taste and concentration are 1..3; duration is 0 (no timer), 1 (30 minutes), or 2 (60 minutes).
 * Restore is opt-in so upgrading VoyahTune cannot unexpectedly start the fragrance system.</p>
 */
final class FragranceSettings {
    static final String ENABLED = "fragranceEnabled";
    static final String TASTE = "fragranceTaste";
    static final String DURATION = "fragranceDuration";
    static final String INTENSITY = "fragranceIntensity";

    static final boolean DEFAULT_ENABLED = false;
    static final int DEFAULT_TASTE = 1;
    static final int DEFAULT_DURATION = 0;
    static final int DEFAULT_INTENSITY = 2;

    private FragranceSettings() {
    }

    static int normalizeTaste(int value) {
        return value >= 1 && value <= 3 ? value : DEFAULT_TASTE;
    }

    static int normalizeDuration(int value) {
        return value >= 0 && value <= 2 ? value : DEFAULT_DURATION;
    }

    static int normalizeIntensity(int value) {
        return value >= 1 && value <= 3 ? value : DEFAULT_INTENSITY;
    }
}
