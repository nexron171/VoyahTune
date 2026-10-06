package ru.big.town.anative;

/** Net SOC use, with at most one percentage point of recovery per uninterrupted rise. */
final class BatteryConsumption {
    static final double MAX_RECOVERY_PERCENT = 1, EPS = .00001;
    private double rise, credited;

    void breakSegment() { rise = credited = 0; }

    double delta(double previous, double current) {
        if (!Double.isFinite(previous) || !Double.isFinite(current)) { breakSegment(); return 0; }
        double drop = previous - current;
        if (drop > 0) { breakSegment(); return drop; }
        // Repeated callbacks at the same level must not split a charging episode.
        if (drop == 0) return 0;
        rise -= drop;
        double before = credited;
        credited = rise <= MAX_RECOVERY_PERCENT + EPS ? rise : 0;
        // Once the rise exceeds the limit, undo its entire previously credited part.
        return before - credited;
    }
}
