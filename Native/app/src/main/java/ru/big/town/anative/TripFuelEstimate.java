package ru.big.town.anative;

/** Net fuel-level change over the observed part of an OEM trip, not an OEM consumption sensor. */
final class TripFuelEstimate {
    static final float TANK_LITERS = 56f;
    static final float MIN_DISTANCE_KM = 1f;
    private static final float REFILL_RISE_PERCENT = 5f;
    private float startKm = Float.NaN, lastKm, anchorFuel, minimumFuel, lastFuel, completedLiters;

    void clear() { startKm = Float.NaN; completedLiters = 0; }

    void observe(float km, float fuel) {
        if (!Float.isFinite(km) || km < 0 || !Float.isFinite(fuel) || fuel < 0 || fuel > 100) return;
        if (!Float.isFinite(startKm) || km < lastKm - .05f) {
            startKm = lastKm = km;
            anchorFuel = minimumFuel = lastFuel = fuel;
            completedLiters = 0;
            return;
        }
        if (fuel - minimumFuel >= REFILL_RISE_PERCENT) {
            // Preserve the preceding leg across a substantial refill. Small rebounds are netted,
            // never added as a new consumption leg. This is an estimate from a coarse tank sensor.
            completedLiters += Math.max(0, anchorFuel - minimumFuel) * TANK_LITERS / 100f;
            anchorFuel = minimumFuel = fuel;
        }
        minimumFuel = Math.min(minimumFuel, fuel);
        lastFuel = fuel;
        lastKm = km;
    }

    float distanceKm() { return Float.isFinite(startKm) ? Math.max(0, lastKm - startKm) : Float.NaN; }

    float average() {
        float km = distanceKm();
        if (!Float.isFinite(km) || km < MIN_DISTANCE_KM) return Float.NaN;
        float liters = completedLiters + Math.max(0, anchorFuel - lastFuel) * TANK_LITERS / 100f;
        return liters * 100f / km;
    }

    float[] snapshot() {
        return new float[]{startKm, lastKm, anchorFuel, minimumFuel, lastFuel, completedLiters};
    }

    void restore(float[] saved) {
        clear();
        if (saved == null || saved.length != 6) return;
        for (float v : saved) if (!Float.isFinite(v) || v < 0) return;
        if (saved[1] < saved[0] || saved[2] > 100 || saved[3] > 100 || saved[4] > 100
                || saved[3] > saved[2] || saved[3] > saved[4]) return;
        startKm = saved[0]; lastKm = saved[1]; anchorFuel = saved[2];
        minimumFuel = saved[3]; lastFuel = saved[4]; completedLiters = saved[5];
    }
}
