package ru.big.town.anative;

/** Android-free guards for event-driven automatic battery preheating. */
final class BatteryHeatAutoPolicy {
    private BatteryHeatAutoPolicy() {}

    static boolean revisionCurrent(long submittedRevision, long currentRevision) {
        return submittedRevision == currentRevision;
    }

    static boolean settingRefreshNeededForTemperature(boolean settingKnown) {
        return !settingKnown;
    }

    static boolean canSend(boolean activeInstance,
                           long expectedCanBusEpoch, long activeCanBusEpoch,
                           long ambientTemperatureEpoch,
                           long expectedDecisionGeneration, long currentDecisionGeneration,
                           boolean autoEnabled, boolean temperatureKnown, boolean temperatureCold,
                           boolean controlAlreadyActive) {
        return activeInstance
                && expectedCanBusEpoch == activeCanBusEpoch
                && ambientTemperatureEpoch == activeCanBusEpoch
                && expectedDecisionGeneration == currentDecisionGeneration
                && autoEnabled
                && temperatureKnown
                && temperatureCold
                && !controlAlreadyActive;
    }
}
