package ru.big.town.anative;

/** Pure policy deciding whether a pending sensor action still needs provider thresholds. */
final class LightSettingsPolicy {
    private LightSettingsPolicy() {}

    static boolean needsThresholds(boolean cancelOnManualAuto, boolean manualAutoBlocked,
                                   boolean ifUnsent, boolean everSent,
                                   boolean outdoorReasonKnown) {
        if (cancelOnManualAuto && manualAutoBlocked) return false;
        if (ifUnsent && everSent) return false;
        return !outdoorReasonKnown;
    }
}
