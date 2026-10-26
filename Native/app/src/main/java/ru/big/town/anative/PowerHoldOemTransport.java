package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Calls the stock manager in VehicleSetting through the vehicle agent. */
final class PowerHoldOemTransport {
    static final String ACTION_REQUEST = "ru.big.town.anative.POWER_HOLD_OEM_REQUEST";
    static final String ACTION_STATUS = "ru.big.town.anative.POWER_HOLD_OEM_STATUS";
    static final String EXTRA_STATUS = "status";
    static final String OEM_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String OEM_PACKAGE = "com.qinggan.app.vehiclesetting";
    private static final String TAG = "PowerHoldOem";
    private static final long RESPONSE_TIMEOUT_MS = 3_000L;

    private final Context context;

    PowerHoldOemTransport(Context context) {
        this.context = context.getApplicationContext();
    }

    PowerHoldPolicy.Outcome activate(boolean enableEngineExtender) {
        Bundle result = request("activate", enableEngineExtender);
        if (result == null) return PowerHoldPolicy.Outcome.RESULT_UNKNOWN;
        if (result.getInt("protocol", 0) != 1) {
            return PowerHoldPolicy.Outcome.TRANSPORT_FAILURE;
        }
        return PowerHoldPolicy.Outcome.fromIpcCode(
                result.getInt("outcome", PowerHoldPolicy.Outcome.RESULT_UNKNOWN.ipcCode));
    }

    Integer readStatus() {
        Bundle result = request("status", false);
        if (result == null || result.getInt("protocol", 0) != 1) return null;
        int status = result.getInt(EXTRA_STATUS, -1);
        return status >= 0 && status <= 2 ? status : null;
    }

    private Bundle request(String operation, boolean enableEngineExtender) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Power Hold OEM request must run off the main thread");
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Bundle> response = new AtomicReference<>();
        Intent request = new Intent(ACTION_REQUEST).setPackage(OEM_PACKAGE);
        request.putExtra("operation", operation);
        request.putExtra("engineExtender", enableEngineExtender);
        request.putExtra("deadline", SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS);
        try {
            context.sendOrderedBroadcast(
                    request,
                    OEM_PERMISSION,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context receiverContext, Intent intent) {
                            Bundle result = getResultExtras(false);
                            response.set(result == null ? new Bundle() : new Bundle(result));
                            completed.countDown();
                        }
                    },
                    new Handler(Looper.getMainLooper()),
                    0,
                    null,
                    null);
            if (!completed.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, operation + " result unavailable; request will not be replayed");
                return null;
            }
            return response.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException error) {
            Log.w(TAG, operation + " failed", error);
            return null;
        }
    }
}
