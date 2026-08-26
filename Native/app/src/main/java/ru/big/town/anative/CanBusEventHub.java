package ru.big.town.anative;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One lazy, process-wide read-side connection to the Qinggan CanBus service.
 *
 * <p>Binding and callback registration run on {@code CanBusEventHubIo}; blocking snapshot reads
 * use isolated query threads so a stuck vendor call cannot stop reconnect/unbind. Incoming oneway
 * callbacks do only primitive decoding, interest filtering and a bounded mailbox offer on their
 * Binder-pool thread. Consumer work is always posted through the supplied Handler; it is never
 * invoked inline from Binder.</p>
 */
final class CanBusEventHub {
    private static final String TAG = "CanBusEventHub";

    private static final String CANBUS_DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    private static final String CALLBACK_DESCRIPTOR =
            "com.qinggan.canbus.ICanBusServiceCallback";
    private static final String CANBUS_ACTION = "com.qinggan.canbus.CanBusService";
    private static final String CANBUS_PACKAGE = "com.qinggan.canbus.service";

    private static final int TX_GET_DOOR_STATUS = 2;
    private static final int TX_QUERY_VEHICLE_STATE = 20;
    private static final int TX_ADD_CALLBACK = 28;
    private static final int TX_REMOVE_CALLBACK = 29;

    private static final int CB_DOOR_STATUS = 1;
    private static final int CB_AIR_CONDITION = 4;
    private static final int CB_LIGHT_STATUS = 10;
    private static final int CB_GEAR_STATUS = 12;
    private static final int CB_VEHICLE_STATE = 36;

    private static final int LIGHT_FIELD_COUNT = 17;
    private static final int LIGHT_DIPPED_BEAM_INDEX = 7;
    private static final int LIGHT_HEAD_LIGHT_INDEX = 13;
    private static final int LIGHT_AUTO_LAMP_INDEX = 16;
    private static final int AIR_TEMPERATURE_OUT_INDEX = 35;
    private static final int AIR_TEMPERATURE_INVALID = -9999;

    private static final int PRE_READY_CAPACITY = 64;
    private static final long BIND_RETRY_MS = 5_000L;
    private static final long CALLBACK_RETRY_MS = 30_000L;

    private static volatile CanBusEventHub instance;

    interface Listener {
        void onCanBusEvent(CanBusEvent event);
    }

    static CanBusEventHub get(Context context) {
        CanBusEventHub current = instance;
        if (current != null) return current;
        synchronized (CanBusEventHub.class) {
            current = instance;
            if (current == null) {
                Context appContext = context.getApplicationContext();
                instance = current = new CanBusEventHub(
                        appContext != null ? appContext : context);
            }
        }
        return current;
    }

    private final Context context;
    private final HandlerThread ioThread;
    private final Handler ioHandler;
    private final HandlerThread doorQueryThread;
    private final Handler doorQueryHandler;
    private final HandlerThread vehicleQueryThread;
    private final Handler vehicleQueryHandler;
    private final Executor ioExecutor;
    private final CanBusEventRouter router = new CanBusEventRouter();
    private final AtomicInteger subscriberCount = new AtomicInteger();
    private final AtomicLong malformedCallbacks = new AtomicLong();
    private final AtomicLong preReadyDrops = new AtomicLong();
    private final AtomicBoolean doorSeedRequestPosted = new AtomicBoolean();
    private final AtomicBoolean vehicleSnapshotRequestPosted = new AtomicBoolean();

    /* Guards callback ingress ordering, the ready barrier and seed revisions. */
    private final Object eventLock = new Object();
    private final ArrayDeque<CanBusEvent> preReadyEvents = new ArrayDeque<>();
    private long nextSequence;
    private long readyEpoch;
    private long doorRevision;
    private CanBusEvent pendingConnectionEvent;
    private volatile long activeEpoch;

