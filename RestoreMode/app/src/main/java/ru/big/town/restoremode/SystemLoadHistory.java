package ru.big.town.restoremode;

import java.util.ArrayList;
import java.util.List;

/** Monotonic two-minute window; unknown samples remain gaps rather than zeroes. */
final class SystemLoadHistory {
    static final long WINDOW_MS = 120_000, STALE_MS = 15_000;
    static final class Point {
        final long elapsed;
        final float cpu, ram;
        Point(long elapsed, float cpu, float ram) {
            this.elapsed = elapsed; this.cpu = valid(cpu); this.ram = valid(ram);
        }
    }
    private final List<Point> points = new ArrayList<>();
    private static float valid(float value) {
        return Float.isFinite(value) && value >= 0 && value <= 100 ? value : Float.NaN;
    }
    boolean add(long elapsed, float cpu, float ram, long now) {
        if (elapsed < 0 || elapsed > now || now - elapsed > STALE_MS
                || (!points.isEmpty() && elapsed <= points.get(points.size() - 1).elapsed)) return false;
        points.add(new Point(elapsed, cpu, ram));
        prune(now);
        // Bound the window even after rapid lifecycle changes.
        while (points.size() > 25) points.remove(0);
        return true;
    }
    void prune(long now) { points.removeIf(p -> now < p.elapsed || now - p.elapsed > WINDOW_MS); }
    List<Point> points() { return points; }
    float current(boolean cpu, long now) {
        if (points.isEmpty()) return Float.NaN;
        Point p = points.get(points.size() - 1);
        return now >= p.elapsed && now - p.elapsed < STALE_MS
                ? (cpu ? p.cpu : p.ram) : Float.NaN;
    }
    static boolean joins(Point a, Point b) { return b.elapsed > a.elapsed && b.elapsed - a.elapsed <= 7_500; }
}
