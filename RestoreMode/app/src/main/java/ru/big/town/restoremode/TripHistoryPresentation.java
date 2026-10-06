package ru.big.town.restoremode;

/** Units and speed bands shared by the map and its legend. */
final class TripHistoryPresentation {
    static final int[] COLORS = {0xff64d8ff, 0xff2979ff, 0xffffdf38, 0xffff922e, 0xfff04444};
    static final String[] BANDS = {"0–25", "26–59", "60–79", "80–129", "130+"};
    static int speedColor(double speed) {
        return COLORS[speed < 26 ? 0 : speed < 60 ? 1 : speed < 80 ? 2 : speed < 130 ? 3 : 4];
    }
    static double rate(double dropBefore, double dropAfter, double kmBefore, double kmAfter, double capacity) {
        return rate(dropBefore, dropAfter, kmBefore, kmAfter, capacity, false);
    }
    static double rate(double dropBefore, double dropAfter, double kmBefore, double kmAfter, double capacity, boolean electricity) {
        double km = kmAfter - kmBefore, drop = dropAfter - dropBefore;
        if (!Double.isFinite(km) || !Double.isFinite(drop) || !Double.isFinite(capacity)) return Double.NaN;
        double value = km > .00001 && (electricity || drop >= 0) && capacity > 0 ? drop * capacity / km : Double.NaN;
        return Double.isFinite(value) ? value : Double.NaN;
    }
    static double averagePer100(double amount, double distanceKm) {
        return averagePer100(amount, distanceKm, false);
    }
    static double averagePer100(double amount, double distanceKm, boolean electricity) {
        if (!Double.isFinite(amount) || (!electricity && amount < 0) || !Double.isFinite(distanceKm) || distanceKm <= 0) return Double.NaN;
        double value = amount / distanceKm * 100;
        return Double.isFinite(value) ? value : Double.NaN;
    }
    static boolean drawableSegment(double[] previous, double[] next) {
        return previous.length >= 8 && next.length >= 8 && next[7] == 0 && next[0] > previous[0]
                && next[5] > 0 && Double.isFinite(next[6]) && next[6] >= 0 && next[6] <= 200;
    }
}