    /* The fields below are confined to ioHandler. */
    private IBinder remote;
    private CallbackBinder callbackBinder;
    private IBinder.DeathRecipient deathRecipient;
    private boolean bindingRequested;
    private boolean callbackAdded;
    private long nextEpoch;
    private long lastBindAttemptElapsed = -BIND_RETRY_MS;
    private ServiceConnection serviceConnection;
    private long nextBindingGeneration;
    private long activeBindingGeneration;
    private final LatestSingleFlight doorQueryGate = new LatestSingleFlight();
    private final LatestSingleFlight vehicleQueryGate = new LatestSingleFlight();

    private final Runnable bindRetryRunnable = this::ensureBound;
    private final Runnable callbackRetryRunnable = this::ensureCallbackRegistered;

    private CanBusEventHub(Context context) {
        this.context = context;
        ioThread = new HandlerThread("CanBusEventHubIo");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        doorQueryThread = new HandlerThread("CanBusDoorQuery");
        doorQueryThread.start();
        doorQueryHandler = new Handler(doorQueryThread.getLooper());
        vehicleQueryThread = new HandlerThread("CanBusVehicleQuery");
        vehicleQueryThread.start();
        vehicleQueryHandler = new Handler(vehicleQueryThread.getLooper());
        ioExecutor = command -> {
            if (!ioHandler.post(command)) {
                throw new RejectedExecutionException("CanBusEventHubIo stopped");
            }
        };
    }

    /** Adds a subscriber without binding until this IO-thread task is processed. */
    Subscription subscribe(int interestMask, int[] vehicleStateIds, Handler deliveryHandler,
                           Listener listener) {
        if (deliveryHandler == null || listener == null) {
            throw new IllegalArgumentException("deliveryHandler/listener required");
        }
        Subscription subscription = new Subscription(this, interestMask,
                vehicleStateIds == null ? null : vehicleStateIds.clone(),
                deliveryHandler, listener);
        subscriberCount.incrementAndGet();
        ioHandler.post(() -> addSubscription(subscription));
        return subscription;
    }

    /** Blocking TX2 is posted to the hub IO thread; a stale result is discarded by revision. */
    void requestDriverDoorSeed() {
        if (!router.hasInterest(CanBusEventRouter.INTEREST_DOOR)) return;
        if (!doorSeedRequestPosted.compareAndSet(false, true)) return;
        if (!ioHandler.post(this::acceptDriverDoorQueryRequest)) {
            doorSeedRequestPosted.set(false);
        }
    }

    /**
     * Temporarily preserves the legacy TX20 snapshot request for BatteryHeatService.
     * The request is synchronous, but always executes on the hub IO thread; incoming code 36 is
     * still filtered by the union of subscribers before an event is allocated.
     */
    void requestVehicleStateSnapshot() {
        if (!router.hasInterest(CanBusEventRouter.INTEREST_VEHICLE_STATE)) return;
        if (!vehicleSnapshotRequestPosted.compareAndSet(false, true)) return;
        if (!ioHandler.post(this::acceptVehicleStateQueryRequest)) {
            vehicleSnapshotRequestPosted.set(false);
        }
    }

    private void addSubscription(Subscription subscription) {
        if (subscription.isClosed()) {
            releaseIfUnused();
            return;
        }

        Executor deliveryExecutor = command -> {
            if (!subscription.deliveryHandler.post(command)) {
                throw new RejectedExecutionException("consumer Handler stopped");
            }
        };
        synchronized (eventLock) {
            CanBusEventRouter.Subscription routed = router.subscribe(
                    subscription.interestMask, subscription.vehicleStateIds,
                    deliveryExecutor, event -> {
                        if (event.kind == CanBusEvent.Kind.CONNECTION_LOST
                                || event.connectionEpoch == activeEpoch) {
                            subscription.listener.onCanBusEvent(event);
                        }
                    });
            if (!subscription.attach(routed)) return;

            // A subscriber joining an already-ready session sees the barrier before later events.
            if (activeEpoch != 0 && readyEpoch == activeEpoch) {
                routed.offer(CanBusEvent.connection(activeEpoch, ++nextSequence,
                        SystemClock.elapsedRealtime()));
            }
        }
        ensureBound();
    }

