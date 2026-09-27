package ru.big.town.anative;

import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.util.Collections;

/** Read-only root diagnostics. Reuses the existing OEM transport; no update commands. */
public final class OtaStatusProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != 0) throw new SecurityException("Root diagnostics only");
        if (!"preflight".equals(method) && !"health".equals(method)) throw new IllegalArgumentException("Unknown method");
        Bundle result = new Bundle();
        long identity = Binder.clearCallingIdentity();
        try {
            boolean service = false;
            ActivityManager manager = getContext().getSystemService(ActivityManager.class);
            for (ActivityManager.RunningServiceInfo item : manager.getRunningServices(100)) {
                if (item.service.getClassName().equals(SetModesService.class.getName()) && item.pid == android.os.Process.myPid()) service = true;
            }
            Bundle restore = getContext().getContentResolver().call(
                Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider"), "otaHealth", null, null);
            result.putBoolean("otaReady", service && restore != null && restore.getBoolean("ready"));
            result.putString("version", BuildConfig.VERSION_NAME);
            if ("preflight".equals(method)) {
                Bundle vehicle = OemVehicleStateTransport.withSession(getContext(), Collections.emptyList(), session -> {
                    Bundle snapshot = new Bundle();
                    OemVehicleStateTransport.GearStatus gear = session.readGearStatus();
                    Integer speed = session.readVehicleSpeed();
                    snapshot.putBoolean("parked", gear != null && gear.value == 0);
                    snapshot.putBoolean("stationary", speed != null && speed == 0);
                    snapshot.putString("gear", gear == null ? "unknown" : String.valueOf(gear.value));
                    snapshot.putString("speed", speed == null ? "unknown" : String.valueOf(speed));
                    return snapshot;
                });
                if (vehicle != null) result.putAll(vehicle);
            }
        } catch (Exception e) { result.putBoolean("otaReady", false); result.putString("error", e.toString()); }
        finally { Binder.restoreCallingIdentity(identity); }
        return result;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
