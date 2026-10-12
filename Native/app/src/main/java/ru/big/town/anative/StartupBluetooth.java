package ru.big.town.anative;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/** Enables the Bluetooth radio once per service creation without blocking main. */
final class StartupBluetooth {
    private static final String TAG = "NativeStartupBluetooth";
    private static final AtomicBoolean IN_PROGRESS = new AtomicBoolean();

    private StartupBluetooth() {}

    static void enable(Context context) {
        if (!IN_PROGRESS.compareAndSet(false, true)) return;
        Context application = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            try {
                enableAdapter(application);
            } catch (RuntimeException error) {
                Log.w(TAG, "Bluetooth enable request failed", error);
            } finally {
                IN_PROGRESS.set(false);
            }
        }, TAG);
        worker.setDaemon(true);
        worker.start();
    }

    @SuppressWarnings("deprecation") // Native targets the privileged Android 11 vehicle platform.
    private static void enableAdapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            Log.w(TAG, "Bluetooth adapter unavailable");
            return;
        }
        int state = adapter.getState();
        if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON) {
            Log.i(TAG, "Bluetooth already enabled or enabling");
            return;
        }
        if (adapter.enable()) {
            Log.i(TAG, "Bluetooth enable request accepted");
        } else {
            Log.w(TAG, "Bluetooth enable request rejected");
        }
    }
}
