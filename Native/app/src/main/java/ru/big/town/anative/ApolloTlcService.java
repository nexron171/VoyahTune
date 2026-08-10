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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fail-closed, profile-pinned bridge for the OEM triggered lane-change switch.
 *
 * <p>Only {@code PLC_SWITCH} is written and only through the OEM ICanBusService TX58. The
 * subscription capability states are read through TX57 and are never changed here. There are no
 * write retries, wake restores, raw CAN frames or TX77 calls. A successful Binder transaction is
 * not treated as ECU acknowledgement: every write remains pending until the mandatory delayed
 * TX57 readback has completed, while callback state is published as the live ECU observation.</p>
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
    private static final String EXTRA_ENABLED = "enabled";
    private static final String EXTRA_ARGUMENT_VALID = "argumentValid";

    private static final String VEHICLE_SETTING_PACKAGE = "com.qinggan.app.vehiclesetting";
    private static final String ALLOWED_VEHICLE_SETTING_SHA256 =
            "72f1c549e5cbfe22f65169898710d63c84981adcbbf7490c959f84fdeff621e6";
    private static final String ALLOWED_CANBUS_SERVICE_SHA256 =
            "96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c";

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
    private static final int CALLBACK_VEHICLE_STATE_CHANGED = 36;

    private static final long BIND_RETRY_MS = 5_000L;
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

        queueSignalWrite(ApolloTlcPolicy.Signal.PLC_SWITCH, enabled, "PLC_SWITCH");
    }

    private void handleGlaSet(boolean enabled, boolean argumentValid) {
        handleTrafficLightSet(
                ApolloTlcPolicy.Signal.GLA_SWITCH, enabled, argumentValid, false);
    }

    private void handleGlaSoundSet(boolean enabled, boolean argumentValid) {
        handleTrafficLightSet(
                ApolloTlcPolicy.Signal.GLA_LIGHT_CHANGE_SWITCH,
                enabled, argumentValid, true);
    }

    private void handleTrafficLightSet(ApolloTlcPolicy.Signal signal, boolean enabled,
                                       boolean argumentValid, boolean requiresRecognition) {
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
        queueSignalWrite(signal, enabled, signal.name());
    }

    private void queueSignalWrite(ApolloTlcPolicy.Signal signal, boolean enabled,
                                  String logName) {
        int desiredState = ApolloTlcPolicy.requestedPlcState(enabled);
        int generation = ++writeGeneration;
        pending = true;
        pendingSignal = signal;
        pendingDesiredState = desiredState;
        lastError = ApolloTlcPolicy.ERROR_NONE;
        publishState();

        // Re-check immediately before TX58 in case package permissions changed while this command
        // was being validated. No Binder write is attempted on an ungranted permission.
        if (!hasWriteCanBusPermission()) {
            pending = false;
            pendingSignal = null;
            pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
            failWritePermissionClosed();
            publishState();
            return;
        }

        try {
            setVehicleState(signal, desiredState);
            Log.i(TAG, logName + " TX58 queued; desired=" + desiredState
                    + " generation=" + generation);
        } catch (RemoteException | RuntimeException e) {
            pending = false;
            pendingSignal = null;
            pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
            lastError = "tx58_failed";
            Log.e(TAG, logName + " TX58 failed; no retry", e);
            publishState();
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
        pending = false;
        if (!readOk) {
            if (runtimeProfileValid) {
                lastError = "delayed_readback_failed";
            }
        } else if (pendingSignal == null || cachedState(pendingSignal) != pendingDesiredState) {
            lastError = "readback_mismatch";
        } else {
            lastError = ApolloTlcPolicy.ERROR_NONE;
        }
        pendingSignal = null;
        pendingDesiredState = ApolloTlcPolicy.UNKNOWN;
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
            default:
                return ApolloTlcPolicy.UNKNOWN;
        }
    }

    private void onVehicleStateCallback(int ordinal, int id, int state) {
        if (destroyed || !runtimeProfileValid) return;
        ApolloTlcPolicy.Signal signal = ApolloTlcPolicy.Signal.fromId(id);
        if (signal == null) return;
        if (signal.ordinal != ordinal) {
            failRuntimeProfileClosed("profile_callback_mismatch");
            Log.e(TAG, "Pinned VehicleState mismatch for id=" + id
                    + ": expected ordinal=" + signal.ordinal + " actual=" + ordinal);
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
            GearReading reading = getGearStatus();
            if (!reading.valid) {
                failRuntimeProfileClosed("profile_gear_parcel_mismatch");
                Log.e(TAG, "Invalid GearState parcel ordinal=" + reading.ordinal
                        + " value=" + reading.value);
                return false;
            }
            gear = reading.value;
            Log.i(TAG, reason + ": gear=" + gear + " plc=" + plcSwitch
                    + " gla=" + glaSwitch + " glaSound=" + glaLightChangeSwitch);
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

    /** Presence marker + VehicleState.writeToParcel(ordinal, stable id). */
    private static void writeVehicleState(Parcel data, ApolloTlcPolicy.Signal signal) {
        data.writeInt(1);
        data.writeInt(signal.ordinal);
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

    /**
     * Re-hashes the APK backing a newly delivered Binder before the first descriptor/TX call.
     * The initial large VehicleSetting hash is intentionally not repeated.
     */
    private void verifyConnectedCanBus(IBinder candidate) {
        if (destroyed || !BuildConfig.IS_FULL || !hashCheckComplete
                || !vehicleSettingHashMatches) {
            rejectCanBusVerification("profile_vehicle_setting_hash_unavailable");
            return;
        }
        final int generation = beginCanBusVerification(candidate);
        profileExecutor.execute(() -> {
            ApkHashResult result = verifyInstalledApk(
                    CANBUS_PACKAGE, ALLOWED_CANBUS_SERVICE_SHA256, "profile_canbus");
            handler.post(() -> {
                if (!verificationResultCurrent(generation, candidate)) return;
                canBusVerificationPending = false;
                pendingCanBusBinder = null;
                canBusServiceHashMatches = result.matches;
                canBusServiceHashError = result.error;
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

    /** Hashes only the small CanBusService APK, then creates a fresh binding on success. */
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
            ApkHashResult result = verifyInstalledApk(
                    CANBUS_PACKAGE, ALLOWED_CANBUS_SERVICE_SHA256, "profile_canbus");
            handler.post(() -> {
                if (!verificationResultCurrent(generation, null)) return;
                canBusVerificationPending = false;
                pendingCanBusBinder = null;
                canBusServiceHashMatches = result.matches;
                canBusServiceHashError = result.error;
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
            ApkHashResult vehicleSetting = verifyInstalledApk(
                    VEHICLE_SETTING_PACKAGE, ALLOWED_VEHICLE_SETTING_SHA256,
                    "profile_vehicle_setting");
            ApkHashResult canBusService = verifyInstalledApk(
                    CANBUS_PACKAGE, ALLOWED_CANBUS_SERVICE_SHA256,
                    "profile_canbus");
            handler.post(() -> {
                if (destroyed) return;
                hashCheckComplete = true;
                vehicleSettingHashMatches = vehicleSetting.matches;
                canBusServiceHashMatches = canBusService.matches;
                vehicleSettingHashError = vehicleSetting.error;
                canBusServiceHashError = canBusService.error;
                if (BuildConfig.IS_FULL && !hasWriteCanBusPermission()) {
                    failWritePermissionClosed();
                } else if ((!vehicleSetting.matches || !canBusService.matches)
                        && BuildConfig.IS_FULL) {
                    // A full build installed over a previously supported firmware must fail closed.
                    masterForceDisabled = true;
                    writeMaster(false);
                } else if (vehicleSetting.matches && canBusService.matches) {
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

    private ApkHashResult verifyInstalledApk(String packageName, String expectedSha256,
                                             String errorPrefix) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            String digest = sha256(new File(info.sourceDir));
            if (expectedSha256.equals(digest)) {
                return new ApkHashResult(true, ApolloTlcPolicy.ERROR_NONE);
            }
            Log.e(TAG, packageName + " APK hash mismatch");
            return new ApkHashResult(false, errorPrefix + "_hash_mismatch");
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, packageName + " APK not found");
            return new ApkHashResult(false, errorPrefix + "_apk_not_found");
        } catch (Exception e) {
            Log.e(TAG, packageName + " APK hash failed", e);
            return new ApkHashResult(false, errorPrefix + "_hash_failed");
        }
    }

    private static final class ApkHashResult {
        final boolean matches;
        final String error;

        ApkHashResult(boolean matches, String error) {
            this.matches = matches;
            this.error = error;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
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
        update.putExtra(EXTRA_ERROR, reportedError(directTlcSupported));
        update.setPackage(RESTOREMODE_PACKAGE);
        sendBroadcast(update, BIND_PERMISSION);
    }
}
