package ru.big.town.anative;

/** Android-free guards for event-driven automatic battery preheating. */
final class BatteryHeatAutoPolicy {
    static final int PLATFORM_UNKNOWN = 0;
    static final int PLATFORM_H97X = 1;
    static final int PLATFORM_H97C = 2;

    private BatteryHeatAutoPolicy() {}

    static boolean revisionCurrent(long submittedRevision, long currentRevision) {
        return submittedRevision == currentRevision;
    }

    static boolean settingRefreshNeededForTemperature(boolean settingKnown) {
        return !settingKnown;
    }

    static int platform(boolean h97xSeen, boolean h97cSeen) {
        if (h97cSeen) return PLATFORM_H97C;
        if (h97xSeen) return PLATFORM_H97X;
        return PLATFORM_UNKNOWN;
    }

    static boolean heatingActive(int controlStatus, int preheatSet, int bmsState) {
        return controlStatus == 1 || preheatSet == 1 || bmsState == 9;
    }

    static boolean activationConfirmed(int platform, int controlStatus, int switchState,
                                       int preheatSet, int bmsState) {
        if (heatingActive(controlStatus, preheatSet, bmsState)) return true;
        return platform == PLATFORM_H97C && switchState == 1;
    }

    static boolean controlBusy(int controlStatus, int switchState, int preheatSet, int bmsState,
                               boolean confirmationPending) {
        return confirmationPending
                || switchState == 1
                || controlStatus == 2
                || heatingActive(controlStatus, preheatSet, bmsState);
    }

    static boolean blockingFailure(int failReason) {
        return failReason >= 1 && failReason <= 4;
    }

    static int effectiveFailure(int platform, int h97xFailure, int h97cFailure,
                                int unknownValue) {
        if (platform == PLATFORM_H97C) return h97cFailure;
        if (platform == PLATFORM_H97X) return h97xFailure;
        if (h97cFailure != unknownValue) return h97cFailure;
        return h97xFailure;
    }

    static boolean snapshotComplete(int seenMask, int h97xRequiredMask,
                                    int h97cRequiredMask) {
        return (seenMask & h97xRequiredMask) == h97xRequiredMask
                || (seenMask & h97cRequiredMask) == h97cRequiredMask;
    }

    static boolean canSend(boolean activeInstance,
                           long expectedCanBusEpoch, long activeCanBusEpoch,
                           long ambientTemperatureEpoch,
                           long expectedDecisionGeneration, long currentDecisionGeneration,
                           boolean autoEnabled, boolean temperatureKnown, boolean temperatureCold,
                           boolean controlBusy, boolean controlBlocked) {
        return activeInstance
                && expectedCanBusEpoch == activeCanBusEpoch
                && ambientTemperatureEpoch == activeCanBusEpoch
                && expectedDecisionGeneration == currentDecisionGeneration
                && autoEnabled
                && temperatureKnown
                && temperatureCold
                && !controlBusy
                && !controlBlocked;
    }
}