    private void onSubscriptionClosed() {
        int remaining = subscriberCount.decrementAndGet();
        if (remaining < 0) {
            subscriberCount.set(0);
            throw new IllegalStateException("negative CanBus subscriber count");
        }
        if (remaining == 0) ioHandler.post(this::releaseIfUnused);
    }

    private void releaseIfUnused() {
        if (subscriberCount.get() == 0) releaseBinding("last subscriber closed");
    }

    private void ensureBound() {
        if (subscriberCount.get() == 0 || bindingRequested) return;
        long now = SystemClock.elapsedRealtime();
        long wait = BIND_RETRY_MS - (now - lastBindAttemptElapsed);
        if (wait > 0) {
            ioHandler.removeCallbacks(bindRetryRunnable);
            ioHandler.postDelayed(bindRetryRunnable, wait);
            return;
        }

        lastBindAttemptElapsed = now;
        long bindingGeneration = ++nextBindingGeneration;
        GenerationServiceConnection connection =
                new GenerationServiceConnection(bindingGeneration);
        serviceConnection = connection;
        activeBindingGeneration = bindingGeneration;
        try {
            Intent intent = new Intent(CANBUS_ACTION).setPackage(CANBUS_PACKAGE);
            boolean bound = context.bindService(intent, Context.BIND_AUTO_CREATE,
                    ioExecutor, connection);
            bindingRequested = bound;
            if (!bound) {
                serviceConnection = null;
                activeBindingGeneration = 0;
                scheduleBindRetry();
            }
            Log.i(TAG, "bindService returned " + bound);
        } catch (RuntimeException e) {
            bindingRequested = false;
            serviceConnection = null;
            activeBindingGeneration = 0;
            Log.w(TAG, "bindService failed: " + e.getMessage());
            scheduleBindRetry();
        }
    }

    private void scheduleBindRetry() {
        if (subscriberCount.get() == 0) return;
        ioHandler.removeCallbacks(bindRetryRunnable);
        ioHandler.postDelayed(bindRetryRunnable, BIND_RETRY_MS);
    }

    private void handleServiceConnected(IBinder service) {
        if (subscriberCount.get() == 0) {
            releaseBinding("connected without subscribers");
            return;
        }

        // A duplicate callback for the same live binding must not create a second OEM observer.
        if (remote == service) {
            bindingRequested = true;
            if (!callbackAdded) ensureCallbackRegistered();
            return;
        }

        ioHandler.removeCallbacks(bindRetryRunnable);
        ioHandler.removeCallbacks(callbackRetryRunnable);
        if (callbackAdded && remote != null && callbackBinder != null
                && remote.isBinderAlive()) {
            removeCallback(remote, callbackBinder);
        }
        clearRemoteSession("new service connection");
        bindingRequested = true;
        remote = service;
        callbackAdded = false;

        long epoch = ++nextEpoch;
        CallbackBinder newCallback = new CallbackBinder(epoch);
        callbackBinder = newCallback;
        IBinder.DeathRecipient newDeathRecipient =
                () -> ioHandler.post(() -> handleBinderDeath(epoch, service));
        deathRecipient = newDeathRecipient;

        synchronized (eventLock) {
            activeEpoch = epoch;
            readyEpoch = 0;
            doorRevision++;
            preReadyEvents.clear();
            pendingConnectionEvent = CanBusEvent.connection(epoch, ++nextSequence,
                    SystemClock.elapsedRealtime());
        }

        try {
            service.linkToDeath(newDeathRecipient, 0);
        } catch (RemoteException e) {
            restartBinding("binder already dead");
            return;
        }

        Log.i(TAG, "CanBus service connected, epoch=" + epoch);
        ensureCallbackRegistered();
    }

    private void handleBinderDeath(long epoch, IBinder binder) {
        if (epoch != activeEpoch || binder != remote) return;
        restartBinding("binder died");
    }

