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
import ru.big.town.common.SmartSaveSettings.Automation;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Energy control with bounded startup readiness and event-driven speed updates. */
final class EnergyModeAutomationController implements AutoCloseable {
    private static final String TAG = "EnergyModeAutomation";
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
    private final HandlerThread thread = new HandlerThread(TAG);
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
    private Automation automation;
    private EnergyModeSession session;
    private boolean connected;
    private boolean temporaryElectric;
    private int temporaryFuelTarget;
    private int confirmedFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
    private int requestedFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
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

    EnergyModeAutomationController(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("SmartSave", Context.MODE_PRIVATE);
        policy = new SmartSavePolicy(preferences.getBoolean("chargingLocked", false));
        temporaryElectric = preferences.getBoolean("temporaryElectric", false);
        temporaryFuelTarget =
                preferences.getInt("temporaryFuelTarget", EnergyModeSession.NO_FUEL_TARGET);
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

    private static Automation selectedAutomation(Bundle settings) {
        return settings == null
                ? null
                : Automation.selected(
                        settings.getString("energy"), settings.getBoolean("forcedEv", false));
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
        Automation nextAutomation = selectedAutomation(next);
        if (!sameConfiguration(configuration, next)) {
            commandGeneration++;
            requestedMode = -1;
            requestedFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
            requestSubmitted = false;
        }
        if (next == null) {
            stopWatching();
            configuration = null;
            automation = null;
            handler.postDelayed(retryConfiguration, CHARGE_INTERVAL_MS);
            return false;
        }
        boolean newCycle = preferences.getLong("energyCycle", -1) != next.getLong("cycle", -1);
        boolean newSelection =
                preferences.getLong("energySelectionRevision", 0)
                        != next.getLong("energySelectionRevision", 0);
        if (newCycle
                || nextAutomation != automation
                || (newSelection && nextAutomation == Automation.HYBRID)) {
            stopWatching();
        }
        configuration = next;
        automation = nextAutomation;
        if (automation != Automation.HYBRID || newSelection || newCycle) {
            temporaryFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
        }
        if (automation != Automation.SMART_SAVE) {
            temporaryElectric = false;
        } else if (newSelection) {
            policy.holdManualSave();
        }
        persistState();
        if (automation == null || configuration.getInt("acc", -1) != 2) {
            stopWatching();
            return false;
        }
        if (!configuration.getBoolean(automation.option, false)) {
            stopWatching();
            if ((temporaryElectric || temporaryFuelTarget != EnergyModeSession.NO_FUEL_TARGET)
                    && requestedMode != automation.selectedMode) {
                requestMode(
                        automation.selectedMode,
                        EnergyModeSession.NO_FUEL_TARGET,
                        "option disabled");
            }
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
        Automation selected = selectedAutomation(next);
        return selectedAutomation(previous) == selected
                && previous.getLong("revision", -1) == next.getLong("revision", -1)
                && previous.getLong("cycle", -1) == next.getLong("cycle", -1)
                && previous.getInt("acc", -1) == next.getInt("acc", -1)
                && (selected == null
                        || previous.getBoolean(selected.option, false)
                                == next.getBoolean(selected.option, false));
    }

    private void onEvent(CanBusEvent event) {
        if (closed || subscription == null) {
            return;
        }
        switch (event.kind) {
            case CONNECTION:
                clearMeasurements();
                connected = true;
                session =
                        automation == Automation.HYBRID
                                ? new HybridEngineSession(temporaryFuelTarget)
                                : new SmartSaveSession(policy, SystemClock.elapsedRealtime());
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
                    session.recordTarget(VehicleRestorePolicy.saveTargetPercent(event.second));
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
                                                    : VehicleRestorePolicy.saveTargetPercent(
                                                            states.get(TARGET)));
                                }
                                if (modeRevision == modeBeforeQuery
                                        && commandBeforeQuery == commandGeneration) {
                                    if (states != null && requestSubmitted) {
                                        requestedMode = -1;
                                        requestedFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
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
        if (observedMode == requestedMode && requestedFuelTarget < 0) {
            requestedMode = -1;
            requestSubmitted = false;
        }
        if (observedMode == VehicleRestorePolicy.SOC_SREV
                && requestedMode != VehicleRestorePolicy.SOC_EV) {
            temporaryElectric = false;
        }
    }

    private void evaluate() {
        handler.removeCallbacks(measurementDeadline);
        if (!connected || subscription == null || session == null || closed) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int currentMode = requestedMode >= 0 ? requestedMode : observedMode;
        int desiredMode = session.desiredMode(now, currentMode);
        int fuelTarget = session.fuelTargetPercent();
        if (desiredMode == VehicleRestorePolicy.SOC_REV) {
            confirmedFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
            if (observedMode == desiredMode && requestedMode < 0) {
                temporaryFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
            }
        }
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
        if (desiredMode == EnergyModeSession.WAIT_FOR_DATA) return;
        boolean needsTarget = fuelTarget >= 0 && fuelTarget != confirmedFuelTarget;
        if (desiredMode == requestedMode
                || (requestedMode < 0 && desiredMode == observedMode && !needsTarget)) {
            return;
        }
        requestMode(desiredMode, fuelTarget, session.reason());
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

    private void requestMode(int mode, int fuelTarget, String reason) {
        final long generation = ++commandGeneration;
        final Automation owner = automation;
        requestedMode = mode;
        requestedFuelTarget = fuelTarget;
        requestSubmitted = false;
        final long selectionRevision = configuration.getLong("revision", -1);
        final long cycle = configuration.getLong("cycle", -1);
        final long modeBeforeRequest = modeRevision;
        logDecision("request", mode, reason);
        if (mode == VehicleRestorePolicy.SOC_EV) {
            // Record ownership before enqueueing so disabling can cancel a pending EV as well.
            temporaryElectric = true;
            persistState();
        } else if (fuelTarget >= 0) {
            temporaryFuelTarget = fuelTarget;
            persistState();
        } else if (owner == Automation.HYBRID
                && temporaryFuelTarget != EnergyModeSession.NO_FUEL_TARGET) {
            temporaryFuelTarget = HybridEngineSession.RETURN_PENDING;
            persistState();
        }
        BooleanSupplier active = () -> !closed && generation == commandGeneration;
        BooleanSupplier allowed =
                () ->
                        active.getAsBoolean()
                                && commandAllowed(owner, mode, selectionRevision, cycle);
        BooleanSupplier send =
                fuelTarget >= 0
                        ? () ->
                                SaveChargeController.applyTemporary(
                                        context, fuelTarget, selectionRevision, cycle, active)
                        : () ->
                                OemVehicleStateTransport.sendTemporaryEnergyMode(
                                                context, mode, selectionRevision, cycle)
                                        .accepted();
        ApplyEngine.postWakeAction(
                "energy mode " + owner,
                () -> CanSender.runGuardedSend(allowed, send),
                result -> handler.post(() -> finishRequest(generation, modeBeforeRequest, result)));
    }

    private void finishRequest(
            long generation, long modeBeforeRequest, ApplyEngine.WakeActionResult result) {
        if (closed || generation != commandGeneration) return;
        if (result != ApplyEngine.WakeActionResult.SUCCESS) {
            // Retry an unconfirmed target on the next state poll. A speed reversal takes priority.
            if (requestedFuelTarget >= 0) {
                requestSubmitted = true;
            } else {
                requestedMode = -1;
                if (session == null) handler.postDelayed(retryConfiguration, CHARGE_INTERVAL_MS);
            }
            Log.w(TAG, "Energy mode request incomplete: " + result);
            return;
        }
        if (requestedFuelTarget < 0) {
            requestSubmitted = requestedMode >= 0;
            return;
        }
        confirmedFuelTarget = requestedFuelTarget;
        if (modeRevision == modeBeforeRequest) observedMode = requestedMode;
        modeRevision++;
        requestedMode = -1;
        requestedFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
        evaluate();
    }

    private boolean commandAllowed(Automation owner, int mode, long revision, long cycle) {
        Bundle current = readConfiguration();
        return selectedAutomation(current) == owner
                && current.getLong("revision", -1) == revision
                && current.getLong("cycle", -1) == cycle
                && current.getInt("acc", -1) == 2
                && owner.allows(mode, current.getBoolean(owner.option, false));
    }

    private void persistState() {
        long cycle = configuration.getLong("cycle", -1);
        long selection = configuration.getLong("energySelectionRevision", 0);
        if (preferences.getBoolean("chargingLocked", false) == policy.isChargingLocked()
                && preferences.getBoolean("temporaryElectric", false) == temporaryElectric
                && preferences.getInt("temporaryFuelTarget", EnergyModeSession.NO_FUEL_TARGET)
                        == temporaryFuelTarget
                && preferences.getLong("energyCycle", -1) == cycle
                && preferences.getLong("energySelectionRevision", 0) == selection
                && preferences.getBoolean("manualSaveHeld", false) == policy.isManualSaveHeld()) {
            return;
        }
        preferences
                .edit()
                .putLong("energyCycle", cycle)
                .putLong("energySelectionRevision", selection)
                .putInt("temporaryFuelTarget", temporaryFuelTarget)
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
        requestedFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
        confirmedFuelTarget = EnergyModeSession.NO_FUEL_TARGET;
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
