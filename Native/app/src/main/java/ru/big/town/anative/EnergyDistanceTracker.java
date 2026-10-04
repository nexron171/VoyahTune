package ru.big.town.anative;

/** Continuous axis from observed OEM trip increments, without inventing distance between trips. */
final class EnergyDistanceTracker {
    private double distance;
    private float lastTrip = Float.NaN;
    private int lastCounter = -1;
    private boolean anchor = true;

    void breakSegment() { anchor = true; }
    boolean observe(float trip, int counter) {
        if (!Float.isFinite(trip) || trip < 0) { anchor = true; return false; }
        boolean reset = (Float.isFinite(lastTrip) && trip < lastTrip - .05f)
                || (counter >= 0 && lastCounter >= 0 && counter < lastCounter);
        if (!anchor && !reset && Float.isFinite(lastTrip)) distance += Math.max(0, trip - lastTrip);
        lastTrip = !anchor&&!reset&&Float.isFinite(lastTrip)?Math.max(lastTrip,trip):trip;
        if (counter >= 0) lastCounter = counter;
        anchor = false;
        return reset;
    }
    double distance() { return distance; }
    double[] snapshot() { return new double[]{distance, lastTrip, lastCounter}; }
    void restore(double[] state) {
        distance = 0; lastTrip = Float.NaN; lastCounter = -1; anchor = true;
        if (state == null || state.length != 3 || !Double.isFinite(state[0]) || state[0] < 0
                || !Double.isFinite(state[1]) || state[1] < 0 || state[2] < -1
                || !Double.isFinite(state[2]) || state[2] > Integer.MAX_VALUE || state[2] != Math.rint(state[2])) return;
        distance = state[0]; lastTrip = (float) state[1]; lastCounter = (int) state[2];
    }
}
