package ru.big.town.anative;

import java.util.ArrayList;
import java.util.List;

/** Rolling levels on a continuous 100 m axis, with consumption retained before decimation. */
final class EnergyHistory {
    static final int MAX_POINTS = 1501;
    static final float WINDOW_KM = 150;
    static final long MAX_SAMPLE_AGE_MS = 10_000;
    static final class Point {
        final float km, ev, fuel;
        final boolean gap;
        final double evDrop, fuelDrop, evKm, fuelKm;
        final double startEvDrop, startFuelDrop, startEvKm, startFuelKm;
        Point(float km, float ev, float fuel, boolean gap) { this(km, ev, fuel, gap, 0, 0, 0, 0); }
        Point(float km, float ev, float fuel, boolean gap,
              double evDrop, double fuelDrop, double evKm, double fuelKm) {
            this(km,ev,fuel,gap,evDrop,fuelDrop,evKm,fuelKm,evDrop,fuelDrop,evKm,fuelKm);
        }
        Point(float km, float ev, float fuel, boolean gap,
              double evDrop, double fuelDrop, double evKm, double fuelKm,
              double startEvDrop, double startFuelDrop, double startEvKm, double startFuelKm) {
            this.km=km; this.ev=ev; this.fuel=fuel; this.gap=gap;
            this.evDrop=evDrop; this.fuelDrop=fuelDrop; this.evKm=evKm; this.fuelKm=fuelKm;
            this.startEvDrop=startEvDrop;this.startFuelDrop=startFuelDrop;
            this.startEvKm=startEvKm;this.startFuelKm=startFuelKm;
        }
    }
    private final ArrayList<Point> points = new ArrayList<>();
    private final LevelConsumption battery = new LevelConsumption(true), fuelUse = new LevelConsumption();
    private boolean gap = true;
    private long lastTime = -1;

    void breakSegment() { gap=true; battery.breakSegment(); fuelUse.breakSegment(); }
    List<Point> points() { return new ArrayList<>(points); }
    void clear() { points.clear(); battery.clear(); fuelUse.clear(); breakSegment(); lastTime=-1; }

    boolean sample(double distance, float ev, float fuel, long now,
                   long distanceAt, long batteryAt, long fuelAt) {
        if (!Double.isFinite(distance) || distance<0 || !fresh(now,distanceAt)) {
            breakSegment(); return false;
        }
        ev=fresh(now,batteryAt)?level(ev):Float.NaN;
        fuel=fresh(now,fuelAt)?level(fuel):Float.NaN;
        if (!Float.isFinite(ev) && !Float.isFinite(fuel)) { breakSegment(); return false; }
        if (lastTime>=0 && (now<lastTime || now-lastTime>MAX_SAMPLE_AGE_MS)) breakSegment();
        lastTime=now;
        float bucket=(float)(Math.floor(distance*10d+.001d)/10d);
        Point old=points.isEmpty()?null:points.get(points.size()-1);
        // The caller owns the continuous axis. An invalid rollback must never erase old history.
        if (old!=null && bucket<old.km-.01f) { breakSegment(); return false; }
        if (old!=null && bucket-old.km>.51f) breakSegment();
        battery.observe(distance,ev); fuelUse.observe(distance,fuel);
        boolean same=old!=null && bucket<old.km+.05f;
        Point next=new Point(bucket,ev,fuel,same?(old.gap||gap):gap,
                battery.decrease(),fuelUse.decrease(),battery.distance(),fuelUse.distance(),
                same?old.startEvDrop:battery.decrease(),same?old.startFuelDrop:fuelUse.decrease(),
                same?old.startEvKm:battery.distance(),same?old.startFuelKm:fuelUse.distance());
        gap=false;
        if (same) {
            boolean changed=Float.compare(old.ev,ev)!=0 || Float.compare(old.fuel,fuel)!=0
                    || old.gap!=next.gap || old.evDrop!=next.evDrop || old.fuelDrop!=next.fuelDrop
                    || old.evKm!=next.evKm || old.fuelKm!=next.fuelKm;
            if (changed) points.set(points.size()-1,next);
            return changed;
        }
        points.add(next);
        while (points.size()>MAX_POINTS || (points.size()>1 && points.get(0).km<bucket-WINDOW_KM-.001f)) points.remove(0);
        return true;
    }
    static boolean fresh(long now,long at) { return at>=0 && now>=at && now-at<=MAX_SAMPLE_AGE_MS; }
    private static float level(float v) { return Float.isFinite(v)&&v>=0&&v<=100?v:Float.NaN; }
    private static boolean total(double v) { return Double.isFinite(v)&&v>=0; }

    void restore(List<Point> saved) {
        clear();
        for (Point p:saved) {
            if (!Float.isFinite(p.km)||p.km<0 || !Double.isFinite(p.evDrop)||!total(p.fuelDrop)||!total(p.evKm)||!total(p.fuelKm)) { clear(); return; }
            if (!points.isEmpty()) {
                Point old=points.get(points.size()-1);
                if (p.km<=old.km || p.fuelDrop<old.fuelDrop
                        || p.evKm<old.evKm || p.fuelKm<old.fuelKm) { clear(); return; }
            }
            points.add(new Point(p.km,level(p.ev),level(p.fuel),p.gap,p.evDrop,p.fuelDrop,p.evKm,p.fuelKm,
                    p.startEvDrop,p.startFuelDrop,p.startEvKm,p.startFuelKm));
            while (points.size()>MAX_POINTS || points.get(0).km<p.km-WINDOW_KM-.001f) points.remove(0);
        }
        if (!points.isEmpty()) {
            Point last=points.get(points.size()-1);
            battery.restore(new double[]{last.evDrop,last.evKm});
            fuelUse.restore(new double[]{last.fuelDrop,last.fuelKm});
        }
        breakSegment();
    }
}
