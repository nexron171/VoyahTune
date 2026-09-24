package ru.big.town.anative;

/** Approximate free tank volume from OEM capacity and fuel percentage, never from mRemain. */
final class FuelRefillEstimate {
    static Integer liters(int capacityLiters, float remainingPercent) {
        if (capacityLiters <= 0 || capacityLiters > 200 || !Float.isFinite(remainingPercent)
                || remainingPercent < 0 || remainingPercent > 100) return null;
        return Math.round(capacityLiters * (100f - remainingPercent) / 100f);
    }
}
