package ru.big.town.anative;

import java.util.Objects;

/** A dead or stalled CarPlay process cannot keep its last turn on the cluster indefinitely. */
final class CarPlayNavigationLease {
    static final long TIMEOUT_MS = 5000;

    static final class Maneuver {
        final int icon, distance;
        final String road;

        Maneuver(int icon, int distance, String road) {
            this.icon = icon;
            this.distance = distance;
            this.road = road;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Maneuver)) return false;
            Maneuver other = (Maneuver) value;
            return icon == other.icon && distance == other.distance && road.equals(other.road);
        }

        @Override
        public int hashCode() {
            return Objects.hash(icon, distance, road);
        }
    }

    private Maneuver maneuver;
    private long sampled;

    void update(boolean active, int icon, int distance, String road, long sampled, long now) {
        if (!active
                || icon < 0
                || icon > 9
                || distance < 0
                || sampled < 0
                || now < sampled
                || now - sampled >= TIMEOUT_MS) {
            clear();
            return;
        }
        String normalized = road == null ? "" : road.replace('\0', ' ');
        if (normalized.length() > 120) normalized = normalized.substring(0, 120);
        maneuver = new Maneuver(icon, distance, normalized);
        this.sampled = sampled;
    }

    Maneuver current(long now) {
        if (now < sampled || now - sampled >= TIMEOUT_MS) clear();
        return maneuver;
    }

    void clear() {
        maneuver = null;
    }
}
