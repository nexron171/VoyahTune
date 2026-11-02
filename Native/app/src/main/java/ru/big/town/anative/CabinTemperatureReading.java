package ru.big.town.anative;

import ru.big.town.common.CabinTemperatureProtocol;

/** Keeps the last valid observation; silence and invalid frames do not erase it. */
final class CabinTemperatureReading {
    private double temperature = Double.NaN;
    private long sampledAt = -1;

    synchronized boolean accept(int raw, long observedAt, long now) {
        double decoded = CabinTemperatureProtocol.decode(raw);
        if (!Double.isFinite(decoded)
                || observedAt < 0
                || observedAt > now
                || observedAt < sampledAt) return false;
        temperature = decoded;
        sampledAt = observedAt;
        return true;
    }

    synchronized double value() {
        return temperature;
    }
}
