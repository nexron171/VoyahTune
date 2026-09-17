package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Owns vehicle-mode decoding, persistence policy and the remember-last control channel.
 *
 * <p>The CanBus connection barrier and all following mode events share one serial handler. This
 * orders door/gear restore events before subsequent mode feedback. {@link VehicleStateControllers} owns the CAN subscription and invokes this
 * controller with typed connection/state inputs. Remember-last opt-outs are applied immediately.</p>
 */
final class ModeFeedbackController implements AutoCloseable {
    static final String ACTION_REMEMBER_LAST_CHANGED =
            "ru.big.town.anative.MODE_REMEMBER_CHANGED";

    private static final String TAG = "$$$ ModeFeedback $$$";
    private static final String BIND_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    private final Context appContext;
    private final Handler feedbackHandler;
    private final BroadcastReceiver rememberLastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (closed) return;
            String modeKey = intent.getStringExtra("modeKey");
            // A missing/null optional value is enabled; only an explicit false is an opt-out.
            boolean rememberLast = !intent.hasExtra("rememberLast")
                    || intent.getBooleanExtra("rememberLast", true);
            MainActivity.updateRememberLastMode(appContext, modeKey, rememberLast);
        }
    };

    private boolean receiverRegistered;
    private volatile boolean closed;

    static ModeFeedbackController create(Context context, Handler feedbackHandler) {
        ModeFeedbackController controller = new ModeFeedbackController(context, feedbackHandler);
        controller.start();
        return controller;
    }

    private ModeFeedbackController(Context context, Handler feedbackHandler) {
        appContext = context.getApplicationContext();
        this.feedbackHandler = feedbackHandler;
    }

    private void start() {
        try {
            ContextCompat.registerReceiver(appContext, rememberLastReceiver,
                    new IntentFilter(ACTION_REMEMBER_LAST_CHANGED), BIND_PERMISSION,
                    feedbackHandler, ContextCompat.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    void onConnected() {
        if (closed) return;
        ApplyEngine.activateWake("CanBus connected");
    }

    void onVehicleState(int id, int state) {
        if (closed) return;
        ModeFeedbackDecoder.Feedback feedback = ModeFeedbackDecoder.decode(id, state);
        if (feedback == null) {
            if (NativeLog.get().isRunning()) {
                Log.i(TAG, "unknown mode state ignored id=" + id + " state=" + state);
            }
            return;
        }

        try {
            ApplyEngine.persistModeFeedbackIfAllowed(
                    appContext, feedback.modeKey, feedback.mode);
        } catch (RuntimeException e) {
            Log.w(TAG, "persist feedback: " + e.getMessage());
        }
        if (NativeLog.get().isRunning()) {
            Log.i(TAG, "VSTATE mode id=" + id + " state=" + state);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(rememberLastReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
    }
}
