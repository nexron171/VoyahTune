package ru.big.town.anative;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import ru.big.town.common.CarPlayWifiScanProtocol;

/** Publishes the authenticated owner's heartbeat; the system agent owns recovery. */
final class CarPlayWifiScanController implements AutoCloseable {
    private final Context context;
    private final HandlerThread thread = new HandlerThread("CarPlayWifiScan");
    private final Handler worker;
    private final Runnable publish = this::publishLease;
    private volatile long requestedAt;
    private long lastScheduledAt;
    private boolean paused;

    CarPlayWifiScanController(Context context) {
        this.context = context.getApplicationContext();
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void update(boolean pause, long timestamp) {
        long now = SystemClock.elapsedRealtime();
        pause =
                pause
                        && timestamp > 0
                        && timestamp <= now
                        && now - timestamp < CarPlayWifiScanProtocol.LEASE_MILLIS;
        if (pause == paused
                && (!pause
                        || now - lastScheduledAt < CarPlayWifiScanProtocol.RENEW_INTERVAL_MILLIS)) {
            return;
        }
        paused = pause;
        requestedAt = pause ? timestamp : 0;
        lastScheduledAt = now;
        worker.removeCallbacks(publish);
        worker.post(publish);
    }

    private void publishLease() {
        try {
            int bootCount = Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
            String lease =
                    CarPlayWifiScanProtocol.leaseValue(
                            bootCount, requestedAt, SystemClock.elapsedRealtime());
            if (!Settings.Global.putString(
                    context.getContentResolver(), CarPlayWifiScanProtocol.LEASE_SETTING, lease)) {
                Log.w("CarPlayWifiScan", "Scan pause heartbeat was not accepted");
            }
        } catch (RuntimeException unavailable) {
            Log.w("CarPlayWifiScan", "Cannot publish scan pause heartbeat", unavailable);
        }
    }

    @Override
    public void close() {
        update(false, 0);
        thread.quitSafely();
    }
}
