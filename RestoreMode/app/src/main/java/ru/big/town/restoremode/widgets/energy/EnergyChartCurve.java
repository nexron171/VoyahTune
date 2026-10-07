package ru.big.town.restoremode.widgets.energy;

/** Visual interpolation only: the recorded levels and consumption totals stay untouched. */
public final class EnergyChartCurve {
    public interface Sink {
        void moveTo(double x, double y);

        void cubicTo(double x1, double y1, double x2, double y2, double x, double y);
    }

    public static void trace(
            float[] x, float[] levels, boolean[] breaks, int count, double minDistance, Sink sink) {
        trace(x, levels, breaks, count, minDistance, 0, 100, sink);
    }

    public static void trace(
            float[] x,
            float[] levels,
            boolean[] breaks,
            int count,
            double minDistance,
            double minLevel,
            double maxLevel,
            Sink sink) {
        int n = Math.min(count, Math.min(x.length, levels.length)), start = 0;
        while (start < n) {
            if (!valid(x[start], levels[start], minDistance, minLevel, maxLevel)) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < n
                    && valid(x[end], levels[end], minDistance, minLevel, maxLevel)
                    && x[end] > x[end - 1]
                    && !(breaks != null && end < breaks.length && breaks[end])) {
                end++;
            }
            sink.moveTo(x[start], levels[start]);
            for (int i = start; i < end - 1; i++) {
                double third = ((double) x[i + 1] - x[i]) / 3;
                sink.cubicTo(
                        x[i] + third,
                        levels[i] + third * tangent(x, levels, i, start, end),
                        x[i + 1] - third,
                        levels[i + 1] - third * tangent(x, levels, i + 1, start, end),
                        x[i + 1],
                        levels[i + 1]);
            }
            start = end;
        }
    }

    private static boolean valid(
            float x, float level, double minDistance, double minLevel, double maxLevel) {
        return Float.isFinite(x)
                && x >= 0
                && x >= minDistance
                && Float.isFinite(level)
                && level >= minLevel
                && level <= maxLevel;
    }

    private static double slope(float[] x, float[] y, int i) {
        return ((double) y[i + 1] - y[i]) / ((double) x[i + 1] - x[i]);
    }

    private static double tangent(float[] x, float[] y, int i, int start, int end) {
        if (i == start) {
            return slope(x, y, i);
        }
        if (i == end - 1) {
            return slope(x, y, i - 1);
        }
        double before = slope(x, y, i - 1), after = slope(x, y, i);
        if (before * after <= 0) {
            return 0;
        }
        // Shared tangent at each knot makes the curve C1 continuous. Limiting its
        // magnitude to both adjacent slopes keeps ordered Bezier controls inside
        // each pair of measured levels: no overshoot, even next to a refill or plateau.
        return Math.copySign(Math.min(Math.abs(before), Math.abs(after)), before);
    }
}
