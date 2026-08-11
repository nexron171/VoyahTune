package ru.big.town.anative;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import dalvik.system.PathClassLoader;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fail-closed, profile-pinned bridge for the OEM triggered lane-change switch.
 *
 * <p>User switches are written through the OEM ICanBusService TX58. Enabling traffic-light
 * recognition first emits one complete TX77 entitlement vector with only GLC and TLA enabled;
 * every unrelated Apollo capability is explicitly disabled. There are no write retries, wake
 * restores or raw CAN frames. A successful Binder transaction is not treated as ECU
 * acknowledgement: every user-switch write remains pending until mandatory delayed TX57
 * readback, while callback state is published as the live ECU observation.</p>
 */
public final class ApolloTlcService extends Service {
    private static final String TAG = "$$$ ApolloTlcService $$$";
    private static final String RESTOREMODE_PACKAGE = "ru.big.town.restoremode";
    private static final String BIND_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";

    public static final String ACTION_APOLLO_TLC_UPDATE =
            "ru.big.town.anative.APOLLO_TLC_UPDATE";
    public static final String ACTION_REQUEST_APOLLO_TLC_UPDATE =
            "ru.big.town.anative.REQUEST_APOLLO_TLC_UPDATE";

    public static final String EXTRA_CAN_CONNECTED = "canConnected";
    public static final String EXTRA_PROFILE_SUPPORTED = "profileSupported";
    public static final String EXTRA_DIRECT_TLC_MODE = "directTlcMode";
    public static final String EXTRA_MASTER_KNOWN = "masterKnown";
    public static final String EXTRA_MASTER_ENABLED = "masterEnabled";
    public static final String EXTRA_PENDING = "pending";
    public static final String EXTRA_GEAR = "gear";
    public static final String EXTRA_PLC_SWITCH = "plcSwitch";
    public static final String EXTRA_PLC_STATUS = "plcStatus";
    public static final String EXTRA_ANP_SWITCH = "anpSwitch";
    public static final String EXTRA_TLC_CAPABILITY = "tlcCapability";
    public static final String EXTRA_PLC_CAPABILITY_SA = "plcCapabilitySa";
    public static final String EXTRA_GLA_SWITCH = "glaSwitch";
    public static final String EXTRA_GLA_LIGHT_CHANGE_SWITCH = "glaLightChangeSwitch";
    public static final String EXTRA_TSR_SWITCH = "tsrSwitch";
    public static final String EXTRA_ERROR = "error";

    public static final String GLOBAL_MASTER_KEY = "open_voyah_apollo_master";
    public static final String GLOBAL_PROFILE_SUPPORTED_KEY =
            "open_voyah_apollo_profile_supported";
    public static final String GLOBAL_PROFILE_HEARTBEAT_KEY =
            "open_voyah_apollo_profile_heartbeat";

    private static final String ACTION_INTERNAL_QUERY =
            "ru.big.town.anative.internal.APOLLO_TLC_QUERY";
    private static final String ACTION_INTERNAL_SET =
            "ru.big.town.anative.internal.APOLLO_TLC_SET";
    private static final String ACTION_INTERNAL_MASTER_SET =
            "ru.big.town.anative.internal.APOLLO_MASTER_SET";
    private static final String ACTION_INTERNAL_GLA_SET =
            "ru.big.town.anative.internal.APOLLO_GLA_SET";
    private static final String ACTION_INTERNAL_GLA_SOUND_SET =
            "ru.big.town.anative.internal.APOLLO_GLA_SOUND_SET";
    private static final String ACTION_INTERNAL_TSR_SET =
            "ru.big.town.anative.internal.APOLLO_TSR_SET";
    private static final String EXTRA_ENABLED = "enabled";
    private static final String EXTRA_ARGUMENT_VALID = "argumentValid";

    private static final String CANBUS_DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    private static final String WRITE_CANBUS_PERMISSION =
            "com.qinggan.permission.WRITE_CANBUS";
    private static final String CANBUS_CALLBACK_DESCRIPTOR =
            "com.qinggan.canbus.ICanBusServiceCallback";
    private static final String CANBUS_ACTION = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";
    private static final int TX_GET_GEAR_STATUS = 6;
    private static final int TX_ADD_CALLBACK = 28;
    private static final int TX_REMOVE_CALLBACK = 29;
    private static final int TX_GET_VEHICLE_STATE = 57;
    private static final int TX_SET_VEHICLE_STATE = 58;
    private static final int TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE = 77;
    private static final int CALLBACK_VEHICLE_STATE_CHANGED = 36;

