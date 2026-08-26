package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dalvik.system.PathClassLoader;

/**
 * Event-demanded access to the stock Android 11 {@code ICanBusService} VehicleState API.
 *
 * <p>The transport deliberately does not own a retry timer. A caller may make another attempt as
 * part of its already bounded apply cycle, but a failed bind never creates an independent forever
 * loop. TX58 and TX77 share one Binder and one transaction lock so a compound OEM operation cannot
 * be interleaved with another VehicleState request from VoyahTune.</p>
 */
final class OemVehicleStateTransport {
    private static final String TAG = "$$$ OemVehicleState $$$";

    private static final String CANBUS_DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    private static final String CANBUS_ACTION = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";
    private static final String WRITE_CANBUS_PERMISSION = "com.qinggan.permission.WRITE_CANBUS";
    private static final int TX_GEAR_STATUS = 6;
    private static final int TX_GET_VEHICLE_STATE = 57;
    private static final int TX_SET_VEHICLE_STATE = 58;
    private static final int TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE = 77;
    private static final long BIND_WAIT_MS = 1_500L;
    private static final long STALE_BIND_MS = 5_000L;

    enum Result {
        /** The OEM service accepted the request; TX58/TX77 do not prove physical CAN completion. */
        ACCEPTED_UNCONFIRMED,
        TRANSIENT_FAILURE;

        boolean accepted() {
            return this == ACCEPTED_UNCONFIRMED;
        }
    }

    /** Name and stable value from the installed {@code com.qinggan.canbus.VehicleState} enum. */
    static final class StateKey {
        final String name;
        final int stableId;

