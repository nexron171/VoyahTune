package ru.big.town.restoremode.trips.location;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import ru.big.town.common.TripProtocol;
import ru.big.town.restoremode.R;

/** GPS acquisition only. Native owns cadence, elapsed time, odometer and persistent samples. */
public class TripLocationTrackingService extends Service implements LocationListener {
    private static final String CHANNEL = "trip_location";
    private LocationManager locations;
    private long tripStart;
    private boolean listening, registered;
    private final BroadcastReceiver state =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (!i.getBooleanExtra("inDrive", false)
                            || !TripLocationPermission.allowed(c)) {
                        stopSelf();
                        return;
                    }
                    tripStart = i.getLongExtra("tripStartWall", 0);
                    listen();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        locations = getSystemService(LocationManager.class);
        if (!TripLocationPermission.allowed(this)) {
            stopSelf();
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL, "Запись маршрута поездки", NotificationManager.IMPORTANCE_LOW));
        try {
            startForeground(
                    18,
                    new NotificationCompat.Builder(this, CHANNEL)
                            .setSmallIcon(R.mipmap.ic_restoremode)
                            .setContentTitle("VoyahTune · запись маршрута")
                            .setContentText("GPS-трек текущей поездки сохраняется на устройстве")
                            .setOngoing(true)
                            .build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (SecurityException denied) {
            stopSelf();
            return;
        }
        ContextCompat.registerReceiver(
                this,
                state,
                new IntentFilter(TripProtocol.UPDATE),
                TripProtocol.PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED);
        registered = true;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (!TripLocationPermission.allowed(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (i != null) {
            tripStart = i.getLongExtra("tripStartWall", 0);
        }
        if (tripStart > 0) {
            listen();
        }
        // A sticky restart confirms the session instead of reusing a stale trip id.
        if (i == null || tripStart == 0) {
            sendBroadcast(
                    new Intent(TripProtocol.REQUEST).setPackage(TripProtocol.NATIVE),
                    TripProtocol.PERMISSION);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private void listen() {
        if (listening || tripStart <= 0 || locations == null) {
            return;
        }
        if (!TripLocationPermission.allowed(this)) {
            stopSelf();
            return;
        }
        try {
            if (locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                locations.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
                listening = true;
            }
        } catch (SecurityException denied) {
            stopSelf();
        } catch (IllegalArgumentException unavailable) {
            Log.w("TripLocation", "GPS provider unavailable", unavailable);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!TripLocationPermission.allowed(this)) {
            TripLocationPermission.sync(this);
            stopSelf();
            return;
        }
        long fix = location.getElapsedRealtimeNanos() / 1_000_000;
        sendBroadcast(
                new Intent(TripProtocol.LOCATION)
                        .setPackage(TripProtocol.NATIVE)
                        .putExtra("tripStartWall", tripStart)
                        .putExtra("lat", location.getLatitude())
                        .putExtra("lon", location.getLongitude())
                        .putExtra(
                                "accuracy",
                                location.hasAccuracy() ? location.getAccuracy() : Float.NaN)
                        .putExtra("elapsed", fix)
                        .putExtra(
                                "mock",
                                location.isFromMockProvider()
                                        || !LocationManager.GPS_PROVIDER.equals(
                                                location.getProvider())),
                TripProtocol.PERMISSION);
    }

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onDestroy() {
        if (locations != null && listening) {
            locations.removeUpdates(this);
        }
        if (registered) {
            unregisterReceiver(state);
        }
        super.onDestroy();
    }
}
