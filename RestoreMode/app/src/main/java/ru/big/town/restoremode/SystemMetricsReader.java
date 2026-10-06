package ru.big.town.restoremode;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.FileReader;

/** Shared local reader for the dashboard and diagnostics; call from one background worker. */
final class SystemMetricsReader {
    static final long INTERVAL_MS = 5_000;
    private final Context context;
    private final SystemCpuSample.Tracker cpu = new SystemCpuSample.Tracker();

    SystemMetricsReader(Context context) { this.context = context; }

    Snapshot read(long generation) {
        long total = 0, available = -1;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                total = info.totalMem; available = info.availMem;
            }
        } catch (RuntimeException ignored) { }
        SystemCpuSample counters = null;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            counters = SystemCpuSample.parse(reader.readLine());
        } catch (Exception ignored) { /* Restricted by some firmware: leave CPU unavailable. */ }
        long now = SystemClock.elapsedRealtime();
        return new Snapshot(now, total, available, cpu.sample(counters, now, generation), counters != null);
    }

    static final class Snapshot {
        final long elapsed, totalMemoryBytes, availableMemoryBytes, usedMemoryBytes;
        final float cpuPercent, ramPercent;
        final boolean cpuReadable;
        Snapshot(long elapsed, long total, long available, float cpu, boolean cpuReadable) {
            this.elapsed = elapsed; totalMemoryBytes = total; availableMemoryBytes = available;
            ramPercent = SystemCpuSample.ramPercent(total, available);
            usedMemoryBytes = Float.isFinite(ramPercent) ? total - available : 0;
            cpuPercent = cpu; this.cpuReadable = cpuReadable;
        }
    }
}
