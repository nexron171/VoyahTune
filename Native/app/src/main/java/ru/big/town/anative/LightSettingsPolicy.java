package ru.big.town.anative;

/** Pure policy deciding whether a pending sensor action still needs provider thresholds. */
final class LightSettingsPolicy {
    enum Decision {
        NEED_THRESHOLDS,
        APPLY_WITHOUT_THRESHOLDS,
        COMPLETE_CANCELLED
    }

    private LightSettingsPolicy() {}

    static Decision decide(boolean cancelOnManualAuto, boolean manualAutoBlocked,
                           boolean ifUnsent, boolean everSent,
                           boolean outdoorReasonKnown) {
        if (cancelOnManualAuto && manualAutoBlocked) return Decision.COMPLETE_CANCELLED;
        if (ifUnsent && everSent) return Decision.COMPLETE_CANCELLED;
        if (outdoorReasonKnown) return Decision.APPLY_WITHOUT_THRESHOLDS;
        return Decision.NEED_THRESHOLDS;
    }
}
