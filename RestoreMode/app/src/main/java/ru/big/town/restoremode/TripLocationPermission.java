package ru.big.town.restoremode;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import ru.big.town.common.TripProtocol;

/** Permission belongs to RestoreMode, the actual GPS reader. Native never bypasses refusal. */
final class TripLocationPermission {
    private static final int FOREGROUND_REQUEST = 7610, BACKGROUND_REQUEST = 7611;
    private TripLocationPermission() { }
    static boolean fine(Context c) { return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    static boolean background(Context c) { return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("TripLocationPermission", Context.MODE_PRIVATE); }
    static boolean allowed(Context c) {
        boolean permissions = fine(c) && background(c);
        // Installer -g grants Android permissions automatically. Human consent is independent.
        if (!permissions && prefs(c).getBoolean("enabled", false)) prefs(c).edit().putBoolean("enabled", false).apply();
        return permissions && prefs(c).getBoolean("enabled", false);
    }
    static void disable(Context c) {
        prefs(c).edit().putBoolean("enabled", false).putBoolean("pendingSettings", false).apply();
        sync(c); if (c instanceof Activity) refresh((Activity)c);
    }
    static AlertDialog atStartup(Activity a) {
        android.content.SharedPreferences p = prefs(a);
        if (p.getBoolean("asked", false)) return null;
        p.edit().putBoolean("asked", true).apply();
        return new MaterialAlertDialogBuilder(a, R.style.DarkDialog).setTitle("Записывать маршрут поездки?")
                .setMessage("VoyahTune сохраняет GPS-трек в истории поездок. Для записи при открытой навигации, свёрнутом VoyahTune и после перезапуска служб нужна точная геолокация с доступом «Разрешать всегда». Данные сохраняются на устройстве. Без разрешения время, пробег и расход продолжат учитываться, а трек записываться не будет.")
                .setPositiveButton("Разрешить", (d, w) -> request(a))
                .setNegativeButton("Без трека", (d, w) -> disable(a)).show();
    }
    static void request(Activity a) {
        if (!fine(a)) {
            boolean asked = prefs(a).getBoolean("foregroundAsked", false);
            prefs(a).edit().putBoolean("foregroundAsked", true).apply();
            if (asked && !a.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) openSettings(a);
            else a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, FOREGROUND_REQUEST);
        }
        else if (!background(a)) explainBackground(a);
        else { prefs(a).edit().putBoolean("enabled", true).apply(); refresh(a); sync(a); }
    }
    static void explainBackground(Activity a) {
        CharSequence option = a.getPackageManager().getBackgroundPermissionOptionLabel();
        new MaterialAlertDialogBuilder(a, R.style.DarkDialog).setTitle("Геолокация в фоне")
                .setMessage("Чтобы маршрут продолжал записываться, пока открыта навигация или другое приложение, разрешите геолокацию в фоне. На следующем экране выберите «" + option + "». Отказ отключит запись трека; остальная статистика поездки сохранится.")
                .setPositiveButton("Открыть разрешение", (d, w) -> {
                    boolean asked = prefs(a).getBoolean("backgroundAsked", false);
                    prefs(a).edit().putBoolean("backgroundAsked", true).apply();
                    if (asked) openSettings(a);
                    else a.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND_REQUEST);
                })
                .setNegativeButton("Без трека", (d, w) -> disable(a)).show();
    }
    static boolean result(Activity a, int request) {
        if (request != FOREGROUND_REQUEST && request != BACKGROUND_REQUEST) return false;
        if (request == FOREGROUND_REQUEST && fine(a) && !background(a)) explainBackground(a);
        else prefs(a).edit().putBoolean("enabled", fine(a) && background(a)).apply();
        refresh(a); sync(a); return true;
    }
    static void refresh(Activity a) {
        if (prefs(a).getBoolean("pendingSettings", false)) {
            prefs(a).edit().putBoolean("enabled", fine(a) && background(a)).putBoolean("pendingSettings", false).apply();
        }
        TextView status = a.findViewById(R.id.tripLocationPermissionStatus);
        if (status != null) status.setText("Точная геолокация: " + (fine(a) ? "разрешена" : "не разрешена")
                + "\nЗапись в фоне всегда: " + (background(a) ? "разрешена" : "не разрешена")
                + "\nЗапись трека: " + (allowed(a) ? "разрешена" : "отключена"));
    }
    static void sync(Context c) {
        c.sendBroadcast(new Intent(TripProtocol.LOCATION_CHANGED).setPackage(TripProtocol.NATIVE), TripProtocol.PERMISSION);
        if (!allowed(c)) c.stopService(new Intent(c, TripLocationService.class));
    }
    static void openSettings(Activity a) {
        // Inspecting already granted Android permissions must not undo a recording refusal.
        prefs(a).edit().putBoolean("pendingSettings", !fine(a) || !background(a)).apply();
        // OEM per-permission screen; AOSP protects it, so retain the public app-settings fallback.
        Intent permission = new Intent("android.intent.action.MANAGE_APP_PERMISSION")
                .putExtra("android.intent.extra.PACKAGE_NAME", a.getPackageName())
                .putExtra("android.intent.extra.PERMISSION_NAME", Manifest.permission.ACCESS_FINE_LOCATION);
        try { a.startActivity(permission); }
        catch (RuntimeException unavailable) {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
        }
    }
}
