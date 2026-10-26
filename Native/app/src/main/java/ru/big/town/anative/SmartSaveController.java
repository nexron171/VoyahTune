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
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Energy control with bounded startup readiness and event-driven speed updates. */
final class SmartSaveController implements AutoCloseable {
    private static final String TAG = "SmartSave";
    private static final long CHARGE_INTERVAL_MS = 60_000;
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
    private final ExecutorService stateQueries = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refreshCharge;
    private final Runnable retryConfiguration = this::refreshConfiguration;
    private final Runnable measurementDeadline = this::evaluate;
    private final BroadcastReceiver settingsChanged =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refreshConfiguration();
                }
            };

    private CanBusEventHub.Subscription subscription;
    private Bundle configuration;
    private SmartSaveSession session;
    private boolean connected;
    private boolean temporaryElectric;
    private boolean stateQueryPending;
    private boolean requestSubmitted;
    private long measurementGeneration;
    private long targetRevision;
    private long modeRevision;
    private String lastDecision;
    private int observedMode = -1;
    private int requestedMode = -1;
    private volatile long commandGeneration;
    private volatile boolean closed;

    SmartSaveController(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("SmartSave", Context.MODE_PRIVATE);
        policy = new SmartSavePolicy(preferences.getBoolean("chargingLocked", false));
        temporaryElectric = preferences.getBoolean("temporaryElectric", false);
        if (preferences.getBoolean("manualSaveHeld", false)) {
            policy.holdManualSave();
        }
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
            stateQueries.shutdownNow();
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
        refreshConfiguration(true);
    }

    private boolean refreshConfiguration(boolean evaluateNow) {
        handler.removeCallbacks(retryConfiguration);
        if (closed) {
            return false;
        }
        Bundle next = readConfiguration();
        boolean newCycle =
                configuration != null
                        && next != null
                        && configuration.getLong("cycle", -1) != next.getLong("cycle", -1);
        if (!sameConfiguration(configuration, next)) {
            commandGeneration++;
            requestedMode = -1;
            requestSubmitted = false;
        }
        configuration = next;
        if (newCycle) {
            stopWatching();
        }
        if (configuration != null) {
            long selection = configuration.getLong("energySelectionRevision", 0);
            long previousSelection = preferences.getLong("energySelectionRevision", 0);
            if (selection != previousSelection) {
                if (saveSelected(configuration)) {
                    policy.holdManualSave();
                }
                preferences
                        .edit()
                        .putLong("energySelectionRevision", selection)
                        .putBoolean("manualSaveHeld", policy.isManualSaveHeld())
                        .apply();
            }
        }
        if (!saveSelected(configuration)) {
            stopWatching();
            if (configuration != null) {
                temporaryElectric = false;
                persistState();
            } else {
                handler.postDelayed(retryConfiguration, CHARGE_INTERVAL_MS);
            }
            return false;
        }
        if (!configuration.getBoolean(SmartSaveSettings.ENABLED, false)) {
            stopWatching();
            persistState();
            if (temporaryElectric
                    && configuration.getInt("acc", -1) == 2
                    && requestedMode != VehicleRestorePolicy.SOC_SREV) {
                requestMode(VehicleRestorePolicy.SOC_SREV, "option disabled");
            }
            return false;
        }
        if (configuration.getInt("acc", -1) != 2) {
            stopWatching();
            return false;
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
        } else if (connected && evaluateNow) {
            evaluate();
        }
        return connected;
    }

    private static boolean sameConfiguration(Bundle previous, Bundle next) {
        if (previous == null || next == null) {
            return previous == next;
        }
        return previous.getLong("revision", -1) == next.getLong("revision", -1)
                && previous.getLong("cycle", -1) == next.getLong("cycle", -1)
                && previous.getInt("acc", -1) == next.getInt("acc", -1)
                && previous.getBoolean(SmartSaveSettings.ENABLED, false)
                        == next.getBoolean(SmartSaveSettings.ENABLED, false)
                && previous.getBoolean("forcedEv", false) == next.getBoolean("forcedEv", false)
                && Objects.equals(previous.getString("energy"), next.getString("energy"));
    }

    private void onEvent(CanBusEvent event) {
        if (closed || subscription == null) {
            return;
        }
        switch (event.kind) {
            case CONNECTION:
                clearMeasurements();
                connected = true;
                session = new SmartSaveSession(policy, SystemClock.elapsedRealtime());
                hub.requestSpeedSnapshot();
                refreshCharge();
                return;
            case CONNECTION_LOST:
                connected = false;
                handler.removeCallbacks(poll);
                clearMeasurements();
                return;
            case VEHICLE_SPEED:
                if (session == null) return;
                session.recordSpeed(event.first);
                break;
            case ENERGY_TELEMETRY:
                if (session == null || event.telemetry.kind != EnergyTelemetrySample.SOC) {
                    return;
                }
                session.recordCharge(event.telemetry.values[0], event.elapsedRealtime);
                break;
            case VEHICLE_STATE:
                if (session == null) return;
                if (event.first == TARGET.stableId) {
                    targetRevision++;
                    session.recordTarget(targetPercent(event.second));
                } else if (event.first == MODE.stableId) {
                    modeRevision++;
                    observeMode(event.second);
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
        if (!refreshConfiguration(false)) {
            return;
        }
        hub.requestEnergySnapshot();
        requestVehicleStates();
        evaluate();
        handler.postDelayed(poll, CHARGE_INTERVAL_MS);
    }

    private void requestVehicleStates() {
        if (stateQueryPending || session == null || !connected || closed) return;
        stateQueryPending = true;
        long generation = measurementGeneration;
        long targetBeforeQuery = targetRevision;
        long modeBeforeQuery = modeRevision;
        long commandBeforeQuery = commandGeneration;
        stateQueries.execute(
                () -> {
                    Map<OemVehicleStateTransport.StateKey, Integer> states = readVehicleStates();
                    handler.post(
                            () -> {
                                stateQueryPending = false;
                                if (closed || !connected || session == null) return;
                                if (generation != measurementGeneration) {
                                    requestVehicleStates();
                                    return;
                                }
                                // Live callbacks supersede a getter that was already in progress.
                                if (targetRevision == targetBeforeQuery) {
                                    session.recordTarget(
                                            states == null
                                                    ? -1
                                                    : targetPercent(states.get(TARGET)));
                                }
                                if (modeRevision == modeBeforeQuery) {
                                    if (states != null
                                            && requestSubmitted
                                            && commandBeforeQuery == commandGeneration) {
                                        requestedMode = -1;
                                        requestSubmitted = false;
                                    }
                                    observeMode(states == null ? -1 : states.get(MODE));
                                }
                                evaluate();
                            });
                });
    }

    private Map<OemVehicleStateTransport.StateKey, Integer> readVehicleStates() {
        try {
            return OemVehicleStateTransport.readVehicleStates(context, Arrays.asList(MODE, TARGET));
        } catch (RuntimeException error) {
            Log.w(TAG, "Energy state snapshot unavailable", error);
            return null;
        }
    }

    private void observeMode(int mode) {
        if (observedMode != mode) {
            Log.i(TAG, "Observed energy mode=" + mode);
        }
        observedMode = mode;
        if (observedMode == requestedMode) {
            requestedMode = -1;
            requestSubmitted = false;
        }
        if (observedMode == VehicleRestorePolicy.SOC_SREV
                && requestedMode != VehicleRestorePolicy.SOC_EV) {
            temporaryElectric = false;
        }
    }

    private int targetPercent(int level) {
        return level >= 0 && level <= 11 ? 25 + level * 5 : -1;
    }

    private void evaluate() {
        handler.removeCallbacks(measurementDeadline);
        if (!connected || subscription == null || session == null || closed) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int currentMode = requestedMode >= 0 ? requestedMode : observedMode;
        int desiredMode = session.desiredMode(now, currentMode);
        persistState();
        String decision = desiredMode + ":" + session.reason();
        if (!decision.equals(lastDecision)) {
            lastDecision = decision;
            logDecision("decision", desiredMode, session.reason());
        }
        long deadline = session.nextEvaluationAt(now);
        if (deadline >= 0) {
            handler.postDelayed(measurementDeadline, Math.max(1, deadline - now));
        }
        if (desiredMode == SmartSaveSession.WAIT_FOR_DATA) return;
        if (desiredMode == requestedMode || (requestedMode < 0 && desiredMode == observedMode)) {
            return;
        }
        requestMode(desiredMode, session.reason());
    }

    private void logDecision(String action, int mode, String reason) {
        String measurements =
                session == null
                        ? "no active measurements"
                        : session.measurements(SystemClock.elapsedRealtime());
        Log.i(
                TAG,
                action
                        + " mode="
                        + mode
                        + " reason="
                        + reason
                        + " cycle="
                        + configuration.getLong("cycle", -1)
                        + " revision="
                        + configuration.getLong("revision", -1)
                        + " selection="
                        + configuration.getLong("energySelectionRevision", 0)
                        + " "
                        + measurements
                        + " chargeLock="
                        + policy.isChargingLocked()
                        + " manualHold="
                        + policy.isManualSaveHeld()
                        + " observed="
                        + observedMode);
    }

    private void requestMode(int mode, String reason) {
        final long generation = ++commandGeneration;
        requestedMode = mode;
        requestSubmitted = false;
        final long selectionRevision = configuration.getLong("revision", -1);
        final long cycle = configuration.getLong("cycle", -1);
        logDecision("request", mode, reason);
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
                            || current.getLong("revision", -1) != selectionRevision
                            || current.getLong("cycle", -1) != cycle
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
                                    } else if (requestedMode == mode) {
                                        requestSubmitted = true;
                                    }
                                }));
    }

    private void persistState() {
        if (preferences.getBoolean("chargingLocked", false) == policy.isChargingLocked()
                && preferences.getBoolean("temporaryElectric", false) == temporaryElectric
                && preferences.getBoolean("manualSaveHeld", false) == policy.isManualSaveHeld()) {
            return;
        }
        preferences
                .edit()
                .putBoolean("manualSaveHeld", policy.isManualSaveHeld())
                .putBoolean("chargingLocked", policy.isChargingLocked())
                .putBoolean("temporaryElectric", temporaryElectric)
                .apply();
    }

    private void clearMeasurements() {
        commandGeneration++;
        measurementGeneration++;
        handler.removeCallbacks(measurementDeadline);
        session = null;
        lastDecision = null;
        observedMode = -1;
        requestedMode = -1;
        requestSubmitted = false;
    }

    private void stopWatching() {
        handler.removeCallbacks(poll);
        if (subscription == null && !connected && session == null) return;
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
                    stateQueries.shutdownNow();
                    thread.quitSafely();
                });
    }
}
