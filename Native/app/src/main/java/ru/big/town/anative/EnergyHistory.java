package ru.big.town.anative;

import java.util.ArrayList;
import java.util.List;

/** Measured samples at OEM trip-distance steps. Never integrates speed or interpolates gaps. */
final class EnergyHistory {
    static final int MAX_POINTS = 600;
    static final float WINDOW_KM = 30;
    static final long MAX_SAMPLE_AGE_MS = 10_000;
    static final class Point {
        final float km, ev, fuel;
        final boolean gap;
        Point(float km, float ev, float fuel, boolean gap) {
            this.km = km; this.ev = ev; this.fuel = fuel; this.gap = gap;
        }
    }
    private final ArrayList<Point> points = new ArrayList<>();
    private boolean gap = true;
    private long lastTime = -1;

    void breakSegment() { gap = true; }
    List<Point> points() { return new ArrayList<>(points); }
    void clear() { points.clear(); gap = true; lastTime = -1; }

    boolean sample(float distance, float ev, float fuel, long now,
                   long distanceAt, long consumptionAt) {
        if (!Float.isFinite(distance) || distance < 0 || now < distanceAt || now < consumptionAt
                || distanceAt < 0 || consumptionAt < 0
                || now - distanceAt > MAX_SAMPLE_AGE_MS
                || now - consumptionAt > MAX_SAMPLE_AGE_MS) {
            gap = true; return false;
        }
        if (lastTime >= 0 && (now < lastTime || now - lastTime > MAX_SAMPLE_AGE_MS)) gap = true;
        lastTime = now; // unchanged distance can still carry fresh, continuous observations
        if (!points.isEmpty()) {
            float previous = points.get(points.size() - 1).km;
            if (distance < previous - .01f) clear(); // OEM trip reset / odometer rollback.
            else if (distance < previous + .05f) {
                if (!Float.isFinite(ev) || !Float.isFinite(fuel)) gap = true;
                return false; // no repeated points or fabricated distance while parked.
            } else if (distance - previous > .5f) gap = true;
        }
        points.add(new Point(distance, ev, fuel, gap));
        gap = false;
        while (points.size() > MAX_POINTS
                || (points.size() > 1 && points.get(0).km < distance - WINDOW_KM)) points.remove(0);
        return true;
    }

    void restore(List<Point> saved) {
        clear();
        for (Point p : saved) {
            if (!Float.isFinite(p.km) || p.km < 0) { clear(); return; }
            if (!points.isEmpty() && p.km <= points.get(points.size()-1).km) { clear(); return; }
            points.add(p);
            while (points.size() > MAX_POINTS || points.get(0).km < p.km - WINDOW_KM) points.remove(0);
        }
        gap = true; // process restart is a gap, even if the stored values happen to match.
    }
}
