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

import ru.big.town.common.CarPlayVehicleProtocol;
import ru.big.town.restoremode.voice.commands.VoiceSteeringPolicy;

/** One subscription per foreground CarPlay session, including UI/display transfers. */
public final class CarPlayVehicleBridge implements VoyahCarPlayIntegration.Host {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Messenger replies;
    private int generation;

    private Messenger createReplies() {
        int expectedGeneration = ++generation;
        return new Messenger(
                new Handler(
                        Looper.getMainLooper(),
                        message -> {
                            if (message.what != CarPlayVehicleProtocol.STATE
                                    || !started
                                    || expectedGeneration != generation) return true;
                            Bundle values = message.getData();
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
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    generation++;
                    service = null;
                    VoyahCarPlayIntegration.reset();
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
    }

    @Override
    public void start() {
        if (started) return;
        started = true;
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
        if (bound) context.unbindService(connection);
        bound = false;
        handler.removeCallbacks(retry);
        if (started) handler.postDelayed(retry, 5000);
    }

    private void send(int what) {
        if (service == null) return;
        Message message = Message.obtain(null, what);
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
        send(CarPlayVehicleProtocol.UNWATCH);
        generation++;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(navigationTick);
        context.unregisterReceiver(siri);
        if (bound) context.unbindService(connection);
        bound = false;
        service = null;
        VoyahCarPlayIntegration.reset();
    }
}
