package ru.voyahtune.agent.policy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WifiScanLease implements AutoCloseable {
    public interface IO {
        long now();
        String lease() throws Exception;
        boolean enabled() throws Exception;
        void setEnabled(boolean enabled) throws Exception;
        void error(Exception failure);
        void uncertain(Exception failure);
    }

    private static final Pattern LEASE = Pattern.compile("([0-9]+):([0-9]+)");
    private final long boot;
    private final IO io;
    private boolean active;
    private boolean restoreEnabled;
    private boolean stopped;
    private boolean reported;

    public WifiScanLease(long boot, IO io) {
        if (boot < 0) throw new IllegalArgumentException("Boot identity unavailable");
        this.boot = boot;
        this.io = java.util.Objects.requireNonNull(io);
    }

    public synchronized boolean poll() {
        try {
            if (!stopped && requested()) pause();
            else restore();
        } catch (Exception failure) {
            report(failure);
            try { restore(); }
            catch (Exception unconfirmed) {
                stopped = true;
                io.uncertain(unconfirmed);
            }
        }
        return !stopped || active;
    }

    private boolean requested() throws Exception {
        String value = io.lease();
        Matcher match = LEASE.matcher(value == null ? "" : value);
        if (!match.matches()) return false;
        try {
            long leaseBoot = Long.parseLong(match.group(1));
            long timestamp = Long.parseLong(match.group(2));
            long now = io.now();
            return leaseBoot == boot && timestamp > 0 && timestamp <= now && now - timestamp < 10000;
        } catch (NumberFormatException malformed) { return false; }
    }

    private void pause() throws Exception {
        if (!active) {
            restoreEnabled = io.enabled();
            // Ownership precedes a vendor call which can mutate and then throw.
            active = true;
            io.setEnabled(false);
        }
        if (io.enabled()) throw new IllegalStateException("Wi-Fi autojoin pause not confirmed");
    }

    private void restore() throws Exception {
        if (!active) return;
        io.setEnabled(restoreEnabled);
        if (io.enabled() != restoreEnabled) throw new IllegalStateException("Wi-Fi autojoin restoration not confirmed");
        active = false;
    }

    public synchronized boolean external(boolean desired) {
        if (!active) return desired;
        restoreEnabled = desired;
        return false;
    }

    public synchronized void stop() { stopped = true; }

    @Override public synchronized void close() throws Exception {
        stopped = true;
        restore();
    }

    private void report(Exception failure) {
        if (reported) return;
        reported = true;
        try { io.error(failure); }
        catch (RuntimeException ignored) { /* Diagnostics must not interrupt restoration. */ }
    }
}
