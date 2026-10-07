package ru.big.town.restoremode.vehicle;

/**
 * Persisted fragrance restore contract shared by the VoyahTune UI and its settings provider.
 *
 * <p>The numeric values intentionally match the Android 11 Qinggan {@code VehicleState} values:
 * taste and concentration are 1..3; duration is 0 (no timer), 1 (30 minutes), or 2 (60 minutes).
 * Restore is opt-in so upgrading VoyahTune cannot unexpectedly start the fragrance system.
 */
public final class FragranceSettings {
    public static final String ENABLED = "fragranceEnabled";
    public static final String TASTE = "fragranceTaste";
    public static final String DURATION = "fragranceDuration";
    public static final String INTENSITY = "fragranceIntensity";

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_TASTE = 1;
    public static final int DEFAULT_DURATION = 0;
    public static final int DEFAULT_INTENSITY = 2;

    private FragranceSettings() {}

    public static int normalizeTaste(int value) {
        return value >= 1 && value <= 3 ? value : DEFAULT_TASTE;
    }

    public static int normalizeDuration(int value) {
        return value >= 0 && value <= 2 ? value : DEFAULT_DURATION;
    }

    public static int normalizeIntensity(int value) {
        return value >= 1 && value <= 3 ? value : DEFAULT_INTENSITY;
    }
}