    private void ensureCallbackRegistered() {
        if (subscriberCount.get() == 0 || remote == null || callbackBinder == null
                || callbackAdded) return;
        ioHandler.removeCallbacks(callbackRetryRunnable);
        long epoch = activeEpoch;
        if (!addCallback(remote, callbackBinder)) {
            if (!remote.isBinderAlive()) {
                restartBinding("callback registration lost binder");
            } else {
                ioHandler.postDelayed(callbackRetryRunnable, CALLBACK_RETRY_MS);
            }
            return;
        }

        callbackAdded = true;
        synchronized (eventLock) {
            if (activeEpoch != epoch) return;
            readyEpoch = epoch;
            if (pendingConnectionEvent != null) {
                router.dispatch(pendingConnectionEvent);
                pendingConnectionEvent = null;
            }
            while (!preReadyEvents.isEmpty()) router.dispatch(preReadyEvents.removeFirst());
        }
        Log.i(TAG, "CanBus callback registered, epoch=" + epoch
                + " malformed=" + malformedCallbacks.get()
                + " preReadyDrops=" + preReadyDrops.get());
    }

    private boolean addCallback(IBinder binder, IBinder callback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!binder.transact(TX_ADD_CALLBACK, data, reply, 0)) return false;
            reply.readException();
            // Vendor AIDL signature is boolean addCallback(...): 1 means success.
            return reply.readInt() != 0;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "addCallback failed: " + e.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void removeCallback(IBinder binder, IBinder callback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!binder.transact(TX_REMOVE_CALLBACK, data, reply, 0)) return;
            reply.readException();
            if (reply.dataAvail() >= Integer.BYTES && reply.readInt() == 0) {
                Log.w(TAG, "removeCallback returned false");
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "removeCallback failed: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void restartBinding(String reason) {
        Log.w(TAG, "Restarting CanBus binding: " + reason);
        releaseBinding(reason);
        scheduleBindRetry();
    }

    private void releaseBinding(String reason) {
        ioHandler.removeCallbacks(bindRetryRunnable);
        ioHandler.removeCallbacks(callbackRetryRunnable);

        IBinder oldRemote = remote;
        CallbackBinder oldCallback = callbackBinder;
        if (callbackAdded && oldRemote != null && oldCallback != null) {
            removeCallback(oldRemote, oldCallback);
        }
        clearRemoteSession(reason);

        ServiceConnection oldConnection = serviceConnection;
        boolean wasBindingRequested = bindingRequested;
        serviceConnection = null;
        activeBindingGeneration = 0;
        bindingRequested = false;
        if (wasBindingRequested && oldConnection != null) {
            try {
                context.unbindService(oldConnection);
            } catch (RuntimeException e) {
                Log.w(TAG, reason + ": unbind failed: " + e.getMessage());
            }
        }
    }

    private void clearRemoteSession(String reason) {
        ioHandler.removeCallbacks(callbackRetryRunnable);
        IBinder oldRemote = remote;
        IBinder.DeathRecipient oldDeathRecipient = deathRecipient;
        if (oldRemote != null && oldDeathRecipient != null) {
            try {
                oldRemote.unlinkToDeath(oldDeathRecipient, 0);
            } catch (RuntimeException ignored) {
                // A dead binder commonly rejects unlink; session invalidation below is enough.
            }
        }
        remote = null;
        callbackBinder = null;
        deathRecipient = null;
        callbackAdded = false;
        synchronized (eventLock) {
            long closingEpoch = activeEpoch;
            activeEpoch = 0;
            readyEpoch = 0;
            if (closingEpoch != 0) {
                doorRevision++;
                router.invalidateThrough(closingEpoch);
                long lostBarrierEpoch = ++nextEpoch;
                router.dispatch(CanBusEvent.connectionLost(
                        lostBarrierEpoch, ++nextSequence,
                        SystemClock.elapsedRealtime(), closingEpoch));
            }
            pendingConnectionEvent = null;
            preReadyEvents.clear();
        }
        if (!"new service connection".equals(reason)) {
            Log.i(TAG, "CanBus session cleared: " + reason);
        }
    }

