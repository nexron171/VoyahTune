package ru.big.town.anative;

import java.util.ArrayList;
import java.util.List;

/** Odometer cadence and local monotonic time; GPS only supplies coordinates. */
final class TripRecorder {
    static final long STEP_METERS = 100, MAX_FIX_AGE_MS = 10_000, MAX_SENSOR_AGE_MS = 20_000;
    static final double MAX_SPEED_KMH = 200;
    static final class Point {
        final String kind;
        final double[] values;
        Point(String kind, double... values) { this.kind = kind; this.values = values; }
    }
    final List<Point> pending = new ArrayList<>();
    double distanceMeters, startSoc = Double.NaN, endSoc = Double.NaN;
    double startFuel = Double.NaN, endFuel = Double.NaN, evDrop, fuelDrop, evMeters, fuelMeters;
    long sequence;
    double currentOdo = Double.NaN, resumeOdo = Double.NaN;
    private double lastOdo = Double.NaN, lastSoc = Double.NaN, lastFuel = Double.NaN;
    private final BatteryConsumption recovery = new BatteryConsumption();
    private double bucketMeters, anchorDistance, anchorLat, anchorLon;
    private long lastOdoTime = -1, bucketStart = -1, anchorTime = -1;
    private boolean gap = true;
    private boolean energyGap = true;
    private double latitude, longitude;
    private long fixElapsed = -1;

    void breakObservation() {
        lastOdo = lastSoc = lastFuel = Double.NaN;
        recovery.breakSegment();
        lastOdoTime = bucketStart = anchorTime = fixElapsed = -1;
        bucketMeters = 0; gap = energyGap = true;
    }
    void clearLocation() { fixElapsed = -1; gap = true; anchorTime = -1; }

    void location(double lat, double lon, float accuracy, long fixTime, long now, boolean mock) {
        if (mock || !Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                || !Float.isFinite(accuracy) || accuracy < 0 || accuracy > 50
                || fixTime < 0 || fixTime > now || now - fixTime > MAX_FIX_AGE_MS) {
            // Rejecting a fix must not let the next spoof replace a trusted coordinate anchor.
            fixElapsed = -1; gap = true; return;
        }
        if (fixTime <= fixElapsed) return;
        latitude = lat; longitude = lon; fixElapsed = fixTime;
    }

    boolean sample(double odoMeters, double soc, double fuel, long elapsed, long wall) {
        soc = validLevel(soc); fuel = validLevel(fuel);
        if (Double.isFinite(soc)) { if (!Double.isFinite(startSoc)) startSoc = soc; endSoc = soc; }
        if (Double.isFinite(fuel)) { if (!Double.isFinite(startFuel)) startFuel = fuel; endFuel = fuel; }
        if (!Double.isFinite(odoMeters) || odoMeters <= 0) { breakObservation(); return false; }
        boolean first = lastOdoTime < 0;
        double delta = Double.isFinite(lastOdo) ? odoMeters - lastOdo : 0;
        if (lastOdoTime < 0 || elapsed < lastOdoTime || elapsed - lastOdoTime > MAX_SENSOR_AGE_MS || delta < 0) {
            lastSoc = lastFuel = Double.NaN; delta = 0; gap = energyGap = true; bucketMeters = 0; bucketStart = elapsed; anchorTime = -1;
            recovery.breakSegment();
        }
        if (first && Double.isFinite(resumeOdo) && odoMeters >= resumeOdo) delta = odoMeters - resumeOdo;
        resumeOdo = Double.NaN; currentOdo = odoMeters;
        distanceMeters += delta;
        if (Double.isFinite(lastSoc) && Double.isFinite(soc)) { evDrop += recovery.delta(lastSoc, soc); evMeters += delta; }
        else recovery.breakSegment();
        if (Double.isFinite(lastFuel) && Double.isFinite(fuel)) { fuelDrop += Math.max(0, lastFuel - fuel); fuelMeters += delta; }
        lastSoc = soc; lastFuel = fuel; lastOdo = odoMeters; lastOdoTime = elapsed;
        if (bucketStart < 0) bucketStart = elapsed;
        bucketMeters += delta;
        if (!first && bucketMeters + .001 < STEP_METERS) return false;
        // A jump is one observation, never fabricated intermediate coordinates.
        long segmentMs = elapsed - bucketStart;
        double speed = speed(bucketMeters, segmentMs);
        pending.add(new Point("energy", distanceMeters / 1000, wall, soc, fuel, evDrop, fuelDrop, evMeters / 1000, fuelMeters / 1000, energyGap ? 1 : 0));
        energyGap = false;
        boolean fresh = fixElapsed >= 0 && elapsed >= fixElapsed && elapsed - fixElapsed <= MAX_FIX_AGE_MS;
        if (fresh && (first || speed <= MAX_SPEED_KMH)) {
            // Coordinate displacement is a separate sanity check, never the displayed speed/distance.
            double displacement = anchorTime < 0 ? 0 : geodesic(anchorLat, anchorLon, latitude, longitude);
            double travelled = distanceMeters - anchorDistance;
            boolean plausible = anchorTime < 0 || (speed(displacement, elapsed - anchorTime) <= MAX_SPEED_KMH
                    && displacement <= travelled + 100);
            if (plausible) {
                pending.add(new Point("track", distanceMeters / 1000, wall, latitude, longitude,
                        first ? 0 : bucketMeters, first ? 0 : segmentMs, first ? 0 : speed, gap || anchorTime < 0 ? 1 : 0));
                anchorLat = latitude; anchorLon = longitude; anchorTime = elapsed; anchorDistance = distanceMeters;
                gap = false;
            } else { gap = true; }
        } else { gap = true; }
        bucketMeters = 0; bucketStart = elapsed;
        return true;
    }

    void endpoint(long wall) {
        pending.add(new Point("energy", distanceMeters / 1000, wall, endSoc, endFuel,
                evDrop, fuelDrop, evMeters / 1000, fuelMeters / 1000, energyGap ? 1 : 0));
    }

    static double validLevel(double v) { return Double.isFinite(v) && v >= 0 && v <= 100 ? v : Double.NaN; }
    void levelsAtDrive(double soc, double fuel) {
        if (!Double.isFinite(startSoc)) startSoc = validLevel(soc);
        if (!Double.isFinite(startFuel)) startFuel = validLevel(fuel);
    }
    void endLevels(double soc, double fuel) { endSoc = validLevel(soc); endFuel = validLevel(fuel); }
    static double speed(double meters, long ms) { return ms > 0 ? meters * 3600 / ms : Double.POSITIVE_INFINITY; }
    static boolean preciseOdometerCompatible(double wholeKm, double ticks) {
        return Double.isFinite(wholeKm) && Double.isFinite(ticks) && Math.abs(ticks / 10 - wholeKm) <= 1.1;
    }
    static double geodesic(double a, double b, double c, double d) {
        double x = Math.sin(Math.toRadians(c - a) / 2), y = Math.sin(Math.toRadians(d - b) / 2);
        double h = x * x + Math.cos(Math.toRadians(a)) * Math.cos(Math.toRadians(c)) * y * y;
        return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }
}