    private static final long BIND_RETRY_MS = 5_000L;
    private static final long ENTITLEMENT_SETTLE_MS = 1_000L;
    private static final long DELAYED_READBACK_MS = 3_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService profileExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ApolloTlcProfile");
        thread.setDaemon(true);
        return thread;
    });

    private IBinder canBusBinder;
    private boolean canBusBindingRequested;
    private boolean canBusConnected;
    private boolean callbackAdded;
    private volatile boolean destroyed;
    private boolean requestReceiverRegistered;
    private boolean canBusVerificationPending;
    private int canBusVerificationGeneration;
    private IBinder pendingCanBusBinder;

    private boolean hashCheckComplete;
    private boolean vehicleSettingHashMatches;
    private boolean canBusServiceHashMatches;
    private boolean runtimeProfileValid = true;
    private String vehicleSettingHashError = "profile_check_pending";
    private String canBusServiceHashError = "profile_check_pending";
    private final EnumMap<ApolloTlcPolicy.Signal, Integer> runtimeSignalOrdinals =
            new EnumMap<>(ApolloTlcPolicy.Signal.class);
    private String lastError = ApolloTlcPolicy.ERROR_NONE;
    /** Sticky until a Settings.Global master write succeeds; prevents false OFF confirmation. */
    private String masterPersistenceError = ApolloTlcPolicy.ERROR_NONE;

    private int gear = ApolloTlcPolicy.UNKNOWN;
    private int plcSwitch = ApolloTlcPolicy.UNKNOWN;
    private int plcStatus = ApolloTlcPolicy.UNKNOWN;
    private int anpSwitch = ApolloTlcPolicy.UNKNOWN;
    private int tlcCapability = ApolloTlcPolicy.UNKNOWN;
    private int plcCapabilitySa = ApolloTlcPolicy.UNKNOWN;
    private int glaSwitch = ApolloTlcPolicy.UNKNOWN;
    private int glaLightChangeSwitch = ApolloTlcPolicy.UNKNOWN;
    private int tsrSwitch = ApolloTlcPolicy.UNKNOWN;

    private boolean pending;
    private int pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
    private ApolloTlcPolicy.Signal pendingSignal;
    private int writeGeneration;
    /** In-memory fail-safe if a rejected/failed OFF cannot update Settings.Global. */
    private boolean masterForceDisabled;
    /** Prevents automatic retries if the full-only vendor permission is unexpectedly unavailable. */
    private boolean writePermissionFailureHandled;

    private final Runnable rebindRunnable =
            () -> revalidateCanBusAndBind("scheduled rebind");

    private final BroadcastReceiver requestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_REQUEST_APOLLO_TLC_UPDATE.equals(intent.getAction())) {
                handler.post(ApolloTlcService.this::handleQuery);
            }
        }
    };

    private final IBinder canBusCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (destroyed && code >= IBinder.FIRST_CALL_TRANSACTION
                    && code <= IBinder.LAST_CALL_TRANSACTION) {
                return true;
            }
            if (code == CALLBACK_VEHICLE_STATE_CHANGED) {
                try {
                    data.enforceInterface(CANBUS_CALLBACK_DESCRIPTOR);
                    int ordinal = ApolloTlcPolicy.UNKNOWN;
                    int id = ApolloTlcPolicy.UNKNOWN;
                    if (data.readInt() != 0) {
                        ordinal = data.readInt();
                        id = data.readInt();
                    }
                    int state = data.readInt();
                    final int callbackOrdinal = ordinal;
                    final int callbackId = id;
                    final int callbackState = state;
                    handler.post(() -> {
                        if (!destroyed) {
                            onVehicleStateCallback(
                                    callbackOrdinal, callbackId, callbackState);
                        }
                    });
                } catch (RuntimeException e) {
                    Log.e(TAG, "Malformed VehicleState callback", e);
                    handler.post(() -> {
                        if (destroyed) return;
                        failRuntimeProfileClosed("profile_callback_malformed");
                        publishState();
                    });
                }
                return true;
            }
            // The OEM callback is large and oneway. Quietly accept callbacks this bridge does not use.
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private final ServiceConnection canBusConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (destroyed) return;
            handler.removeCallbacks(rebindRunnable);
            canBusBindingRequested = true;
            verifyConnectedCanBus(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            invalidateCanBusIdentity("can_disconnected");
            scheduleCanBusRebind();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            restartCanBusBinding("binding_died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            restartCanBusBinding("null_binding");
        }
    };

    public static void requestQuery(Context context) {
        start(context, ACTION_INTERNAL_QUERY, false, true);
    }

    public static void requestTlcSet(Context context, boolean enabled, boolean argumentValid) {
        start(context, ACTION_INTERNAL_SET, enabled, argumentValid);
    }

    public static void requestMasterSet(Context context, boolean enabled, boolean argumentValid) {
        start(context, ACTION_INTERNAL_MASTER_SET, enabled, argumentValid);
    }

    public static void requestGlaSet(Context context, boolean enabled, boolean argumentValid) {
        start(context, ACTION_INTERNAL_GLA_SET, enabled, argumentValid);
    }

    public static void requestGlaSoundSet(Context context, boolean enabled,
                                          boolean argumentValid) {
        start(context, ACTION_INTERNAL_GLA_SOUND_SET, enabled, argumentValid);
    }

    public static void requestTsrSet(Context context, boolean enabled, boolean argumentValid) {
        start(context, ACTION_INTERNAL_TSR_SET, enabled, argumentValid);
    }

    public static void ensureStarted(Context context) {
        start(context, null, false, true);
    }

    private static void start(Context context, String action, boolean enabled,
                              boolean argumentValid) {
        Intent intent = new Intent(context, ApolloTlcService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_ENABLED, enabled);
        intent.putExtra(EXTRA_ARGUMENT_VALID, argumentValid);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start ApolloTlcService for " + action, e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        if (!BuildConfig.IS_FULL) {
            forceMasterOff("light startup");
        }
        try {
            registerReceiver(requestReceiver,
                    new IntentFilter(ACTION_REQUEST_APOLLO_TLC_UPDATE),
                    BIND_PERMISSION, null, RECEIVER_EXPORTED);
            requestReceiverRegistered = true;
        } catch (RuntimeException e) {
            lastError = "request_receiver_failed";
            Log.e(TAG, "Cannot register request receiver", e);
        }
        // Light is intentionally inert: no 378 MB hashing and no qg.canbus bind/transactions.
        // Full binds only from startProfileCheck after both installed APK hashes match.
        if (BuildConfig.IS_FULL) {
            if (hasWriteCanBusPermission()) {
                startProfileCheck();
            } else {
                failWritePermissionClosed();
            }
        }
        publishState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!BuildConfig.IS_FULL) {
            forceMasterOff("light command");
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_INTERNAL_SET.equals(action)) {
            boolean valid = intent.getBooleanExtra(EXTRA_ARGUMENT_VALID, false);
            handleTlcSet(intent.getBooleanExtra(EXTRA_ENABLED, false), valid);
        } else if (ACTION_INTERNAL_MASTER_SET.equals(action)) {
            boolean valid = intent.getBooleanExtra(EXTRA_ARGUMENT_VALID, false);
            handleMasterSet(intent.getBooleanExtra(EXTRA_ENABLED, false), valid);
        } else if (ACTION_INTERNAL_GLA_SET.equals(action)) {
            boolean valid = intent.getBooleanExtra(EXTRA_ARGUMENT_VALID, false);
            handleGlaSet(intent.getBooleanExtra(EXTRA_ENABLED, false), valid);
        } else if (ACTION_INTERNAL_GLA_SOUND_SET.equals(action)) {
            boolean valid = intent.getBooleanExtra(EXTRA_ARGUMENT_VALID, false);
            handleGlaSoundSet(intent.getBooleanExtra(EXTRA_ENABLED, false), valid);
        } else if (ACTION_INTERNAL_TSR_SET.equals(action)) {
            boolean valid = intent.getBooleanExtra(EXTRA_ARGUMENT_VALID, false);
            handleTsrSet(intent.getBooleanExtra(EXTRA_ENABLED, false), valid);
        } else {
            handleQuery();
        }
        return BuildConfig.IS_FULL ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (requestReceiverRegistered) {
            try {
                unregisterReceiver(requestReceiver);
            } catch (RuntimeException ignored) {
            }
            requestReceiverRegistered = false;
        }
        releaseCanBusBinding("destroy");
        profileExecutor.shutdownNow();
        super.onDestroy();
    }

    private void handleQuery() {
        if (!BuildConfig.IS_FULL) {
            forceMasterOff("light query");
            invalidateCanSnapshot();
            publishState();
            return;
        }
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            publishState();
            return;
        }
        lastError = ApolloTlcPolicy.ERROR_NONE;
        if (!isBinderProfilePinned()) {
            invalidateCanSnapshot();
            if (hashCheckComplete && vehicleSettingHashMatches
                    && !canBusVerificationPending) {
                revalidateCanBusAndBind("query");
            } else {
                publishState();
            }
            return;
        }
        if (!runtimeProfileValid) {
            invalidateCanSnapshot();
            publishState();
            return;
        }
        ensureCanBusBound();
        if (canBusConnected && callbackAdded && !refreshFromCan("query")) {
            if (runtimeProfileValid) {
                lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            }
        } else if (!canBusConnected || !callbackAdded) {
            invalidateCanSnapshot();
        }
        publishState();
    }

    private void handleMasterSet(boolean enabled, boolean argumentValid) {
        if (!argumentValid) {
            lastError = "invalid_argument";
            publishState();
            return;
        }
        if (!BuildConfig.IS_FULL) {
            forceMasterOff("light master command");
            lastError = ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
            publishState();
            return;
        }
        // This release is deliberately direct-only. It must never invoke the OEM subscription
        // manager that writes the unrelated 18-field Apollo entitlement bundle.
        masterForceDisabled = true;
        writeMaster(false);
        lastError = enabled
                ? ApolloTlcPolicy.ERROR_MASTER_NOT_USED_DIRECT
                : ApolloTlcPolicy.ERROR_NONE;
        publishState();
        return;
    }

    private void handleTlcSet(boolean enabled, boolean argumentValid) {
        if (!argumentValid) {
            lastError = "invalid_argument";
            publishState();
            return;
        }
        if (!BuildConfig.IS_FULL) {
            forceMasterOff("light TLC command");
            lastError = ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
            publishState();
            return;
        }
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!canBusConnected || !callbackAdded) {
            lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
            publishState();
            return;
        }

        // All policy inputs, especially gear, are synchronously re-read immediately before TX58.
        if (!refreshFromCan("prewrite")) {
            if (runtimeProfileValid) {
                lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            }
            publishState();
            return;
        }
        boolean directTlcMode = isDirectTlcSupported();
        ApolloTlcPolicy.Snapshot snapshot = snapshot();
        String blocked = ApolloTlcPolicy.writeBlockReason(
                true, directTlcMode, canBusConnected && callbackAdded,
                true, false, pending, gear, snapshot, enabled);
        if (!blocked.isEmpty()) {
            lastError = blocked;
            Log.w(TAG, "PLC_SWITCH write blocked: " + blocked);
            publishState();
            return;
        }

        queueSignalWrite(ApolloTlcPolicy.Signal.PLC_SWITCH,
                ApolloTlcPolicy.requestedPlcState(enabled), "PLC_SWITCH");
    }

    private void handleGlaSet(boolean enabled, boolean argumentValid) {
        handleIndependentSwitchSet(ApolloTlcPolicy.Signal.GLA_SWITCH,
                ApolloTlcPolicy.requestedPlcState(enabled), argumentValid,
                false, enabled);
    }

    private void handleGlaSoundSet(boolean enabled, boolean argumentValid) {
        handleIndependentSwitchSet(ApolloTlcPolicy.Signal.GLA_LIGHT_CHANGE_SWITCH,
                ApolloTlcPolicy.requestedPlcState(enabled), argumentValid,
                true, false);
    }

    private void handleTsrSet(boolean enabled, boolean argumentValid) {
        handleIndependentSwitchSet(ApolloTlcPolicy.Signal.TSR_SWITCH,
                ApolloTlcPolicy.requestedTsrState(enabled), argumentValid,
                false, false);
    }

    private void handleIndependentSwitchSet(ApolloTlcPolicy.Signal signal, int desiredState,
                                            boolean argumentValid,
                                            boolean requiresRecognition,
                                            boolean enableTrafficLightEntitlements) {
        if (!argumentValid) {
            lastError = "invalid_argument";
            publishState();
            return;
        }
        if (!BuildConfig.IS_FULL) {
            lastError = ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
            publishState();
            return;
        }
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!canBusConnected || !callbackAdded) {
            lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
            publishState();
            return;
        }
        if (!refreshFromCan("traffic light prewrite")) {
            if (runtimeProfileValid) lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            publishState();
            return;
        }
        String blocked = ApolloTlcPolicy.directSwitchBlockReason(
                true, isDirectTlcSupported(), canBusConnected && callbackAdded,
                pending, cachedState(signal));
        if (blocked.isEmpty() && requiresRecognition
                && glaSwitch != ApolloTlcPolicy.MODULE_ON) {
            blocked = "traffic_light_recognition_disabled";
        }
        if (!blocked.isEmpty()) {
            lastError = blocked;
            publishState();
            return;
        }
        if (enableTrafficLightEntitlements) {
            queueTrafficLightRecognitionEnable(desiredState);
        } else {
            queueSignalWrite(signal, desiredState, signal.name());
        }
    }

    /**
     * Sends one complete 18-key entitlement vector before enabling GLA_SWITCH. TX77 returning
     * zero means only that the OEM AsyncTask accepted the request, so TX58 is delayed long enough
     * for command 126 to leave the head unit. Neither stage is retried automatically.
     */
    private void queueTrafficLightRecognitionEnable(int desiredState) {
        int generation = beginPendingWrite(
                ApolloTlcPolicy.Signal.GLA_SWITCH, desiredState);
        if (!hasWriteCanBusPermission()) {
            clearPendingWrite(generation);
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!runtimeProfileValid || !canBusConnected || !callbackAdded
                || canBusBinder == null) {
            failPendingWrite(generation, ApolloTlcPolicy.ERROR_CAN_DISCONNECTED);
            return;
        }
        try {
            int result = setSelectiveTrafficLightEntitlements(true);
            if (result != 0) {
                failPendingWrite(generation, "traffic_light_entitlement_rejected");
                Log.e(TAG, "Selective traffic-light TX77 rejected; result=" + result);
                return;
            }
            Log.i(TAG, "Selective traffic-light TX77 queued: GLC=2 TLA=2 others=1"
                    + " generation=" + generation);
        } catch (RemoteException | RuntimeException e) {
            failPendingWrite(generation, "traffic_light_entitlement_tx_failed");
            Log.e(TAG, "Selective traffic-light TX77 failed; no retry", e);
            return;
        }
        handler.postDelayed(() -> continueTrafficLightRecognitionEnable(
                        generation, desiredState),
                ENTITLEMENT_SETTLE_MS);
    }

    private void continueTrafficLightRecognitionEnable(int generation, int desiredState) {
        if (destroyed || generation != writeGeneration || !pending
                || pendingSignal != ApolloTlcPolicy.Signal.GLA_SWITCH
                || pendingDesiredState != desiredState) {
            return;
        }
        if (!hasWriteCanBusPermission()) {
            clearPendingWrite(generation);
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!canBusConnected || !callbackAdded
                || !refreshFromCan("traffic-light entitlement settle")) {
            failPendingWrite(generation, ApolloTlcPolicy.ERROR_STATE_READ_FAILED);
            return;
        }
        if (!ApolloTlcPolicy.isModuleState(glaSwitch)) {
            failPendingWrite(generation, ApolloTlcPolicy.ERROR_INVALID_SWITCH_STATE);
            return;
        }
        transmitPendingSignal(generation, ApolloTlcPolicy.Signal.GLA_SWITCH,
                desiredState, "GLA_SWITCH");
    }

    private void queueSignalWrite(ApolloTlcPolicy.Signal signal, int desiredState,
                                  String logName) {
        int generation = beginPendingWrite(signal, desiredState);
        transmitPendingSignal(generation, signal, desiredState, logName);
    }

    private int beginPendingWrite(ApolloTlcPolicy.Signal signal, int desiredState) {
        int generation = ++writeGeneration;
        pending = true;
        pendingSignal = signal;
        pendingDesiredState = desiredState;
        lastError = ApolloTlcPolicy.ERROR_NONE;
        publishState();
        return generation;
    }

    private void transmitPendingSignal(int generation, ApolloTlcPolicy.Signal signal,
                                       int desiredState, String logName) {
        if (destroyed || generation != writeGeneration || !pending
                || pendingSignal != signal || pendingDesiredState != desiredState) {
            return;
        }
        // Re-check immediately before TX58 in case package permissions changed while this command
        // was being validated. No Binder write is attempted on an ungranted permission.
        if (!hasWriteCanBusPermission()) {
            clearPendingWrite(generation);
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!runtimeProfileValid || !canBusConnected || !callbackAdded
                || canBusBinder == null) {
            failPendingWrite(generation, ApolloTlcPolicy.ERROR_CAN_DISCONNECTED);
            return;
        }

        try {
            setVehicleState(signal, desiredState);
            Log.i(TAG, logName + " TX58 queued; desired=" + desiredState
                    + " generation=" + generation);
        } catch (RemoteException | RuntimeException e) {
            failPendingWrite(generation, "tx58_failed");
            Log.e(TAG, logName + " TX58 failed; no retry", e);
            return;
        }

        // Immediate observation is published but does not clear pending. Only the delayed readback
        // closes the command, so another UI tap cannot enqueue a second TX58 inside the ECU window.
        if (!refreshFromCan("immediate readback")) {
            if (runtimeProfileValid) {
                lastError = "immediate_readback_failed";
            }
        }
        publishState();
        handler.postDelayed(() -> finishDelayedReadback(generation), DELAYED_READBACK_MS);
    }

    private void failPendingWrite(int generation, String error) {
        if (generation != writeGeneration) return;
        clearPendingWrite(generation);
        lastError = error;
        publishState();
    }

    private void clearPendingWrite(int generation) {
        if (generation != writeGeneration) return;
        pending = false;
        pendingSignal = null;
        pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
    }

    private void finishDelayedReadback(int generation) {
        if (generation != writeGeneration || destroyed) return;
        if (!runtimeProfileValid) {
            pending = false;
            pendingSignal = null;
            pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
            publishState();
            return;
        }
        boolean readOk = canBusConnected && callbackAdded
                && refreshFromCan("delayed readback");
        boolean shouldDisableTrafficLightEntitlements = false;
        pending = false;
        if (!readOk) {
            if (runtimeProfileValid) {
                lastError = "delayed_readback_failed";
            }
        } else if (pendingSignal == null || cachedState(pendingSignal) != pendingDesiredState) {
            lastError = "readback_mismatch";
        } else {
            lastError = ApolloTlcPolicy.ERROR_NONE;
            shouldDisableTrafficLightEntitlements =
                    pendingSignal == ApolloTlcPolicy.Signal.GLA_SWITCH
                            && pendingDesiredState == ApolloTlcPolicy.MODULE_OFF;
        }
        pendingSignal = null;
        pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
        if (shouldDisableTrafficLightEntitlements) {
            disableTrafficLightEntitlements();
        } else {
            publishState();
        }
    }

    /**
     * GLA_SWITCH is already confirmed OFF before this method runs. The complete entitlement
     * vector then clears GLC (Green Light Control) and TLA (Traffic Light Assist), while keeping
     * every unrelated Apollo entitlement OFF as well. TX77 has no ECU acknowledgement and is not
     * retried automatically.
     */
    private void disableTrafficLightEntitlements() {
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            publishState();
            return;
        }
        if (!runtimeProfileValid || !canBusConnected || !callbackAdded
                || canBusBinder == null) {
            lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
            publishState();
            return;
        }
        try {
            int result = setSelectiveTrafficLightEntitlements(false);
            if (result != 0) {
                lastError = "traffic_light_entitlement_disable_rejected";
                Log.e(TAG, "Traffic-light entitlement disable TX77 rejected; result=" + result);
            } else {
                lastError = ApolloTlcPolicy.ERROR_NONE;
                Log.i(TAG, "Traffic-light entitlement disable TX77 queued:"
                        + " GLC=1 TLA=1 others=1");
            }
        } catch (RemoteException | RuntimeException e) {
            lastError = "traffic_light_entitlement_disable_tx_failed";
            Log.e(TAG, "Traffic-light entitlement disable TX77 failed; no retry", e);
        }
        publishState();
    }

    private ApolloTlcPolicy.Snapshot snapshot() {
        return new ApolloTlcPolicy.Snapshot(
                plcSwitch, plcStatus, anpSwitch, tlcCapability, plcCapabilitySa);
    }

    private int cachedState(ApolloTlcPolicy.Signal signal) {
        switch (signal) {
            case PLC_SWITCH:
                return plcSwitch;
            case GLA_SWITCH:
                return glaSwitch;
            case GLA_LIGHT_CHANGE_SWITCH:
                return glaLightChangeSwitch;
            case TSR_SWITCH:
                return tsrSwitch;
            default:
                return ApolloTlcPolicy.UNKNOWN;
        }
    }

    private void onVehicleStateCallback(int ordinal, int id, int state) {
        if (destroyed || !runtimeProfileValid) return;
        ApolloTlcPolicy.Signal signal = ApolloTlcPolicy.Signal.fromId(id);
        if (signal == null) return;
        Integer expectedOrdinal = runtimeSignalOrdinals.get(signal);
        if (expectedOrdinal == null || expectedOrdinal != ordinal) {
            failRuntimeProfileClosed("profile_callback_mismatch");
            Log.e(TAG, "Pinned VehicleState mismatch for id=" + id
                    + ": expected ordinal=" + expectedOrdinal + " actual=" + ordinal);
            publishState();
            return;
        }
        setCachedState(signal, state);
        publishState();
    }

    private boolean refreshFromCan(String reason) {
        if (!runtimeProfileValid || !hasWriteCanBusPermission()
                || !canBusConnected || canBusBinder == null) {
            invalidateCanSnapshot();
            return false;
        }
        try {
            plcSwitch = getVehicleState(ApolloTlcPolicy.Signal.PLC_SWITCH);
            glaSwitch = getVehicleState(ApolloTlcPolicy.Signal.GLA_SWITCH);
            glaLightChangeSwitch = getVehicleState(
                    ApolloTlcPolicy.Signal.GLA_LIGHT_CHANGE_SWITCH);
            tsrSwitch = getVehicleState(ApolloTlcPolicy.Signal.TSR_SWITCH);
            GearReading reading = getGearStatus();
            if (!reading.valid) {
                failRuntimeProfileClosed("profile_gear_parcel_mismatch");
                Log.e(TAG, "Invalid GearState parcel ordinal=" + reading.ordinal
                        + " value=" + reading.value);
                return false;
            }
            gear = reading.value;
            Log.i(TAG, reason + ": gear=" + gear + " plc=" + plcSwitch
                    + " gla=" + glaSwitch + " glaSound=" + glaLightChangeSwitch
                    + " tsr=" + tsrSwitch);
            return true;
        } catch (RemoteException | RuntimeException e) {
            invalidateCanSnapshot();
            lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            Log.e(TAG, reason + " failed", e);
            return false;
        }
    }

    /**
     * Permanently closes this service instance after a pinned protocol mismatch. The durable
     * entitlement is cleared once, without retry; a failed clear remains visible through
     * masterPersistenceError while masterForceDisabled still blocks every TX58 in memory.
     */
    private void failRuntimeProfileClosed(String error) {
        if (!runtimeProfileValid) return;
        runtimeProfileValid = false;
        invalidateCanSnapshot();
        lastError = error;
        masterForceDisabled = true;
        if (!writeMaster(false)) {
            Log.e(TAG, "Cannot clear Apollo master after permanent runtime mismatch: " + error);
        }
    }

    /** Closes entitlement and all Binder gates once; there is deliberately no automatic retry. */
    private void failWritePermissionClosed() {
        masterForceDisabled = true;
        lastError = ApolloTlcPolicy.ERROR_WRITE_PERMISSION_MISSING;
        invalidateCanSnapshot();
        if (writePermissionFailureHandled) return;
        writePermissionFailureHandled = true;
        if (!writeMaster(false)) {
            Log.e(TAG, "Cannot clear Apollo master after WRITE_CANBUS permission loss");
        }
    }

    private void invalidateCanSnapshot() {
        gear = ApolloTlcPolicy.UNKNOWN;
        plcSwitch = ApolloTlcPolicy.UNKNOWN;
        plcStatus = ApolloTlcPolicy.UNKNOWN;
        anpSwitch = ApolloTlcPolicy.UNKNOWN;
        tlcCapability = ApolloTlcPolicy.UNKNOWN;
        plcCapabilitySa = ApolloTlcPolicy.UNKNOWN;
        glaSwitch = ApolloTlcPolicy.UNKNOWN;
        glaLightChangeSwitch = ApolloTlcPolicy.UNKNOWN;
        tsrSwitch = ApolloTlcPolicy.UNKNOWN;
    }

    private void setCachedState(ApolloTlcPolicy.Signal signal, int state) {
        switch (signal) {
            case PLC_FUNCTION_STATUS:
                plcStatus = state;
                break;
            case PLC_SWITCH:
                plcSwitch = state;
                break;
            case ANP_SWITCH:
                anpSwitch = state;
                break;
            case GLA_SWITCH:
                glaSwitch = state;
                break;
            case GLA_LIGHT_CHANGE_SWITCH:
                glaLightChangeSwitch = state;
                break;
            case TSR_SWITCH:
                tsrSwitch = state;
                break;
            case TLC_FUNC_ENABLE:
                tlcCapability = state;
                break;
            case PLC_FUNC_ENABLE_SA:
                plcCapabilitySa = state;
                break;
        }
    }

    private int getVehicleState(ApolloTlcPolicy.Signal signal) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            writeVehicleState(data, signal);
            if (!canBusBinder.transact(TX_GET_VEHICLE_STATE, data, reply, 0)) {
                throw new RemoteException("TX57 rejected");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void setVehicleState(ApolloTlcPolicy.Signal signal, int state)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            writeVehicleState(data, signal);
            data.writeInt(state);
            if (!canBusBinder.transact(TX_SET_VEHICLE_STATE, data, reply, 0)) {
                throw new RemoteException("TX58 rejected");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * OEM TX77 argument order is air-condition bundle first, vehicle bundle second.
     * The vehicle bundle is intentionally complete because the vendor implementation starts its
     * shared entitlement bit buffer at zero before applying supplied keys.
     */
    private int setSelectiveTrafficLightEntitlements(boolean enabled) throws RemoteException {
        Bundle vehicleBundle = new Bundle();
        for (ApolloTlcPolicy.Entitlement entitlement
                : ApolloTlcPolicy.Entitlement.values()) {
            vehicleBundle.putInt(
                    entitlement.name(), entitlement.selectiveTrafficLightValue(enabled));
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeInt(0); // air-condition bundle is null
            data.writeInt(1); // vehicle bundle is present
            vehicleBundle.writeToParcel(data, 0);
            if (!canBusBinder.transact(
                    TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE, data, reply, 0)) {
                throw new RemoteException("TX77 rejected");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Presence marker + runtime-resolved VehicleState.writeToParcel(ordinal, stable id). */
    private void writeVehicleState(Parcel data, ApolloTlcPolicy.Signal signal) {
        Integer ordinal = runtimeSignalOrdinals.get(signal);
        if (ordinal == null) {
            throw new IllegalStateException("VehicleState schema not resolved for " + signal);
        }
        data.writeInt(1);
        data.writeInt(ordinal);
        data.writeInt(signal.id);
    }

    private GearReading getGearStatus() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            if (!canBusBinder.transact(TX_GET_GEAR_STATUS, data, reply, 0)) {
                throw new RemoteException("TX6 rejected");
            }
            reply.readException();
            if (reply.readInt() == 0) {
                return new GearReading(ApolloTlcPolicy.UNKNOWN,
                        ApolloTlcPolicy.UNKNOWN, false);
            }
            int ordinal = reply.readInt();
            int value = reply.readInt();
            boolean valid = (ordinal >= 0 && ordinal <= 4 && value == ordinal)
                    || (ordinal == 5 && value == -1);
            return new GearReading(ordinal, value, valid);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static final class GearReading {
        final int ordinal;
        final int value;
        final boolean valid;

        GearReading(int ordinal, int value, boolean valid) {
            this.ordinal = ordinal;
            this.value = value;
            this.valid = valid;
        }
    }

    /** Re-resolves the installed VehicleState table before the first Binder transaction. */
    private void verifyConnectedCanBus(IBinder candidate) {
        if (destroyed || !BuildConfig.IS_FULL || !hashCheckComplete
                || !vehicleSettingHashMatches) {
            rejectCanBusVerification("profile_vehicle_setting_hash_unavailable");
            return;
        }
        final int generation = beginCanBusVerification(candidate);
        profileExecutor.execute(() -> {
            VehicleStateSchemaResult result = resolveVehicleStateSchema();
            handler.post(() -> {
                if (!verificationResultCurrent(generation, candidate)) return;
                canBusVerificationPending = false;
                pendingCanBusBinder = null;
                applyVehicleStateSchema(result);
                if (!result.matches) {
                    rejectCanBusVerification(result.error);
                    return;
                }
                activateVerifiedCanBus(candidate);
            });
        });
    }

    private void activateVerifiedCanBus(IBinder service) {
        if (destroyed) return;
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            releaseCanBusBinding("WRITE_CANBUS permission missing");
            publishState();
            return;
        }
        try {
            if (!CANBUS_DESCRIPTOR.equals(service.getInterfaceDescriptor())) {
                failRuntimeProfileClosed("profile_binder_descriptor_mismatch");
                Log.e(TAG, "Unexpected CanBus Binder descriptor after APK verification");
                releaseCanBusBinding("descriptor mismatch");
                publishState();
                return;
            }
        } catch (RemoteException | RuntimeException e) {
            lastError = "can_descriptor_failed";
            Log.e(TAG, "Cannot verify CanBus Binder descriptor", e);
            restartCanBusBinding("descriptor failed");
            return;
        }
        canBusBinder = service;
        canBusConnected = true;
        callbackAdded = false;
        lastError = ApolloTlcPolicy.ERROR_NONE;
        Log.i(TAG, "CanBusService connected after current APK verification");
        addCanBusCallback();
        if (callbackAdded && !refreshFromCan("connect")) {
            if (runtimeProfileValid) {
                lastError = ApolloTlcPolicy.ERROR_STATE_READ_FAILED;
            }
        }
        publishState();
    }

    /** Re-resolves the runtime VehicleState schema, then creates a fresh binding on success. */
    private void revalidateCanBusAndBind(String reason) {
        if (destroyed || !BuildConfig.IS_FULL || !hashCheckComplete
                || !vehicleSettingHashMatches || !hasWriteCanBusPermission()
                || canBusVerificationPending) {
            return;
        }
        // This path is entered only after disconnect/death/rejection. Mark disconnected before
        // unbind so release cannot emit TX29 to an unverified or dead Binder identity.
        canBusBinder = null;
        canBusConnected = false;
        callbackAdded = false;
        releaseCanBusBinding(reason);

        final int generation = beginCanBusVerification(null);
        profileExecutor.execute(() -> {
            VehicleStateSchemaResult result = resolveVehicleStateSchema();
            handler.post(() -> {
                if (!verificationResultCurrent(generation, null)) return;
                canBusVerificationPending = false;
                pendingCanBusBinder = null;
                applyVehicleStateSchema(result);
                if (!result.matches) {
                    rejectCanBusVerification(result.error);
                    return;
                }
                Log.i(TAG, reason + ": CanBus APK revalidated; binding fresh service");
                ensureCanBusBound();
                publishState();
            });
        });
    }

    private int beginCanBusVerification(IBinder candidate) {
        int generation = ++canBusVerificationGeneration;
        canBusVerificationPending = true;
        pendingCanBusBinder = candidate;
        canBusServiceHashMatches = false;
        canBusServiceHashError = "profile_canbus_revalidation_pending";
        runtimeSignalOrdinals.clear();
        canBusBinder = null;
        canBusConnected = false;
        callbackAdded = false;
        invalidateCanSnapshot();
        publishState();
        return generation;
    }

    private boolean verificationResultCurrent(int generation, IBinder candidate) {
        return canBusVerificationPending
                && ApolloTlcPolicy.verificationResultCurrent(
                BuildConfig.IS_FULL, destroyed,
                canBusVerificationGeneration, generation,
                pendingCanBusBinder == candidate);
    }

    private void rejectCanBusVerification(String error) {
        ++canBusVerificationGeneration;
        canBusVerificationPending = false;
        pendingCanBusBinder = null;
        canBusServiceHashMatches = false;
        canBusServiceHashError = error;
        runtimeSignalOrdinals.clear();
        canBusBinder = null;
        canBusConnected = false;
        callbackAdded = false;
        invalidateCanSnapshot();
        masterForceDisabled = true;
        writeMaster(false);
        releaseCanBusBinding("CanBus APK verification failed");
        publishState();
    }

    private void ensureCanBusBound() {
        if (destroyed || canBusBindingRequested || !isBinderProfilePinned()) return;
        try {
            Intent intent = new Intent(CANBUS_ACTION);
            intent.setPackage(CANBUS_PACKAGE);
            canBusBindingRequested = bindService(
                    intent, canBusConnection, Context.BIND_AUTO_CREATE);
            if (!canBusBindingRequested) {
                lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
                scheduleCanBusRebind();
            }
        } catch (RuntimeException e) {
            canBusBindingRequested = false;
            lastError = ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
            Log.e(TAG, "CanBus bind failed", e);
            scheduleCanBusRebind();
        }
    }

    private void scheduleCanBusRebind() {
        if (destroyed || !BuildConfig.IS_FULL || !hashCheckComplete
                || !vehicleSettingHashMatches) return;
        handler.removeCallbacks(rebindRunnable);
        handler.postDelayed(rebindRunnable, BIND_RETRY_MS);
    }

    private void restartCanBusBinding(String reason) {
        invalidateCanBusIdentity(reason);
        releaseCanBusBinding(reason);
        scheduleCanBusRebind();
    }

    private void invalidateCanBusIdentity(String error) {
        ++canBusVerificationGeneration;
        canBusVerificationPending = false;
        pendingCanBusBinder = null;
        canBusServiceHashMatches = false;
        canBusServiceHashError = "profile_canbus_revalidation_pending";
        runtimeSignalOrdinals.clear();
        canBusBinder = null;
        canBusConnected = false;
        callbackAdded = false;
        invalidateCanSnapshot();
        if (pending) {
            ++writeGeneration;
            pending = false;
            pendingSignal = null;
            pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
        }
        lastError = error;
        publishState();
    }

    private void releaseCanBusBinding(String reason) {
        handler.removeCallbacks(rebindRunnable);
        if (canBusConnected && callbackAdded) removeCanBusCallback();
        if (canBusBindingRequested) {
            try {
                unbindService(canBusConnection);
            } catch (RuntimeException e) {
                Log.w(TAG, reason + ": unbind failed", e);
            }
        }
        canBusBindingRequested = false;
        canBusBinder = null;
        canBusConnected = false;
        callbackAdded = false;
    }

    private void addCanBusCallback() {
        if (!canBusConnected || canBusBinder == null || callbackAdded) return;
        if (!hasWriteCanBusPermission()) {
            failWritePermissionClosed();
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeStrongBinder(canBusCallback);
            if (!canBusBinder.transact(TX_ADD_CALLBACK, data, reply, 0)) {
                throw new RemoteException("TX28 rejected");
            }
            reply.readException();
            int result = reply.readInt();
            if (!ApolloTlcPolicy.callbackRegistrationAccepted(result)) {
                callbackAdded = false;
                invalidateCanSnapshot();
                lastError = "callback_unavailable";
                Log.e(TAG, "CanBus callback registration rejected (TX28 result="
                        + result + ", expected 1)");
                return;
            }
            callbackAdded = true;
        } catch (RemoteException | RuntimeException e) {
            callbackAdded = false;
            invalidateCanSnapshot();
            lastError = "callback_register_failed";
            Log.e(TAG, "Cannot add CanBus callback", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void removeCanBusCallback() {
        if (!canBusConnected || canBusBinder == null || !callbackAdded) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeStrongBinder(canBusCallback);
            canBusBinder.transact(TX_REMOVE_CALLBACK, data, reply, 0);
            reply.readException();
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Cannot remove CanBus callback", e);
        } finally {
            callbackAdded = false;
            reply.recycle();
            data.recycle();
        }
    }

    private void startProfileCheck() {
        profileExecutor.execute(() -> {
            VehicleStateSchemaResult schema = resolveVehicleStateSchema();
            handler.post(() -> {
                if (destroyed) return;
                hashCheckComplete = true;
                vehicleSettingHashMatches = true;
                vehicleSettingHashError = ApolloTlcPolicy.ERROR_NONE;
                applyVehicleStateSchema(schema);
                if (BuildConfig.IS_FULL && !hasWriteCanBusPermission()) {
                    failWritePermissionClosed();
                } else if (!schema.matches && BuildConfig.IS_FULL) {
                    masterForceDisabled = true;
                    writeMaster(false);
                } else if (schema.matches) {
                    // This release exposes only direct TLC. Keep the unrelated global Apollo
                    // entitlement switch off even when an older installation persisted it as ON.
                    forceMasterOff("direct TLC startup");
                }
                if (isBinderProfilePinned()) {
                    ensureCanBusBound();
                } else {
                    invalidateCanSnapshot();
                }
                publishState();
            });
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private VehicleStateSchemaResult resolveVehicleStateSchema() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(CANBUS_PACKAGE, 0);
            ClassLoader loader = new PathClassLoader(info.sourceDir, getClassLoader());
            Class enumClass = Class.forName(
                    "com.qinggan.canbus.VehicleState", true, loader);
            if (!enumClass.isEnum()) {
                return VehicleStateSchemaResult.failed("profile_canbus_schema_mismatch");
            }
            Method getValue = enumClass.getMethod("getValue");
            EnumMap<ApolloTlcPolicy.Signal, Integer> ordinals =
                    new EnumMap<>(ApolloTlcPolicy.Signal.class);
            for (ApolloTlcPolicy.Signal signal : ApolloTlcPolicy.Signal.values()) {
                Enum value = Enum.valueOf(enumClass, signal.name());
                Object stableId = getValue.invoke(value);
                if (!(stableId instanceof Integer) || ((Integer) stableId) != signal.id) {
                    Log.e(TAG, "VehicleState id mismatch for " + signal.name());
                    return VehicleStateSchemaResult.failed(
                            "profile_canbus_schema_mismatch");
                }
                ordinals.put(signal, value.ordinal());
            }
            for (ApolloTlcPolicy.Entitlement entitlement
                    : ApolloTlcPolicy.Entitlement.values()) {
                Enum value = Enum.valueOf(enumClass, entitlement.name());
                Object stableId = getValue.invoke(value);
                if (!(stableId instanceof Integer)
                        || ((Integer) stableId) != entitlement.id) {
                    Log.e(TAG, "VehicleState id mismatch for " + entitlement.name());
                    return VehicleStateSchemaResult.failed(
                            "profile_canbus_schema_mismatch");
                }
            }
            return new VehicleStateSchemaResult(
                    true, ApolloTlcPolicy.ERROR_NONE, ordinals);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "CanBusService APK not found");
            return VehicleStateSchemaResult.failed("profile_canbus_apk_not_found");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "Cannot resolve installed VehicleState schema", e);
            return VehicleStateSchemaResult.failed("profile_canbus_schema_unavailable");
        }
    }

    private void applyVehicleStateSchema(VehicleStateSchemaResult result) {
        canBusServiceHashMatches = result.matches;
        canBusServiceHashError = result.error;
        runtimeSignalOrdinals.clear();
        if (result.matches) runtimeSignalOrdinals.putAll(result.ordinals);
    }

    private static final class VehicleStateSchemaResult {
        final boolean matches;
        final String error;
        final EnumMap<ApolloTlcPolicy.Signal, Integer> ordinals;

        VehicleStateSchemaResult(boolean matches, String error,
                                 EnumMap<ApolloTlcPolicy.Signal, Integer> ordinals) {
            this.matches = matches;
            this.error = error;
            this.ordinals = ordinals;
        }

        static VehicleStateSchemaResult failed(String error) {
            return new VehicleStateSchemaResult(
                    false, error, new EnumMap<>(ApolloTlcPolicy.Signal.class));
        }
    }

    private boolean hookProfileSupported() {
        try {
            return Settings.Global.getInt(
                    getContentResolver(), GLOBAL_PROFILE_SUPPORTED_KEY, 0) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isProfileSupported() {
        return isProfileSupported(hookProfileSupported(), profileHeartbeatError());
    }

    /** Direct TLC needs only the allow-listed OEM Binder ABI; it does not use the Frida profile. */
    private boolean isDirectTlcSupported() {
        return BuildConfig.IS_FULL && hashCheckComplete
                && vehicleSettingHashMatches && canBusServiceHashMatches
                && runtimeProfileValid && hasWriteCanBusPermission();
    }

    private boolean isBinderProfilePinned() {
        boolean writePermissionGranted = BuildConfig.IS_FULL
                && hasWriteCanBusPermission();
        return ApolloTlcPolicy.binderProfilePinned(
                BuildConfig.IS_FULL, hashCheckComplete,
                vehicleSettingHashMatches, canBusServiceHashMatches,
                writePermissionGranted);
    }

    private boolean isProfileSupported(boolean hookSupported, String heartbeatError) {
        boolean writePermissionGranted = BuildConfig.IS_FULL
                && hasWriteCanBusPermission();
        return ApolloTlcPolicy.profileSupported(
                BuildConfig.IS_FULL,
                hashCheckComplete && vehicleSettingHashMatches,
                hashCheckComplete && canBusServiceHashMatches,
                hookSupported, heartbeatError.isEmpty(), runtimeProfileValid,
                writePermissionGranted);
    }

    private boolean hasWriteCanBusPermission() {
        return checkSelfPermission(WRITE_CANBUS_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String profileHeartbeatError() {
        final long heartbeat;
        try {
            heartbeat = Settings.Global.getLong(
                    getContentResolver(), GLOBAL_PROFILE_HEARTBEAT_KEY, Long.MIN_VALUE);
        } catch (RuntimeException e) {
            return "profile_heartbeat_read_failed";
        }
        if (heartbeat <= 0L) {
            return "profile_heartbeat_missing";
        }
        long age = SystemClock.elapsedRealtime() - heartbeat;
        if (age < 0L) return "profile_heartbeat_future";
        if (age > ApolloTlcPolicy.PROFILE_HEARTBEAT_MAX_AGE_MS) {
            return "profile_heartbeat_stale";
        }
        return ApolloTlcPolicy.ERROR_NONE;
    }

    private static final class MasterSetting {
        final boolean known;
        final boolean enabled;

        MasterSetting(boolean known, boolean enabled) {
            this.known = known;
            this.enabled = enabled;
        }
    }

    private MasterSetting readPersistedMaster() {
        try {
            boolean enabled = Settings.Global.getInt(
                    getContentResolver(), GLOBAL_MASTER_KEY, 0) == 1;
            if ("master_read_failed".equals(masterPersistenceError)) {
                masterPersistenceError = ApolloTlcPolicy.ERROR_NONE;
            }
            return new MasterSetting(true, enabled);
        } catch (RuntimeException e) {
            masterPersistenceError = "master_read_failed";
            Log.e(TAG, "Cannot read Apollo master", e);
            return new MasterSetting(false, false);
        }
    }

    /** Internal TX58 authorization; deliberately stricter than the persisted value shown to UI. */
    private boolean isMasterAuthorized(boolean profileSupported) {
        MasterSetting master = readPersistedMaster();
        boolean writePermissionGranted = BuildConfig.IS_FULL
                && hasWriteCanBusPermission();
        return ApolloTlcPolicy.effectiveMaster(
                BuildConfig.IS_FULL, profileSupported,
                master.known, master.enabled, masterForceDisabled,
                writePermissionGranted);
    }

    private boolean writeMaster(boolean enabled) {
        try {
            boolean ok = Settings.Global.putInt(
                    getContentResolver(), GLOBAL_MASTER_KEY, enabled ? 1 : 0);
            if (ok) {
                masterPersistenceError = ApolloTlcPolicy.ERROR_NONE;
            } else {
                masterPersistenceError = "master_write_failed";
                lastError = "master_write_failed";
            }
            return ok;
        } catch (RuntimeException e) {
            masterPersistenceError = "master_write_failed";
            lastError = "master_write_failed";
            Log.e(TAG, "Cannot write Apollo master", e);
            return false;
        }
    }

    private void forceMasterOff(String reason) {
        masterForceDisabled = true;
        if (writeMaster(false)) {
            Log.w(TAG, reason + ": light/fail-safe forces " + GLOBAL_MASTER_KEY + "=0");
        }
    }

    private String reportedError(boolean directTlcSupported) {
        if (!masterPersistenceError.isEmpty()) return masterPersistenceError;
        if (!BuildConfig.IS_FULL) return ApolloTlcPolicy.ERROR_UNSUPPORTED_LIGHT;
        if (!hasWriteCanBusPermission()) {
            return ApolloTlcPolicy.ERROR_WRITE_PERMISSION_MISSING;
        }
        if (!hashCheckComplete) return "profile_check_pending";
        if (!vehicleSettingHashMatches) return vehicleSettingHashError;
        if (!canBusServiceHashMatches) return canBusServiceHashError;
        if (!runtimeProfileValid) {
            return lastError.isEmpty() ? "profile_runtime_mismatch" : lastError;
        }
        if (!directTlcSupported) return ApolloTlcPolicy.ERROR_PROFILE_UNSUPPORTED;
        if (!canBusConnected) return ApolloTlcPolicy.ERROR_CAN_DISCONNECTED;
        if (!callbackAdded) return "callback_unavailable";
        if (!lastError.isEmpty()) return lastError;
        return ApolloTlcPolicy.operationalSnapshotError(snapshot());
    }

    private void publishState() {
        if (destroyed) return;
        boolean directTlcSupported = isDirectTlcSupported();
        MasterSetting master = readPersistedMaster();
        Intent update = new Intent(ACTION_APOLLO_TLC_UPDATE);
        update.putExtra(EXTRA_CAN_CONNECTED, canBusConnected);
        update.putExtra(EXTRA_PROFILE_SUPPORTED, directTlcSupported);
        update.putExtra(EXTRA_DIRECT_TLC_MODE, directTlcSupported);
        // masterEnabled is meaningful only when masterKnown is true. Never turn a failed
        // Settings.Global read into a known OFF state; the UI keeps emergency OFF available.
        // TX58 authorization remains independently fail-closed in isMasterAuthorized().
        update.putExtra(EXTRA_MASTER_KNOWN, master.known);
        update.putExtra(EXTRA_MASTER_ENABLED,
                ApolloTlcPolicy.reportedMasterEnabled(master.known, master.enabled));
        update.putExtra(EXTRA_PENDING, pending);
        update.putExtra(EXTRA_GEAR, gear);
        update.putExtra(EXTRA_PLC_SWITCH, plcSwitch);
        update.putExtra(EXTRA_PLC_STATUS, plcStatus);
        update.putExtra(EXTRA_ANP_SWITCH, anpSwitch);
        update.putExtra(EXTRA_TLC_CAPABILITY, tlcCapability);
        update.putExtra(EXTRA_PLC_CAPABILITY_SA, plcCapabilitySa);
        update.putExtra(EXTRA_GLA_SWITCH, glaSwitch);
        update.putExtra(EXTRA_GLA_LIGHT_CHANGE_SWITCH, glaLightChangeSwitch);
        update.putExtra(EXTRA_TSR_SWITCH, tsrSwitch);
        update.putExtra(EXTRA_ERROR, reportedError(directTlcSupported));
        update.setPackage(RESTOREMODE_PACKAGE);
        sendBroadcast(update, BIND_PERMISSION);
    }
}
