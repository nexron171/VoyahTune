package ru.big.town.restoremode.carplay;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import androidx.core.content.ContextCompat;

import com.shilapi.xcertplay.VoyahCarPlayIntegration;
import com.shilapi.xcertplay.airplay.VehicleClimate;
import com.shilapi.xcertplay.network.VoyahWifiScanPause;

import ru.big.town.common.CarPlayAudioProtocol;
import ru.big.town.common.CarPlayClimateProtocol;
import ru.big.town.common.CarPlayVehicleProtocol;
import ru.big.town.common.CarPlayWifiScanProtocol;
import ru.big.town.restoremode.voice.commands.VoiceSteeringPolicy;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** One subscription per foreground CarPlay session, including UI/display transfers. */
public final class CarPlayVehicleBridge
        implements VoyahCarPlayIntegration.Host, VoyahWifiScanPause.Host, VehicleClimate.Host {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Messenger replies;
    private int generation;
    private int nextClimateRequest;
    private final Map<Integer, ClimateReply> climateReplies = new HashMap<>();

    private static final class ClimateReply {
        final VehicleClimate.Result result;
        final Runnable timeout;

        ClimateReply(VehicleClimate.Result result, Runnable timeout) {
            this.result = result;
            this.timeout = timeout;
        }
    }

    private final Set<Object> wifiScanOwners = Collections.newSetFromMap(new IdentityHashMap<>());

    private Messenger createReplies() {
        int expectedGeneration = ++generation;
        return new Messenger(
                new Handler(
                        Looper.getMainLooper(),
                        message -> {
                            if (!started || expectedGeneration != generation) return true;
                            if (message.what == CarPlayClimateProtocol.RESULT
                                    || message.what
                                            == ru.big.town.common.CarPlayUltraProtocol.RESULT) {
                                Bundle response = message.getData();
                                finishClimate(
                                        response.getInt(CarPlayClimateProtocol.REQUEST),
                                        response.getInt(
                                                CarPlayClimateProtocol.ERROR,
                                                CarPlayClimateProtocol.UNAVAILABLE));
                                return true;
                            }
                            if (message.what != CarPlayVehicleProtocol.STATE) return true;
                            Bundle values = message.getData();
                            Bundle climate = values.getBundle(CarPlayClimateProtocol.STATE);
                            Map<String, Double> observed = new HashMap<>();
                            if (climate != null) {
                                for (String field : climate.keySet()) {
                                    Object value = climate.get(field);
                                    if (value instanceof Double)
                                        observed.put(field, (Double) value);
                                }
                            }
                            VehicleClimate.update(observed);
                            CarPlayUltraSource.update(
                                    values.getBundle(
                                            ru.big.town.common.CarPlayUltraProtocol.STATE));
                            VoyahCarPlayIntegration.update(
                                    values.getInt(CarPlayVehicleProtocol.SPEED_KMH, -1),
                                    values.getInt(CarPlayVehicleProtocol.GEAR, -1),
                                    values.getLong(CarPlayVehicleProtocol.SPEED_TIME, -1),
                                    values.getInt(CarPlayVehicleProtocol.SW_REASON, -1));
                            return true;
                        }));
    }

    private Messenger service;
    private boolean started, bound;
    private boolean ultraEnabled;
    private int audioStream = CarPlayAudioProtocol.NONE;
    private final Runnable retry = this::bind;
    private final Runnable navigationTick =
            new Runnable() {
                @Override
                public void run() {
                    if (!started) return;
                    sendNavigation();
                    handler.postDelayed(this, 1000);
                }
            };

    private void sendNavigation() {
        if (service == null) return;
        com.shilapi.xcertplay.hud.VoyahManeuver maneuver =
                com.shilapi.xcertplay.hud.VoyahNavigation.snapshot();
        Bundle values = new Bundle();
        values.putBoolean(CarPlayWifiScanProtocol.PAUSED, !wifiScanOwners.isEmpty());
        values.putBoolean(CarPlayVehicleProtocol.NAV_ACTIVE, maneuver != null);
        values.putLong(CarPlayVehicleProtocol.NAV_TIME, android.os.SystemClock.elapsedRealtime());
        if (maneuver != null) {
            values.putInt(CarPlayVehicleProtocol.NAV_ICON, maneuver.getIcon());
            values.putInt(CarPlayVehicleProtocol.NAV_DISTANCE, maneuver.getDistanceMeters());
            values.putString(CarPlayVehicleProtocol.NAV_ROAD, maneuver.getRoad());
        }
        Message message = Message.obtain(null, CarPlayVehicleProtocol.NAVIGATION);
        message.replyTo = replies;
        message.setData(values);
        try {
            service.send(message);
        } catch (RemoteException unavailable) {
            reconnect();
        }
    }

    private final ServiceConnection connection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (!started) return;
                    replies = createReplies();
                    service = new Messenger(binder);
                    send(CarPlayVehicleProtocol.WATCH);
                    sendAudioFocus();
                    sendNavigation();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    generation++;
                    service = null;
                    VoyahCarPlayIntegration.reset();
                    resetClimate();
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    reconnect();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    reconnect();
                }
            };
    private final BroadcastReceiver siri =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (started
                            && CarPlayVehicleProtocol.SIRI_BROADCAST.equals(intent.getAction())
                            && context.getSharedPreferences(
                                            "DrivePreferences", Context.MODE_PRIVATE)
                                    .getBoolean(VoiceSteeringPolicy.SIRI_ENABLED, false)) {
                        VoyahCarPlayIntegration.requestSiri();
                    }
                }
            };

    public CarPlayVehicleBridge(Context context) {
        this.context = context.getApplicationContext();
        VehicleClimate.install(this);
        CarPlayUltraSource.install(context, this);
    }

    @Override
    public void write(
            String field, double value, BooleanSupplier current, VehicleClimate.Result result) {
        writeCommand(CarPlayClimateProtocol.SET, field, value, current, result);
    }

    public void writeUltra(
            String field, double value, BooleanSupplier current, VehicleClimate.Result result) {
        writeCommand(ru.big.town.common.CarPlayUltraProtocol.SET, field, value, current, result);
    }

    private void writeCommand(
            int type,
            String field,
            double value,
            BooleanSupplier current,
            VehicleClimate.Result result) {
        handler.post(
                () -> {
                    if (!started || service == null || !current.getAsBoolean()) {
                        result.complete(CarPlayClimateProtocol.UNAVAILABLE);
                        return;
                    }
                    if (climateReplies.size() >= 16) {
                        result.complete(CarPlayClimateProtocol.BUSY);
                        return;
                    }
                    int request = ++nextClimateRequest;
                    Runnable timeout = () -> finishClimate(request, CarPlayClimateProtocol.TIMEOUT);
                    climateReplies.put(request, new ClimateReply(result, timeout));
                    handler.postDelayed(timeout, 3500);
                    Bundle data = new Bundle();
                    data.putInt(CarPlayClimateProtocol.REQUEST, request);
                    data.putString(CarPlayClimateProtocol.FIELD, field);
                    data.putDouble(CarPlayClimateProtocol.VALUE, value);
                    data.putLong(
                            CarPlayClimateProtocol.DEADLINE,
                            android.os.SystemClock.elapsedRealtime() + 3000);
                    Message message = Message.obtain(null, type);
                    message.replyTo = replies;
                    message.setData(data);
                    try {
                        service.send(message);
                    } catch (RemoteException disconnected) {
                        reconnect();
                    }
                });
    }

    private void finishClimate(int request, int error) {
        ClimateReply reply = climateReplies.remove(request);
        if (reply == null) return;
        handler.removeCallbacks(reply.timeout);
        reply.result.complete(error);
    }

    private void resetClimate() {
        VehicleClimate.update(Collections.emptyMap());
        CarPlayUltraSource.update(null);
        for (Integer request : new java.util.ArrayList<>(climateReplies.keySet())) {
            finishClimate(request, CarPlayClimateProtocol.UNAVAILABLE);
        }
    }

    @Override
    public void acquireWifiScanPause(Object owner, BooleanSupplier current) {
        handler.post(
                () -> {
                    if (current.getAsBoolean() && wifiScanOwners.add(owner) && started) {
                        sendNavigation();
                    }
                });
    }

    @Override
    public void releaseWifiScanPause(Object owner) {
        handler.post(
                () -> {
                    if (wifiScanOwners.remove(owner) && started) sendNavigation();
                });
    }

    @Override
    public void audioFocus(int stream) {
        if (stream != CarPlayAudioProtocol.NONE && !CarPlayAudioProtocol.validStream(stream))
            return;
        audioStream = stream;
        sendAudioFocus();
    }

    private void sendAudioFocus() {
        if (!started || service == null) return;
        Message message = Message.obtain(null, CarPlayAudioProtocol.FOCUS);
        message.replyTo = replies;
        Bundle data = new Bundle();
        data.putInt(CarPlayAudioProtocol.STREAM, audioStream);
        message.setData(data);
        try {
            service.send(message);
        } catch (RemoteException unavailable) {
            reconnect();
        }
    }

    @Override
    public boolean openProjection(
            android.app.Activity activity,
            int displayId,
            VoyahCarPlayIntegration.ProjectionResult result) {
        if (displayId <= 1) return false;
        new CarPlayProjectionClient(activity, displayId, result).start();
        return true;
    }

    @Override
    public boolean openDashboard(int displayId) {
        return CarPlayDashboardLauncher.open(context, displayId);
    }

    @Override
    public int physicalDisplayId(int displayId) {
        return CarPlayDashboardLauncher.targetDisplayId(displayId);
    }

    @Override
    public void closeProjection(int displayId) {
        CarPlayProjectionClient.close(context, displayId);
    }

    @Override
    public void start() {
        if (started) return;
        started = true;
        ultraEnabled = com.shilapi.xcertplay.CarPlaySettingsAccess.ultra(context);
        ContextCompat.registerReceiver(
                context,
                siri,
                new IntentFilter(CarPlayVehicleProtocol.SIRI_BROADCAST),
                CarPlayVehicleProtocol.PERMISSION,
                handler,
                ContextCompat.RECEIVER_EXPORTED);
        bind();
        handler.post(navigationTick);
    }

    private void bind() {
        if (!started || bound) return;
        Intent intent =
                new Intent()
                        .setClassName("ru.big.town.anative", "ru.big.town.anative.SetModesService");
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException unavailable) {
            bound = false;
        }
        if (!bound) handler.postDelayed(retry, 5000);
    }

    private void reconnect() {
        generation++;
        service = null;
        VoyahCarPlayIntegration.reset();
        resetClimate();
        if (bound) context.unbindService(connection);
        bound = false;
        handler.removeCallbacks(retry);
        if (started) handler.postDelayed(retry, 5000);
    }

    private void send(int what) {
        if (service == null) return;
        Message message = Message.obtain(null, what);
        if (what == CarPlayVehicleProtocol.WATCH) {
            Bundle data = new Bundle();
            data.putBoolean(ru.big.town.common.CarPlayUltraProtocol.ENABLED, ultraEnabled);
            message.setData(data);
        }
        message.replyTo = replies;
        try {
            service.send(message);
        } catch (RemoteException unavailable) {
            reconnect();
        }
    }

    @Override
    public void stop() {
        if (!started) return;
        started = false;
        wifiScanOwners.clear();
        audioStream = CarPlayAudioProtocol.NONE;
        send(CarPlayVehicleProtocol.UNWATCH);
        generation++;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(navigationTick);
        context.unregisterReceiver(siri);
        if (bound) context.unbindService(connection);
        bound = false;
        service = null;
        VoyahCarPlayIntegration.reset();
        resetClimate();
    }
}
