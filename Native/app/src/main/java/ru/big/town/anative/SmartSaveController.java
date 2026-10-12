package ru.big.town.anative;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import ru.big.town.common.SmartSaveSettings;

import java.util.Arrays;
import java.util.Map;

/** Event-driven speed control; only charge and the OEM charge target use a minute timer. */
final class SmartSaveController implements AutoCloseable {
    private static final String TAG = "SmartSave";
    private static final long CHARGE_INTERVAL_MS = 60_000;
    private static final long MAX_CHARGE_AGE_MS = 90_000;
    private static final Uri SETTINGS_URI =
            Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");
    private static final OemVehicleStateTransport.StateKey MODE =
            new OemVehicleStateTransport.StateKey(
                    VehicleRestorePolicy.SOC_MODE, VehicleRestorePolicy.SOC_MODE_ID);
    private static final OemVehicleStateTransport.StateKey TARGET =
            new OemVehicleStateTransport.StateKey(
                    VehicleRestorePolicy.SAVE_CHARGE_LEVEL,
                    VehicleRestorePolicy.SAVE_CHARGE_LEVEL_ID);

    private final Context context;
    private final HandlerThread thread = new HandlerThread("SmartSave");
    private final Handler handler;
    private final CanBusEventHub hub;
    private final SharedPreferences preferences;
    private final SmartSavePolicy policy;
    private final Runnable poll = this::refreshCharge;
    private final Runnable retryConfiguration = this::refreshConfiguration;
    private final BroadcastReceiver settingsChanged =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refreshConfiguration();
                }
            };

    private CanBusEventHub.Subscription subscription;
    private Bundle configuration;
    private boolean connected;
    private boolean temporaryElectric;
    private float charge = Float.NaN;
    private long chargeTime = -1;
    private int target = -1;
    private int speed = -1;
    private int observedMode = -1;
    private int requestedMode = -1;
    private volatile long commandGeneration;
    private volatile boolean closed;

    SmartSaveController(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("SmartSave", Context.MODE_PRIVATE);
        policy = new SmartSavePolicy(preferences.getBoolean("chargingLocked", false));
        temporaryElectric = preferences.getBoolean("temporaryElectric", false);
        hub = CanBusEventHub.get(context);
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            ContextCompat.registerReceiver(
                    context,
                    settingsChanged,
                    new IntentFilter(SmartSaveSettings.CHANGED),
                    "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE",
                    handler,
                    ContextCompat.RECEIVER_EXPORTED);
        } catch (RuntimeException error) {
            thread.quitSafely();
            throw error;
        }
        handler.post(this::refreshConfiguration);
    }

    private Bundle readConfiguration() {
        try {
            return context.getContentResolver().call(SETTINGS_URI, "driveHookV2", "snapshot", null);
        } catch (RuntimeException error) {
            Log.w(TAG, "Settings unavailable", error);
            return null;
        }
    }

    private boolean saveSelected(Bundle settings) {
        return settings != null
                && "SREV".equals(settings.getString("energy"))
                && !settings.getBoolean("forcedEv", false);
    }

    private void refreshConfiguration() {
        handler.removeCallbacks(retryConfiguration);
        if (closed) {
            return;
        }
        commandGeneration++;
        requestedMode = -1;
        configuration = readConfiguration();
        if (!saveSelected(configuration)) {
            stopWatching();
            if (configuration != null) {
                temporaryElectric = false;
                persistState();
            } else {
                handler.postDelayed(retryConfiguration, CHARGE_INTERVAL_MS);
            }
            return;
        }
        if (!configuration.getBoolean(SmartSaveSettings.ENABLED, false)) {
            stopWatching();
            persistState();
            if (temporaryElectric && configuration.getInt("acc", -1) == 2) {
                requestMode(VehicleRestorePolicy.SOC_SREV);
            }
            return;
        }
        if (configuration.getInt("acc", -1) != 2) {
            stopWatching();
            return;
        }
        if (subscription == null) {
            subscription =
                    hub.subscribe(
                            CanBusEventRouter.INTEREST_CONNECTION
                                    | CanBusEventRouter.INTEREST_VEHICLE_SPEED
                                    | CanBusEventRouter.INTEREST_ENERGY_TELEMETRY
                                    | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                            new int[] {MODE.stableId, TARGET.stableId},
                            handler,
                            this::onEvent);
        } else if (connected) {
            evaluate();
        }
    }

    private void onEvent(CanBusEvent event) {
        if (closed || subscription == null) {
            return;
        }
        switch (event.kind) {
            case CONNECTION:
                clearMeasurements();
                connected = true;
                hub.requestSpeedSnapshot();
                refreshCharge();
                return;
            case CONNECTION_LOST:
                commandGeneration++;
                connected = false;
                handler.removeCallbacks(poll);
                clearMeasurements();
                return;
            case VEHICLE_SPEED:
                speed = event.first;
                break;
            case ENERGY_TELEMETRY:
                if (event.telemetry.kind != EnergyTelemetrySample.SOC) {
                    return;
                }
                charge = event.telemetry.values[0];
                chargeTime = event.elapsedRealtime;
                break;
            case VEHICLE_STATE:
                if (event.first == TARGET.stableId) {
                    target = targetPercent(event.second);
                } else if (event.first == MODE.stableId) {
                    observedMode = event.second;
                    if (observedMode == requestedMode) {
                        requestedMode = -1;
                    }
                    if (observedMode == VehicleRestorePolicy.SOC_SREV
                            && requestedMode != VehicleRestorePolicy.SOC_EV) {
                        temporaryElectric = false;
                    }
                }
                break;
            default:
                return;
        }
        evaluate();
    }

    private void refreshCharge() {
        handler.removeCallbacks(poll);
        if (closed || !connected || subscription == null) {
            return;
        }
        refreshConfiguration();
        if (subscription == null || !connected) {
            return;
        }
        hub.requestEnergySnapshot();
        Map<OemVehicleStateTransport.StateKey, Integer> states =
                OemVehicleStateTransport.readVehicleStates(context, Arrays.asList(MODE, TARGET));
        target = states == null ? -1 : targetPercent(states.get(TARGET));
        observedMode = states == null ? -1 : states.get(MODE);
        evaluate();
        handler.postDelayed(poll, CHARGE_INTERVAL_MS);
    }

    private int targetPercent(int level) {
        return level >= 0 && level <= 11 ? 25 + level * 5 : -1;
    }

    private void evaluate() {
        if (!connected || subscription == null || closed) {
            return;
        }
        long age = SystemClock.elapsedRealtime() - chargeTime;
        float freshCharge =
                chargeTime >= 0 && age >= 0 && age <= MAX_CHARGE_AGE_MS ? charge : Float.NaN;
        int currentMode = requestedMode >= 0 ? requestedMode : observedMode;
        int desiredMode = policy.desiredMode(freshCharge, target, speed, currentMode);
        persistState();
        if (desiredMode == requestedMode || (requestedMode < 0 && desiredMode == observedMode)) {
            return;
        }
        requestMode(desiredMode);
    }

    private void requestMode(int mode) {
        final long generation = ++commandGeneration;
        requestedMode = mode;
        if (mode == VehicleRestorePolicy.SOC_EV) {
            // Record ownership before enqueueing so disabling can cancel a pending EV as well.
            temporaryElectric = true;
            persistState();
        }
        ApplyEngine.postWakeAction(
                "smart Save",
                () -> {
                    if (closed || generation != commandGeneration) {
                        return false;
                    }
                    Bundle current = readConfiguration();
                    if (!saveSelected(current)
                            || current.getInt("acc", -1) != 2
                            || (mode == VehicleRestorePolicy.SOC_EV
                                    && !current.getBoolean(SmartSaveSettings.ENABLED, false))) {
                        return false;
                    }
                    return CanSender.runGuardedSend(
                            () -> !closed && generation == commandGeneration,
                            () ->
                                    OemVehicleStateTransport.sendTemporaryEnergyMode(
                                                    context,
                                                    mode,
                                                    current.getLong("revision", -1),
                                                    current.getLong("cycle", -1))
                                            .accepted());
                },
                result ->
                        handler.post(
                                () -> {
                                    if (closed || generation != commandGeneration) {
                                        return;
                                    }
                                    if (result != ApplyEngine.WakeActionResult.SUCCESS) {
                                        requestedMode = -1;
                                        Log.w(TAG, "Temporary mode was not submitted: " + result);
                                    }
                                }));
    }

    private void persistState() {
        if (preferences.getBoolean("chargingLocked", false) == policy.isChargingLocked()
                && preferences.getBoolean("temporaryElectric", false) == temporaryElectric) {
            return;
        }
        preferences
                .edit()
                .putBoolean("chargingLocked", policy.isChargingLocked())
                .putBoolean("temporaryElectric", temporaryElectric)
                .apply();
    }

    private void clearMeasurements() {
        charge = Float.NaN;
        chargeTime = -1;
        target = -1;
        speed = -1;
        observedMode = -1;
        requestedMode = -1;
    }

    private void stopWatching() {
        handler.removeCallbacks(poll);
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        connected = false;
        clearMeasurements();
    }

    @Override
    public void close() {
        closed = true;
        commandGeneration++;
        context.unregisterReceiver(settingsChanged);
        handler.post(
                () -> {
                    handler.removeCallbacks(retryConfiguration);
                    stopWatching();
                    thread.quitSafely();
                });
    }
}
