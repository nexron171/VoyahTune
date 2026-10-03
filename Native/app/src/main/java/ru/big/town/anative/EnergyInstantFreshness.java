package ru.big.town.anative;

import java.util.Arrays;

/** A successful getter can return the same OEM cache indefinitely, including false live zeros. */
final class EnergyInstantFreshness {
    private int index = -1;
    private float[] previous;
    private long confirmedAt = -1;

    long observe(EnergyTelemetrySample sample, long now, boolean callback) {
        boolean first = previous == null;
        boolean changed = !first && (index != sample.sourceIndex || !Arrays.equals(previous, sample.values));
        index = sample.sourceIndex;
        previous = sample.values.clone();
        // The OEM consumer ignores index zero; negative values and 511 denote no sample.
        boolean valid = index > 0 && index < 511
                && (Float.isFinite(previous[0]) || Float.isFinite(previous[1]));
        if (!valid) { reset(); return -1; }
        if (changed || (first && callback)) confirmedAt = now;
        // First getter only establishes a baseline. Identical getters/callbacks cannot renew it.
        return confirmedAt;
    }

    void reset() { index = -1; previous = null; confirmedAt = -1; }
}
