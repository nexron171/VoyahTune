package ru.big.town.anative;

/** Accepts only a sensor observation made after its matching settings snapshot. */
final class SensorSampleFence {
    final long settingsGeneration;
    final long liveRevisionFence;

    SensorSampleFence(long settingsGeneration, long liveRevisionFence) {
        this.settingsGeneration = settingsGeneration;
        this.liveRevisionFence = liveRevisionFence;
    }

    boolean accepts(long sensorRevision, long querySettingsGeneration) {
        return querySettingsGeneration == settingsGeneration
                || sensorRevision > liveRevisionFence;
    }
}
