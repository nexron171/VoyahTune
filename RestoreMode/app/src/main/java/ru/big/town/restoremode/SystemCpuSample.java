package ru.big.town.restoremode;

/** Aggregate CPU counters: guest/guest_nice are already included in user/nice. */
final class SystemCpuSample {
    final long total, idle;
    private SystemCpuSample(long total, long idle) { this.total = total; this.idle = idle; }

    static SystemCpuSample parse(String line) {
        if (line == null) return null;
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 5 || !"cpu".equals(fields[0])) return null;
        try {
            long total = 0, idle = 0;
            for (int i = 1; i < fields.length && i <= 8; i++) {
                long value = Long.parseLong(fields[i]);
                if (value < 0) return null;
                total = Math.addExact(total, value);
                if (i == 4 || i == 5) idle = Math.addExact(idle, value);
            }
            return new SystemCpuSample(total, idle);
        } catch (IllegalArgumentException | ArithmeticException e) { return null; }
    }

    float percentSince(SystemCpuSample previous) {
        if (previous == null) return Float.NaN;
        long delta = total - previous.total, idleDelta = idle - previous.idle;
        if (delta <= 0 || idleDelta < 0 || idleDelta > delta) return Float.NaN;
        return (float) (100.0 * (delta - idleDelta) / delta);
    }

    /** Worker-confined baseline; visible sessions must never share a CPU averaging interval. */
    static final class Tracker {
        private SystemCpuSample previous;
        private long previousAt = -1, previousGeneration = Long.MIN_VALUE;
        float sample(SystemCpuSample current, long at, long generation) {
            float value = current != null && previousGeneration == generation && previousAt >= 0
                    && at > previousAt && at - previousAt <= 7_500
                    ? current.percentSince(previous) : Float.NaN;
            previous = current; previousAt = at; previousGeneration = generation;
            return value;
        }
    }

    static float ramPercent(long total, long available) {
        if (total <= 0 || available < 0 || available > total) return Float.NaN;
        return (float) (100.0 * (total - available) / total);
    }
}