    private void acceptDriverDoorQueryRequest() {
        doorSeedRequestPosted.set(false);
        doorQueryGate.request();
        startDriverDoorQueryIfNeeded();
    }

    private void startDriverDoorQueryIfNeeded() {
        if (!doorQueryGate.tryStart()) return;
        IBinder binder = remote;
        long epoch;
        long revision;
        synchronized (eventLock) {
            epoch = activeEpoch;
            if (epoch == 0 || readyEpoch != epoch || binder == null) {
                doorQueryGate.complete();
                return;
            }
            revision = doorRevision;
        }

        if (!doorQueryHandler.post(() -> {
            Integer frontLeft = readDriverDoor(binder);
            if (!ioHandler.post(() -> finishDriverDoorQuery(
                    binder, epoch, revision, frontLeft))) {
                Log.w(TAG, "Door query completion rejected: hub IO stopped");
            }
        })) {
            doorQueryGate.complete();
            startDriverDoorQueryIfNeeded();
        }
    }

    private void finishDriverDoorQuery(IBinder binder, long epoch, long revision,
                                       Integer frontLeft) {
        try {
            if (frontLeft == null) return;
            synchronized (eventLock) {
                if (activeEpoch != epoch || readyEpoch != epoch || binder != remote
                        || revision != doorRevision) {
                    return;
                }
                doorRevision++;
                router.dispatch(CanBusEvent.door(CanBusEvent.Origin.SEED, epoch,
                        ++nextSequence, SystemClock.elapsedRealtime(), frontLeft));
            }
        } finally {
            doorQueryGate.complete();
            startDriverDoorQueryIfNeeded();
        }
    }

