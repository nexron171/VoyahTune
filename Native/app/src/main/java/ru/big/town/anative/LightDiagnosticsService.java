package ru.big.town.anative;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Read-only, bound diagnostic observer. No light-control commands are issued here. */
public final class LightDiagnosticsService extends Service {
    public static final int WATCH = 1;
    public static final int UPDATE = 2;
    public static final String VALUES = "values";
    private static final String TAG = "LightDiagnostics";
    private static final int UNKNOWN = Integer.MIN_VALUE;
    private static final int[] RSM_IDS = {1072, 1071, 1070, 1073};
    private static final String[] RSM_NAMES = {"BCM_RSM_lightSWReason", "BCM_RSM_AmbBrightness",
            "BCM_RSM_FwBrightness", "BCM_RSM_IRBrightness"};
    private static final String CAR_ACTION = "com.qinggan.carsignal.CarSignalService";
    private static final String CAR_PACKAGE = "com.qinggan.carsignal.service";
    private static final String CAR_DESCRIPTOR = "com.qinggan.carsignal.ICarSignalService";
    private static final String CALLBACK_DESCRIPTOR = "com.qinggan.carsignal.ICarSignalServiceCallBack";
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(2, 2, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "LightDiagnostics-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private final Handler main = new Handler(Looper.getMainLooper());
    private final int[] values = new int[6];
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (message.what == WATCH) {
                client = message.replyTo;
                clientSession = message.arg1;
                publish();
            } else {
                super.handleMessage(message);
            }
        }
    });
    private Messenger client;
    private int clientSession;
    private CanBusEventHub.Subscription subscription;
    private ServiceConnection carConnection;
    private volatile IBinder carBinder;
    private volatile IBinder carCallback;
    private volatile int generation;
    private int canSnapshotRevision;

    @Override public void onCreate() {
        super.onCreate();
        Arrays.fill(values, UNKNOWN);
    }

    @Override public IBinder onBind(Intent intent) {
        startWatching();
        return endpoint.getBinder();
    }

    @Override public boolean onUnbind(Intent intent) {
        stopWatching();
        return false;
    }

    @Override public void onDestroy() {
        stopWatching();
        super.onDestroy();
    }

    private void startWatching() {
        if (subscription != null) return;
        final int session = ++generation;
        Arrays.fill(values, UNKNOWN);
        subscription = CanBusEventHub.get(this).subscribe(
                CanBusEventRouter.INTEREST_CONNECTION | CanBusEventRouter.INTEREST_VEHICLE_STATE,
                RSM_IDS, main, event -> onCanEvent(session, event));
        carConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (generation != session) return;
                carBinder = binder;
                IBinder callback = new Binder() {
                    @Override protected boolean onTransact(int code, Parcel data, Parcel reply,
                                                            int flags) throws RemoteException {
                        if (code == 13 || code == 25) {
                            data.enforceInterface(CALLBACK_DESCRIPTOR);
                            int value = data.readInt();
                            int index = code == 13 ? 4 : 5;
                            main.post(() -> {
                                if (generation == session && carCallback == this) {
                                    values[index] = value;
                                    publish();
                                }
                            });
                            return true;
                        }
                        if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) return true;
                        return super.onTransact(code, data, reply, flags);
                    }
                };
                carCallback = callback;
                submitIo(() -> {
                    boolean registered = transactCallback(binder, 46, callback);
                    if (generation != session || carBinder != binder || carCallback != callback) {
                        if (registered) transactCallback(binder, 47, callback);
                        return;
                    }
                    int light = readCarInt(binder, 36);
                    int pas = readCarInt(binder, 73);
                    main.post(() -> {
                        if (generation != session || carBinder != binder) return;
                        if (values[4] == UNKNOWN) values[4] = light;
                        if (values[5] == UNKNOWN) values[5] = pas;
                        publish();
                    });
                });
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                if (generation == session) clearCarSignal();
            }
        };
        Intent carIntent = new Intent(CAR_ACTION).setPackage(CAR_PACKAGE);
        try {
            if (!bindService(carIntent, carConnection, BIND_AUTO_CREATE)) {
                Log.w(TAG, "CarSignalService unavailable");
                carConnection = null;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "CarSignalService bind failed", e);
            carConnection = null;
        }
    }

    private void onCanEvent(int session, CanBusEvent event) {
        if (generation != session) return;
        if (event.kind == CanBusEvent.Kind.CONNECTION_LOST) {
            ++canSnapshotRevision;
            Arrays.fill(values, 0, 4, UNKNOWN);
            publish();
        } else if (event.kind == CanBusEvent.Kind.CONNECTION) {
            final int snapshotRevision = ++canSnapshotRevision;
            submitIo(() -> {
                OemVehicleStateTransport.StateKey[] keys = new OemVehicleStateTransport.StateKey[4];
                for (int i = 0; i < 4; i++) keys[i] =
                        new OemVehicleStateTransport.StateKey(RSM_NAMES[i], RSM_IDS[i]);
                Map<OemVehicleStateTransport.StateKey, Integer> snapshot =
                        OemVehicleStateTransport.readVehicleStates(this, Arrays.asList(keys));
                main.post(() -> {
                    if (generation != session || canSnapshotRevision != snapshotRevision
                            || snapshot == null) return;
                    for (int i = 0; i < 4; i++) {
                        if (values[i] == UNKNOWN && snapshot.get(keys[i]) != null) {
                            values[i] = snapshot.get(keys[i]);
                        }
                    }
                    publish();
                });
            });
        } else if (event.kind == CanBusEvent.Kind.VEHICLE_STATE) {
            for (int i = 0; i < 4; i++) {
                if (event.first == RSM_IDS[i]) {
                    values[i] = event.second;
                    publish();
                    break;
                }
            }
        }
    }

    private void clearCarSignal() {
        carBinder = null;
        carCallback = null;
        values[4] = UNKNOWN;
        values[5] = UNKNOWN;
        publish();
    }

    private void stopWatching() {
        if (subscription == null && carConnection == null) return;
        ++generation;
        client = null;
        if (subscription != null) { subscription.close(); subscription = null; }
        IBinder binder = carBinder;
        IBinder callback = carCallback;
        carBinder = null;
        carCallback = null;
        if (carConnection != null) {
            try { unbindService(carConnection); } catch (IllegalArgumentException ignored) { }
            carConnection = null;
        }
        if (binder != null && callback != null)
            submitIo(() -> transactCallback(binder, 47, callback));
        Arrays.fill(values, UNKNOWN);
    }

    private void publish() {
        if (client == null) return;
        Message message = Message.obtain(null, UPDATE);
        message.arg1 = clientSession;
        android.os.Bundle data = new android.os.Bundle();
        data.putIntArray(VALUES, values.clone());
        message.setData(data);
        try { client.send(message); } catch (RemoteException e) { client = null; }
    }

    private static void submitIo(Runnable task) {
        try { IO.execute(task); }
        catch (RejectedExecutionException e) { Log.w(TAG, "Diagnostic OEM queue full", e); }
    }

    private static boolean transactCallback(IBinder binder, int code, IBinder callback) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!binder.transact(code, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "CarSignal callback transaction " + code + " failed", e);
            return false;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static int readCarInt(IBinder binder, int code) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAR_DESCRIPTOR);
            if (!binder.transact(code, data, reply, 0)) return UNKNOWN;
            reply.readException();
            return reply.readInt();
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "CarSignal read " + code + " failed", e);
            return UNKNOWN;
        } finally { data.recycle(); reply.recycle(); }
    }
}