        StateKey(String name, int stableId) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("VehicleState name is empty");
            }
            this.name = name;
            this.stableId = stableId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StateKey)) return false;
            StateKey that = (StateKey) other;
            return stableId == that.stableId && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + stableId;
        }

        @Override
        public String toString() {
            return name + "(" + stableId + ")";
        }
    }

    static final class StateValue {
        final StateKey key;
        final int value;

        StateValue(StateKey key, int value) {
            this.key = Objects.requireNonNull(key, "key");
            this.value = value;
        }
    }

    static final class GearStatus {
        final int ordinal;
        final int value;

        GearStatus(int ordinal, int value) {
            this.ordinal = ordinal;
            this.value = value;
        }
    }

    interface Session {
        GearStatus readGearStatus();

        Integer readVehicleState(StateKey key);

        Result sendVehicleState(StateValue state, String label);

        Result sendBundle(Map<StateKey, Integer> values, String label);
    }

    interface SessionOperation<T> {
        T run(Session session);
    }

    private static final OemVehicleStateTransport INSTANCE =
            new OemVehicleStateTransport();

    private final Object connectionLock = new Object();
    private final Object schemaLock = new Object();
    private final Object transactionLock = new Object();
    private final Map<StateKey, Integer> resolvedOrdinals = new HashMap<>();

    private Context appContext;
    private IBinder canBusBinder;
    private DemandConnection activeConnection;
    private boolean connectionRegistered;
    private boolean bindingInProgress;
    private long bindingGeneration;
    private long bindingStartedElapsed;
    private Class<? extends Enum> vehicleStateClass;
    private Method vehicleStateGetValue;

    private final class DemandConnection implements ServiceConnection {
        final long generation;

        DemandConnection(long generation) {
            this.generation = generation;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                String descriptor = service.getInterfaceDescriptor();
                if (!CANBUS_DESCRIPTOR.equals(descriptor)) {
                    Log.e(TAG, "Unexpected Binder descriptor: " + descriptor);
                    dropBinding(this, service);
                    return;
                }
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "Cannot verify CanBus Binder descriptor", e);
                dropBinding(this, service);
                return;
            }
            synchronized (connectionLock) {
                if (activeConnection != this) return;
                canBusBinder = service;
                bindingInProgress = false;
                connectionLock.notifyAll();
            }
            Log.i(TAG, "CanBusService connected for VehicleState demand gen=" + generation);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (connectionLock) {
                if (activeConnection != this) return;
                canBusBinder = null;
                bindingInProgress = true; // Android will reconnect the still-registered binding.
                bindingStartedElapsed = SystemClock.elapsedRealtime();
                connectionLock.notifyAll();
            }
            Log.w(TAG, "CanBusService disconnected; no independent rebind scheduled");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "CanBusService binding died");
            dropBinding(this, null);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.e(TAG, "CanBusService returned a null binding");
            dropBinding(this, null);
        }
    }

    private OemVehicleStateTransport() {}

    static Result sendVehicleState(Context context, String name, int stableId, int value,
                                   String label) {
        return sendVehicleState(context, new StateKey(name, stableId), value, label);
    }

    static Result sendVehicleState(Context context, StateKey key, int value, String label) {
        return INSTANCE.sendSingleInternal(context, new StateValue(key, value), label);
    }

    /**
     * Runs a short OEM read/write operation against one verified Binder while holding the same
     * transaction lock used by TX58/TX77. A null result means that the schema or Binder was not
     * available before the operation started.
     */
    static <T> T withSession(Context context, Collection<StateKey> keys,
                             SessionOperation<T> operation) {
        return INSTANCE.withSessionInternal(
                context, Objects.requireNonNull(keys, "VehicleState keys"),
                Objects.requireNonNull(operation, "session operation"));
    }

    static Result sendBundle(Context context, Map<StateKey, Integer> values, String label) {
        return INSTANCE.sendBundleInternal(context, immutableCopy(values), label);
    }

    /** Reads a narrow immutable TX57 snapshot once; a partial result is never published. */
    static Map<StateKey, Integer> readVehicleStates(
            Context context, Collection<StateKey> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("VehicleState snapshot is empty");
        }
        LinkedHashMap<StateKey, Integer> requested = new LinkedHashMap<>();
        for (StateKey key : keys) {
            requested.put(Objects.requireNonNull(key, "VehicleState key"), 0);
        }
        return withSession(context, requested.keySet(), session -> {
            LinkedHashMap<StateKey, Integer> result = new LinkedHashMap<>();
            for (StateKey key : requested.keySet()) {
                Integer value = session.readVehicleState(key);
                if (value == null) return null;
                result.put(key, value);
            }
            return Collections.unmodifiableMap(result);
        });
    }

    static Result sendBundle(Context context, Map<String, Integer> values,
                             Map<String, Integer> stableIds, String label) {
        return sendBundle(context, keyedValues(values, stableIds), label);
    }

    /**
     * Executes one TX58 followed by one TX77 without allowing another OEM transaction between them.
     * This is intended for settings whose duration/control state lives outside their shared bundle.
     */
    static Result sendVehicleStateThenBundle(Context context, StateValue first,
                                             Map<StateKey, Integer> values, String label) {
        return INSTANCE.sendSequenceInternal(
                context, Objects.requireNonNull(first, "first"), immutableCopy(values), label);
    }

    static Result sendVehicleStateThenBundle(
            Context context, String firstName, int firstStableId, int firstValue,
            Map<String, Integer> values, Map<String, Integer> stableIds, String label) {
        return sendVehicleStateThenBundle(
                context, new StateValue(new StateKey(firstName, firstStableId), firstValue),
                keyedValues(values, stableIds), label);
    }

    /**
     * Sends one ordered restore snapshot under the shared transaction lock. On Android 11 the OEM
     * TX77 implementation enqueues {@code AsyncTask.execute()} work on its serial executor, so the
     * trailing bundle is processed after the primary one. This is required for Snow: the primary
     * drive request may force recuperation=2 and the trailing explicit recuperation target must win.
     */
    static Result sendRestoreSequence(
            Context context, StateValue first,
            Map<String, Integer> primaryValues, Map<String, Integer> trailingValues,
            Map<String, Integer> stableIds, String label) {
        return INSTANCE.sendRestoreSequenceInternal(
                context, first,
                keyedValuesOptional(primaryValues, stableIds),
                keyedValuesOptional(trailingValues, stableIds), label);
    }

    private static LinkedHashMap<StateKey, Integer> keyedValues(
            Map<String, Integer> values, Map<String, Integer> stableIds) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("VehicleState bundle is empty");
        }
        if (stableIds == null) {
            throw new IllegalArgumentException("VehicleState stable-id map is null");
        }
        LinkedHashMap<StateKey, Integer> keyed = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            Integer stableId = stableIds.get(entry.getKey());
            if (stableId == null) {
                throw new IllegalArgumentException(
                        "No stable id for VehicleState " + entry.getKey());
            }
            keyed.put(new StateKey(entry.getKey(), stableId), entry.getValue());
        }
        return keyed;
    }

    private static LinkedHashMap<StateKey, Integer> keyedValuesOptional(
            Map<String, Integer> values, Map<String, Integer> stableIds) {
        if (values == null || values.isEmpty()) return new LinkedHashMap<>();
        return keyedValues(values, stableIds);
    }

    private static LinkedHashMap<StateKey, Integer> immutableCopy(
            Map<StateKey, Integer> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("VehicleState bundle is empty");
        }
        LinkedHashMap<StateKey, Integer> copy = new LinkedHashMap<>();
        for (Map.Entry<StateKey, Integer> entry : values.entrySet()) {
            StateKey key = Objects.requireNonNull(entry.getKey(), "VehicleState key");
            Integer value = Objects.requireNonNull(entry.getValue(), "VehicleState value");
            copy.put(key, value);
        }
        return copy;
    }

    private Result sendSingleInternal(Context context, StateValue state, String label) {
        Context app = applicationContext(context);
        if (app == null || !resolveStates(app, java.util.Collections.singleton(state.key))) {
            return Result.TRANSIENT_FAILURE;
        }
        if (CanSender.isDebugMode()) {
            synchronized (transactionLock) {
                return emulateSingle(state, label);
            }
        }
        IBinder binder = acquireBinder(app);
        if (binder == null) return Result.TRANSIENT_FAILURE;
        synchronized (transactionLock) {
            return transactSingle(binder, state, label);
        }
    }

    private <T> T withSessionInternal(Context context, Collection<StateKey> keys,
                                      SessionOperation<T> operation) {
        Context app = applicationContext(context);
        if (app == null || !resolveStates(app, keys)) return null;

        if (CanSender.isDebugMode()) {
            synchronized (transactionLock) {
                return operation.run(new BoundSession(null, true));
            }
        }

        IBinder binder = acquireBinder(app);
        if (binder == null) return null;
        synchronized (transactionLock) {
            if (!isCurrentBinder(binder)) return null;
            return operation.run(new BoundSession(binder, false));
        }
    }

    private final class BoundSession implements Session {
        private final IBinder binder;
        private final boolean emulated;

        BoundSession(IBinder binder, boolean emulated) {
            this.binder = binder;
            this.emulated = emulated;
        }

        @Override
        public GearStatus readGearStatus() {
            if (emulated) {
                Log.i(TAG, "EMULATE TX6 gear=Parking(0)");
                return new GearStatus(0, 0);
            }
            return transactGearStatus(binder);
        }

        @Override
        public Integer readVehicleState(StateKey key) {
            Objects.requireNonNull(key, "VehicleState key");
            if (emulated) {
                int value = "BMS_SOC_DISPLAY".equals(key.name) ? 100 : 0;
                Log.i(TAG, "EMULATE TX57 " + key + "=" + value);
                return value;
            }
            return transactVehicleState(binder, key);
        }

        @Override
        public Result sendVehicleState(StateValue state, String label) {
            Objects.requireNonNull(state, "VehicleState value");
            return emulated ? emulateSingle(state, label) : transactSingle(binder, state, label);
        }

        @Override
        public Result sendBundle(Map<StateKey, Integer> values, String label) {
            LinkedHashMap<StateKey, Integer> copy = immutableCopy(values);
            return emulated ? emulateBundle(copy, label) : transactBundle(binder, copy, label);
        }
    }

    private Result sendBundleInternal(Context context, LinkedHashMap<StateKey, Integer> values,
                                      String label) {
        Context app = applicationContext(context);
        if (app == null || !resolveStates(app, values.keySet())) {
            return Result.TRANSIENT_FAILURE;
        }
        if (CanSender.isDebugMode()) {
            synchronized (transactionLock) {
                return emulateBundle(values, label);
            }
        }
        IBinder binder = acquireBinder(app);
        if (binder == null) return Result.TRANSIENT_FAILURE;
        synchronized (transactionLock) {
            return transactBundle(binder, values, label);
        }
    }

    private Result sendSequenceInternal(Context context, StateValue first,
                                        LinkedHashMap<StateKey, Integer> values, String label) {
        return sendRestoreSequenceInternal(
                context, first, values, new LinkedHashMap<>(), label);
    }

    private Result sendRestoreSequenceInternal(
            Context context, StateValue first,
            LinkedHashMap<StateKey, Integer> primaryValues,
            LinkedHashMap<StateKey, Integer> trailingValues, String label) {
        Context app = applicationContext(context);
        if (app == null) return Result.TRANSIENT_FAILURE;
        LinkedHashMap<StateKey, Integer> all = new LinkedHashMap<>(primaryValues);
        all.putAll(trailingValues);
        if (first != null) all.put(first.key, first.value);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("VehicleState restore sequence is empty");
        }
        if (!resolveStates(app, all.keySet())) return Result.TRANSIENT_FAILURE;

        if (CanSender.isDebugMode()) {
            synchronized (transactionLock) {
                return emulateRestoreSequence(
                        first, primaryValues, trailingValues, label);
            }
        }
        IBinder binder = acquireBinder(app);
        if (binder == null) return Result.TRANSIENT_FAILURE;
        synchronized (transactionLock) {
            return transactRestoreSequence(
                    binder, first, primaryValues, trailingValues, label);
        }
    }

    private Result emulateRestoreSequence(
            StateValue first, Map<StateKey, Integer> primaryValues,
            Map<StateKey, Integer> trailingValues, String label) {
        if (first != null) {
            Result result = emulateSingle(first, label + " first");
            if (!result.accepted()) return result;
        }
        if (!primaryValues.isEmpty()) {
            Result result = emulateBundle(primaryValues, label + " primary");
            if (!result.accepted()) return result;
        }
        if (!trailingValues.isEmpty()) {
            return emulateBundle(trailingValues, label + " trailing");
        }
        return Result.ACCEPTED_UNCONFIRMED;
    }

    private Result transactRestoreSequence(
            IBinder binder, StateValue first, Map<StateKey, Integer> primaryValues,
            Map<StateKey, Integer> trailingValues, String label) {
        if (first != null) {
            Result result = transactSingle(binder, first, label + " first");
            if (!result.accepted()) return result;
        }
        if (!primaryValues.isEmpty()) {
            Result result = transactBundle(binder, primaryValues, label + " primary");
            if (!result.accepted()) return result;
        }
        if (!trailingValues.isEmpty()) {
            return transactBundle(binder, trailingValues, label + " trailing");
        }
        return Result.ACCEPTED_UNCONFIRMED;
    }

    private Context applicationContext(Context context) {
        if (context == null) return null;
        Context app = context.getApplicationContext();
        synchronized (connectionLock) {
            if (appContext == null) appContext = app;
            return appContext;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean resolveStates(Context context, Collection<StateKey> keys) {
        synchronized (schemaLock) {
            try {
                if (vehicleStateClass == null) {
                    ApplicationInfo info = context.getPackageManager()
                            .getApplicationInfo(CANBUS_PACKAGE, 0);
                    ClassLoader loader = new PathClassLoader(
                            info.sourceDir, context.getClassLoader());
                    Class rawClass = Class.forName(
                            "com.qinggan.canbus.VehicleState", true, loader);
                    if (!rawClass.isEnum()) {
                        Log.e(TAG, "VehicleState is not an enum");
                        return false;
                    }
                    vehicleStateClass = (Class<? extends Enum>) rawClass;
                    vehicleStateGetValue = rawClass.getMethod("getValue");
                }
                for (StateKey key : keys) {
                    if (resolvedOrdinals.containsKey(key)) continue;
                    Enum enumValue = Enum.valueOf(vehicleStateClass, key.name);
                    Object installedId = vehicleStateGetValue.invoke(enumValue);
                    if (!(installedId instanceof Integer)
                            || ((Integer) installedId).intValue() != key.stableId) {
                        Log.e(TAG, "VehicleState id mismatch for " + key
                                + ", installed=" + installedId);
                        return false;
                    }
                    resolvedOrdinals.put(key, enumValue.ordinal());
                }
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "CanBusService APK not found", e);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.e(TAG, "Cannot resolve installed VehicleState schema", e);
            }
            return false;
        }
    }

    private IBinder acquireBinder(Context context) {
        if (context.checkSelfPermission(WRITE_CANBUS_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "WRITE_CANBUS permission missing");
            return null;
        }

        IBinder current;
        DemandConnection staleConnection = null;
        boolean staleWasRegistered = false;
        DemandConnection newConnection = null;
        long now = SystemClock.elapsedRealtime();
        synchronized (connectionLock) {
            current = liveBinderLocked();
            if (current != null) return current;

            if (activeConnection != null && !bindingInProgress) {
                // Binder liveness can turn false just before Android delivers a disconnect callback.
                // Give that registered binding one bounded reconnect window, then the next demand
                // will replace it through the stale-generation path below.
                bindingInProgress = true;
                bindingStartedElapsed = now;
            }

            // No timer owns this recovery. A later real demand may replace exactly one stale bind;
            // generation fencing makes every delayed callback from the old attempt harmless.
            if (activeConnection != null && bindingInProgress
                    && now - bindingStartedElapsed >= STALE_BIND_MS) {
                staleConnection = activeConnection;
                staleWasRegistered = connectionRegistered;
                activeConnection = null;
                connectionRegistered = false;
                bindingInProgress = false;
            }
            if (activeConnection == null) {
                newConnection = new DemandConnection(++bindingGeneration);
                activeConnection = newConnection;
                bindingInProgress = true;
                bindingStartedElapsed = now;
            }
        }

        if (staleWasRegistered && staleConnection != null) {
            try {
                context.unbindService(staleConnection);
            } catch (RuntimeException e) {
                Log.w(TAG, "Stale CanBus unbind failed", e);
            }
        }
        if (newConnection != null) bindOnce(context, newConnection);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            synchronized (connectionLock) {
                return liveBinderLocked();
            }
        }

        long deadline = SystemClock.elapsedRealtime() + BIND_WAIT_MS;
        synchronized (connectionLock) {
            while ((current = liveBinderLocked()) == null && bindingInProgress) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) break;
                try {
                    connectionLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return liveBinderLocked();
        }
    }

    private void bindOnce(Context context, DemandConnection candidate) {
        boolean bound = false;
        try {
            Intent intent = new Intent(CANBUS_ACTION);
            intent.setPackage(CANBUS_PACKAGE);
            // Android 11: explicit executor keeps connection delivery well-defined while the
            // ApplyEngine worker performs a bounded wait for this one demand attempt.
            bound = context.bindService(intent, Context.BIND_AUTO_CREATE,
                    context.getMainExecutor(), candidate);
            Log.i(TAG, "demand bindService gen=" + candidate.generation
                    + " returned " + bound);
        } catch (RuntimeException e) {
            Log.e(TAG, "Demand CanBus bind failed", e);
        } finally {
            synchronized (connectionLock) {
                if (activeConnection == candidate) {
                    connectionRegistered = bound;
                    if (!bound) {
                        canBusBinder = null;
                        activeConnection = null;
                        bindingInProgress = false;
                    }
                }
                connectionLock.notifyAll();
            }
        }
    }

    private IBinder liveBinderLocked() {
        if (canBusBinder != null && canBusBinder.isBinderAlive()) return canBusBinder;
        canBusBinder = null;
        return null;
    }

    private Result emulateSingle(StateValue state, String label) {
        if (!CanSender.beginFrameAttemptForCurrentGuard()) return Result.TRANSIENT_FAILURE;
        Log.i(TAG, "EMULATE TX58 [" + safeLabel(label) + "] " + state.key
                + "=" + state.value + " accepted-unconfirmed");
        return Result.ACCEPTED_UNCONFIRMED;
    }

    private Result emulateBundle(Map<StateKey, Integer> values, String label) {
        if (!CanSender.beginFrameAttemptForCurrentGuard()) return Result.TRANSIENT_FAILURE;
        Log.i(TAG, "EMULATE TX77 [" + safeLabel(label) + "] states=" + values
                + " accepted-unconfirmed");
        return Result.ACCEPTED_UNCONFIRMED;
    }

    private Result transactSingle(IBinder binder, StateValue state, String label) {
        Integer ordinal;
        synchronized (schemaLock) {
            ordinal = resolvedOrdinals.get(state.key);
        }
        if (ordinal == null || !isCurrentBinder(binder)) return Result.TRANSIENT_FAILURE;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeInt(1); // VehicleState object is present.
            data.writeInt(ordinal);
            data.writeInt(state.key.stableId);
            data.writeInt(state.value);
            if (!CanSender.beginFrameAttemptForCurrentGuard()) {
                return Result.TRANSIENT_FAILURE;
            }
            if (!binder.transact(TX_SET_VEHICLE_STATE, data, reply, 0)) {
                Log.e(TAG, "TX58 rejected [" + safeLabel(label) + "] " + state.key);
                return Result.TRANSIENT_FAILURE;
            }
            reply.readException();
            Log.i(TAG, "TX58 accepted-unconfirmed [" + safeLabel(label) + "] "
                    + state.key + "=" + state.value);
            return Result.ACCEPTED_UNCONFIRMED;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "TX58 failed [" + safeLabel(label) + "] " + state.key, e);
            dropBinding(null, binder);
            return Result.TRANSIENT_FAILURE;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private GearStatus transactGearStatus(IBinder binder) {
        if (!isCurrentBinder(binder)) return null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            if (!binder.transact(TX_GEAR_STATUS, data, reply, 0)) {
                Log.e(TAG, "TX6 getGearStatus rejected");
                return null;
            }
            reply.readException();
            if (reply.readInt() == 0) {
                Log.e(TAG, "TX6 getGearStatus returned null");
                return null;
            }
            int ordinal = reply.readInt();
            int value = reply.readInt();
            Log.i(TAG, "TX6 getGearStatus ordinal=" + ordinal + " value=" + value);
            return new GearStatus(ordinal, value);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "TX6 getGearStatus failed", e);
            dropBinding(null, binder);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private Integer transactVehicleState(IBinder binder, StateKey key) {
        Integer ordinal;
        synchronized (schemaLock) {
            ordinal = resolvedOrdinals.get(key);
        }
        if (ordinal == null || !isCurrentBinder(binder)) return null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeInt(1); // VehicleState object is present.
            data.writeInt(ordinal);
            data.writeInt(key.stableId);
            if (!binder.transact(TX_GET_VEHICLE_STATE, data, reply, 0)) {
                Log.e(TAG, "TX57 getVehicleState rejected " + key);
                return null;
            }
            reply.readException();
            int value = reply.readInt();
            Log.i(TAG, "TX57 getVehicleState " + key + "=" + value);
            return value;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "TX57 getVehicleState failed " + key, e);
            dropBinding(null, binder);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private Result transactBundle(IBinder binder, Map<StateKey, Integer> values, String label) {
        if (!isCurrentBinder(binder)) return Result.TRANSIENT_FAILURE;
        Bundle vehicleBundle = new Bundle();
        for (Map.Entry<StateKey, Integer> entry : values.entrySet()) {
            vehicleBundle.putInt(entry.getKey().name, entry.getValue());
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeInt(0); // air-condition bundle is null.
            data.writeInt(1); // vehicle bundle is present.
            vehicleBundle.writeToParcel(data, 0);
            if (!CanSender.beginFrameAttemptForCurrentGuard()) {
                return Result.TRANSIENT_FAILURE;
            }
            if (!binder.transact(
                    TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE, data, reply, 0)) {
                Log.e(TAG, "TX77 rejected [" + safeLabel(label) + "]");
                return Result.TRANSIENT_FAILURE;
            }
            reply.readException();
            int result = reply.readInt();
            if (result != 0) {
                Log.e(TAG, "TX77 returned " + result + " [" + safeLabel(label) + "]");
                return Result.TRANSIENT_FAILURE;
            }
            // H97C starts ModeSettingTask asynchronously and returns zero before sendCanIOCtl.
            Log.i(TAG, "TX77 accepted-unconfirmed [" + safeLabel(label)
                    + "] states=" + vehicleBundle);
            return Result.ACCEPTED_UNCONFIRMED;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "TX77 failed [" + safeLabel(label) + "]", e);
            dropBinding(null, binder);
            return Result.TRANSIENT_FAILURE;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean isCurrentBinder(IBinder binder) {
        synchronized (connectionLock) {
            return binder != null && binder == liveBinderLocked();
        }
    }

    private void dropBinding(DemandConnection failedConnection, IBinder failedBinder) {
        Context context;
        boolean registered;
        DemandConnection connection;
        synchronized (connectionLock) {
            if (failedConnection != null && activeConnection != failedConnection) return;
            if (failedBinder != null && canBusBinder != null && canBusBinder != failedBinder) {
                return;
            }
            canBusBinder = null;
            bindingInProgress = false;
            context = appContext;
            // A ServiceConnection callback itself proves that this candidate was registered. With
            // the API-30 Executor overload it may race the bindService() caller's finally block,
            // before connectionRegistered has been published there.
            registered = connectionRegistered || failedConnection != null;
            connection = activeConnection;
            connectionRegistered = false;
            activeConnection = null;
            connectionLock.notifyAll();
        }
        if (registered && context != null && connection != null) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException e) {
                Log.w(TAG, "CanBus unbind failed", e);
            }
        }
    }

    private static String safeLabel(String label) {
        return label == null || label.isEmpty() ? "?" : label;
    }
}