    private Integer readDriverDoor(IBinder binder) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            if (!binder.transact(TX_GET_DOOR_STATUS, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            reply.readInt(); // bonnetDoor
            return reply.readInt(); // fLDoor
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "getDoorStatus failed: " + e.getMessage());
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void acceptVehicleStateQueryRequest() {
        vehicleSnapshotRequestPosted.set(false);
        vehicleQueryGate.request();
        startVehicleStateQueryIfNeeded();
    }

    private void startVehicleStateQueryIfNeeded() {
        if (!vehicleQueryGate.tryStart()) return;
        IBinder binder = remote;
        long epoch = activeEpoch;
        if (binder == null || epoch == 0) {
            vehicleQueryGate.complete();
            return;
        }
        synchronized (eventLock) {
            if (readyEpoch != epoch) {
                vehicleQueryGate.complete();
                return;
            }
        }

        if (!vehicleQueryHandler.post(() -> queryVehicleState(binder, epoch))) {
            vehicleQueryGate.complete();
            startVehicleStateQueryIfNeeded();
        }
    }

    private void queryVehicleState(IBinder binder, long epoch) {
        long started = SystemClock.elapsedRealtime();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CANBUS_DESCRIPTOR);
            if (!binder.transact(TX_QUERY_VEHICLE_STATE, data, reply, 0)) return;
            reply.readException();
            long duration = SystemClock.elapsedRealtime() - started;
            if (duration > 1_000L) Log.w(TAG, "TX20 took " + duration + " ms");
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "queryVehicleState failed: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
            if (!ioHandler.post(this::finishVehicleStateQuery)) {
                Log.w(TAG, "Vehicle query completion rejected: hub IO stopped");
            }
        }
    }

    private void finishVehicleStateQuery() {
        vehicleQueryGate.complete();
        startVehicleStateQueryIfNeeded();
    }

    private void routeDoor(long epoch, int frontLeft) {
        synchronized (eventLock) {
            if (epoch != activeEpoch) return;
            doorRevision++;
            routeLocked(CanBusEvent.door(CanBusEvent.Origin.LIVE, epoch,
                    ++nextSequence, SystemClock.elapsedRealtime(), frontLeft));
        }
    }

    private void routeGear(long epoch, int value) {
        synchronized (eventLock) {
            if (epoch != activeEpoch) return;
            routeLocked(CanBusEvent.gear(CanBusEvent.Origin.LIVE, epoch,
                    ++nextSequence, SystemClock.elapsedRealtime(), value));
        }
    }

    private void routeLight(long epoch, int autoLamp, int dippedBeam, int headLight) {
        synchronized (eventLock) {
            if (epoch != activeEpoch) return;
            routeLocked(CanBusEvent.light(CanBusEvent.Origin.LIVE, epoch,
                    ++nextSequence, SystemClock.elapsedRealtime(),
                    autoLamp, dippedBeam, headLight));
        }
    }

    private void routeVehicleState(long epoch, int stableId, int value) {
        if (!router.hasVehicleStateInterest(stableId)) return;
        synchronized (eventLock) {
            if (epoch != activeEpoch || !router.hasVehicleStateInterest(stableId)) return;
            routeLocked(CanBusEvent.vehicleState(CanBusEvent.Origin.LIVE, epoch,
                    ++nextSequence, SystemClock.elapsedRealtime(), stableId, value));
        }
    }

    private void routeAmbientTemperature(long epoch, int value) {
        synchronized (eventLock) {
            if (epoch != activeEpoch) return;
            routeLocked(CanBusEvent.ambientTemperature(CanBusEvent.Origin.LIVE, epoch,
                    ++nextSequence, SystemClock.elapsedRealtime(), value));
        }
    }

    private void routeLocked(CanBusEvent event) {
        if (readyEpoch == event.connectionEpoch) {
            router.dispatch(event);
            return;
        }
        if (preReadyEvents.size() == PRE_READY_CAPACITY) {
            preReadyEvents.removeFirst();
            preReadyDrops.incrementAndGet();
        }
        preReadyEvents.addLast(event);
    }

    private void malformed(int code, RuntimeException error) {
        long count = malformedCallbacks.incrementAndGet();
        // Power-of-two sampling prevents a malformed vendor stream from becoming a log storm.
        if ((count & (count - 1)) == 0) {
            Log.w(TAG, "Dropped malformed callback code=" + code + " count=" + count
                    + ": " + error.getMessage());
        }
    }

    private final class CallbackBinder extends Binder {
        private final long epoch;

        CallbackBinder(long epoch) {
            this.epoch = epoch;
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                switch (code) {
                    case CB_DOOR_STATUS: {
                        if (!router.hasInterest(CanBusEventRouter.INTEREST_DOOR)) return true;
                        data.enforceInterface(CALLBACK_DESCRIPTOR);
                        if (data.readInt() == 0) return true;
                        data.readInt(); // bonnetDoor
                        routeDoor(epoch, data.readInt()); // fLDoor
                        return true;
                    }
                    case CB_AIR_CONDITION: {
                        if (!router.hasInterest(
                                CanBusEventRouter.INTEREST_AMBIENT_TEMPERATURE)) return true;
                        data.enforceInterface(CALLBACK_DESCRIPTOR);
                        int outsideTemperature = AIR_TEMPERATURE_INVALID;
                        if (data.readInt() != 0) {
                            for (int index = 0; index <= AIR_TEMPERATURE_OUT_INDEX; index++) {
                                if (index >= 11 && index <= 13) {
                                    data.readFloat();
                                } else {
                                    int value = data.readInt();
                                    if (index == AIR_TEMPERATURE_OUT_INDEX) {
                                        outsideTemperature = value;
                                    }
                                }
                            }
                        }
                        routeAmbientTemperature(epoch, outsideTemperature);
                        return true;
                    }
                    case CB_LIGHT_STATUS: {
                        if (!router.hasInterest(CanBusEventRouter.INTEREST_LIGHT_STATUS)) return true;
                        data.enforceInterface(CALLBACK_DESCRIPTOR);
                        int autoLamp = -1;
                        int dippedBeam = -1;
                        int headLight = -1;
                        if (data.readInt() != 0) {
                            for (int index = 0; index < LIGHT_FIELD_COUNT; index++) {
                                int value = data.readInt();
                                if (index == LIGHT_DIPPED_BEAM_INDEX) dippedBeam = value;
                                else if (index == LIGHT_HEAD_LIGHT_INDEX) headLight = value;
                                else if (index == LIGHT_AUTO_LAMP_INDEX) autoLamp = value;
                            }
                        }
                        routeLight(epoch, autoLamp, dippedBeam, headLight);
                        return true;
                    }
                    case CB_GEAR_STATUS: {
                        if (!router.hasInterest(CanBusEventRouter.INTEREST_GEAR)) return true;
                        data.enforceInterface(CALLBACK_DESCRIPTOR);
                        if (data.readInt() == 0) return true;
                        data.readInt(); // ordinal
                        routeGear(epoch, data.readInt()); // stable enum value
                        return true;
                    }
                    case CB_VEHICLE_STATE: {
                        if (!router.hasInterest(CanBusEventRouter.INTEREST_VEHICLE_STATE)) return true;
                        data.enforceInterface(CALLBACK_DESCRIPTOR);
                        boolean present = data.readInt() != 0;
                        int stableId = -1;
                        if (present) {
                            data.readInt(); // ordinal
                            stableId = data.readInt();
                        }
                        int value = data.readInt();
                        if (present) routeVehicleState(epoch, stableId, value);
                        return true;
                    }
                    default:
                        // All vendor callback transactions are oneway. Unknown regular codes must be
                        // swallowed silently, otherwise Binder emits UNKNOWN_TRANSACTION log spam.
                        if (code >= IBinder.FIRST_CALL_TRANSACTION
                                && code <= IBinder.LAST_CALL_TRANSACTION) {
                            return true;
                        }
                        return super.onTransact(code, data, reply, flags);
                }
            } catch (RuntimeException malformed) {
                malformed(code, malformed);
                return true;
            }
        }
    }

    private final class GenerationServiceConnection implements ServiceConnection {
        private final long generation;

        GenerationServiceConnection(long generation) {
            this.generation = generation;
        }

        private boolean isCurrent() {
            return serviceConnection == this && activeBindingGeneration == generation;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (isCurrent()) handleServiceConnected(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (!isCurrent()) return;
            clearRemoteSession("service disconnected");
            Log.w(TAG, "CanBus service disconnected; waiting for framework reconnect");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (isCurrent()) restartBinding("binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (isCurrent()) restartBinding("null binding");
        }
    }

    static final class Subscription implements AutoCloseable {
        private final CanBusEventHub owner;
        private final int interestMask;
        private final int[] vehicleStateIds;
        private final Handler deliveryHandler;
        private final Listener listener;

        private CanBusEventRouter.Subscription routed;
        private boolean closed;

        Subscription(CanBusEventHub owner, int interestMask, int[] vehicleStateIds,
                     Handler deliveryHandler, Listener listener) {
            this.owner = owner;
            this.interestMask = interestMask;
            this.vehicleStateIds = vehicleStateIds;
            this.deliveryHandler = deliveryHandler;
            this.listener = listener;
        }

        synchronized boolean isClosed() {
            return closed;
        }

        synchronized boolean attach(CanBusEventRouter.Subscription subscription) {
            if (closed) {
                subscription.close();
                return false;
            }
            routed = subscription;
            return true;
        }

        void forgetLightStatus() {
            CanBusEventRouter.Subscription current;
            synchronized (this) {
                if (closed) return;
                current = routed;
            }
            if (current != null) current.forgetSignal(CanBusEvent.Kind.LIGHT_STATUS);
        }

        @Override
        public void close() {
            CanBusEventRouter.Subscription toClose;
            synchronized (this) {
                if (closed) return;
                closed = true;
                toClose = routed;
                routed = null;
            }
            if (toClose != null) toClose.close();
            owner.onSubscriptionClosed();
        }
    }
}
