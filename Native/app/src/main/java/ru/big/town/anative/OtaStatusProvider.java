package ru.big.town.anative;

import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.Collections;

/** Read-only health checks; vehicle preflight remains restricted to root. */
public final class OtaStatusProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private boolean trustedDiagnosticsCaller(int uid) {
        Context context = getContext();
        try {
            PackageManager packages = context.getPackageManager();
            return packages.getPackageUid("ru.big.town.restoremode", 0) == uid
                    && packages.checkSignatures("ru.big.town.restoremode", context.getPackageName())
                    == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        boolean diagnostics = "diagnostics".equals(method);
        int callerUid = Binder.getCallingUid();
        if (callerUid != 0 && !(diagnostics && trustedDiagnosticsCaller(callerUid))) {
            throw new SecurityException("Root or signed diagnostics caller required");
        }
        if (!diagnostics && !"preflight".equals(method) && !"health".equals(method)) {
            throw new IllegalArgumentException("Unknown method");
        }
        Bundle result = new Bundle();
        long identity = Binder.clearCallingIdentity();
        try {
            boolean service = false;
            ActivityManager manager = getContext().getSystemService(ActivityManager.class);
            for (ActivityManager.RunningServiceInfo item : manager.getRunningServices(100)) {
                if (item.service.getClassName().equals(SetModesService.class.getName())
                        && item.pid == Process.myPid()) {
                    service = true;
                }
            }
            Bundle restore = getContext().getContentResolver().call(
                    Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider"),
                    "otaHealth", null, null);
            boolean settingsReady = restore != null && restore.getBoolean("ready");
            result.putBoolean("serviceReady", service);
            result.putBoolean("settingsReady", settingsReady);
            result.putBoolean("otaReady", service && settingsReady);
            result.putInt("pid", Process.myPid());
            result.putString("version", BuildConfig.VERSION_NAME);
            if ("preflight".equals(method)) {
                Bundle vehicle = OemVehicleStateTransport.withSession(
                        getContext(), Collections.emptyList(), session -> {
                            Bundle snapshot = new Bundle();
                            OemVehicleStateTransport.GearStatus gear = session.readGearStatus();
                            Integer speed = session.readVehicleSpeed();
                            snapshot.putBoolean("parked", gear != null && gear.value == 0);
                            snapshot.putBoolean("stationary", speed != null && speed == 0);
                            snapshot.putString(
                                    "gear", gear == null ? "unknown" : String.valueOf(gear.value));
                            snapshot.putString(
                                    "speed", speed == null ? "unknown" : String.valueOf(speed));
                            return snapshot;
                        });
                if (vehicle != null)
                    result.putAll(vehicle);
            }
        } catch (Exception exception) {
            result.putBoolean("otaReady", false);
            result.putString("error", exception.toString());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return result;
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] args, String order) {
        return null;
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
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
