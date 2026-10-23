package ru.big.town.anative;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import ru.big.town.common.CarPlayVehicleProtocol;

/** Narrow input for hooks running with the navigator's original Android UID. */
public final class ClusterNavigationProvider extends ContentProvider {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        // ContentProvider verifies the calling package against the Binder UID.
        ClusterNavigationSources.Source source =
                ClusterNavigationSources.sourceForPackage(getCallingPackage());
        if (source == null) {
            throw new SecurityException("Navigation source is not allowed");
        }
        if (!"maneuver".equals(method) || extras == null) {
            throw new IllegalArgumentException("Expected a maneuver snapshot");
        }
        Bundle snapshot = new Bundle();
        snapshot.putBoolean(
                CarPlayVehicleProtocol.NAV_ACTIVE,
                extras.getBoolean(CarPlayVehicleProtocol.NAV_ACTIVE));
        snapshot.putInt(
                CarPlayVehicleProtocol.NAV_ICON,
                extras.getInt(CarPlayVehicleProtocol.NAV_ICON, -1));
        snapshot.putInt(
                CarPlayVehicleProtocol.NAV_DISTANCE,
                extras.getInt(CarPlayVehicleProtocol.NAV_DISTANCE, -1));
        String road = extras.getString(CarPlayVehicleProtocol.NAV_ROAD, "");
        snapshot.putString(
                CarPlayVehicleProtocol.NAV_ROAD,
                road == null ? "" : road.substring(0, Math.min(120, road.length())));
        snapshot.putLong(
                CarPlayVehicleProtocol.NAV_TIME,
                extras.getLong(CarPlayVehicleProtocol.NAV_TIME, -1));
        main.post(() -> ClusterNavigationController.get(getContext()).update(source, snapshot));
        return Bundle.EMPTY;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
