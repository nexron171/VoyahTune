package ru.big.town.restoremode;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
import ru.big.town.common.DriveSelectionPolicy;

public class RestoreModeContentProvider extends ContentProvider {
    private SharedPreferences sharedPreferences;
    private String energy="SREV";
    private  String recycle="LOW";
    private  String customCommand="";
    private  int customCommandCount=1;
    private  boolean autoLight=false;
    private  boolean driveEnabled=false;
    private  boolean recycleEnabled=false;
    private  boolean energyEnabled=false;
    private boolean driveRememberLast=true;
    private boolean energyRememberLast=true;
    private boolean recycleRememberLast=true;
    private  int lightSensorThreshold=3;
    private  int lightSensorThresholdOff=5;
    private  boolean disablePedestrianSound=false;
    private  boolean forcedEv=false;
    private boolean suspensionMaintenance=false;
    private  boolean debugMode=false;
    private  boolean wiperColdMode=false;
    private  String customCommandStarButton1="";
    private  String customCommandStarButton2="";
    private  boolean autoLaunchOnWake=false;
    private  boolean batteryHeatAuto=false;
    private  boolean pauseMediaOnDoor=false;
    private  boolean fragranceEnabled=FragranceSettings.DEFAULT_ENABLED;
    private  int fragranceTaste=FragranceSettings.DEFAULT_TASTE;
    private  int fragranceDuration=FragranceSettings.DEFAULT_DURATION;
    private  int fragranceIntensity=FragranceSettings.DEFAULT_INTENSITY;
    private boolean apolloTlcEnabled=ApolloSettings.DEFAULT_ENABLED;
    private boolean apolloTrafficLightsEnabled=ApolloSettings.DEFAULT_ENABLED;
    private boolean apolloGreenSoundEnabled=ApolloSettings.DEFAULT_ENABLED;
    private boolean apolloTrafficSignsEnabled=ApolloSettings.DEFAULT_ENABLED;
    private boolean apolloStockUiEnabled=ApolloSettings.DEFAULT_ENABLED;
    public RestoreModeContentProvider() {
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/users";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // TODO: Implement this to handle requests to insert a new row.
        //throw new UnsupportedOperationException("Not yet implemented");
        return null;
    }

    @Override
    public boolean onCreate() {
        sharedPreferences = getContext().getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        return true;
    }

    private void notifySavedMode(String key, String mode) {
        // Hooks write directly to this provider, bypassing Native's MODE_SYNCED notification.
        try {
            getContext().sendBroadcast(new Intent("ru.big.town.anative.MODE_SYNCED")
                    .setPackage(getContext().getPackageName())
                    .putExtra("modeKey", key).putExtra("mode", mode));
        } catch (RuntimeException e) {
            Log.w("DriveSelection", "Selection saved, UI notification unavailable", e);
        }
    }

    /**
     * Root-only, state-change delivery from {@code /data/local/bin/load.bin}. The CLI never runs on
     * a permanent cadence: load.bin calls it after its bounded status record changes and allows at
     * most three delivery attempts for that revision. SharedPreferences uses Android's AtomicFile
     * implementation, so the diagnostics screen never sees a torn value.
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("driveHookV2".equals(method)) {
            int uid = Binder.getCallingUid();
            if (uid != 0 && uid != android.os.Process.SYSTEM_UID && uid != android.os.Process.myUid()) {
                getContext().enforceCallingOrSelfPermission(
                        "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE", "Drive hook state");
            }
            int boot = android.provider.Settings.Global.getInt(getContext().getContentResolver(), "boot_count", -1);
            Bundle result = DriveSelectionPreferences.hook(sharedPreferences,
                    "dispatchSettings".equals(arg) ? "snapshot" : arg, extras, boot);
            boolean dispatched = false;
            if ("dispatchSettings".equals(arg) && result.getInt("acc", -1) == 2
                    && "pending".equals(result.getString("settingsStartup"))) {
                try {
                    dispatched = getContext().startForegroundService(new Intent()
                            .setClassName("ru.big.town.anative",
                                    "ru.big.town.anative.SetModesService")
                            .setAction("ru.big.town.anative.ACC_RESTORE")) != null;
                } catch (RuntimeException e) {
                    Log.w("DriveSelection", "ACC settings dispatch unavailable", e);
                }
            }
            if ("dispatchSettings".equals(arg)) result.putBoolean("settingsDispatched", dispatched);
            if ("user".equals(arg) && extras != null) {
                if (extras.containsKey("mode")) notifySavedMode("driveMode",
                        DriveSelectionPreferences.read(sharedPreferences).configured);
                if (extras.containsKey("energy")) notifySavedMode("energy",
                        sharedPreferences.getString("energy", "SREV"));
            }
            return result;
        }
        if ("otaHealth".equals(method)) {
            int caller = Binder.getCallingUid();
            try {
                int nativeUid = getContext().getPackageManager().getApplicationInfo("ru.big.town.anative", 0).uid;
                if (caller != 0 && (caller != nativeUid || getContext().getPackageManager().checkSignatures(
                        "ru.big.town.anative", getContext().getPackageName()) != android.content.pm.PackageManager.SIGNATURE_MATCH)) {
                    throw new SecurityException("Root or trusted Native only");
                }
            } catch (android.content.pm.PackageManager.NameNotFoundException e) { throw new SecurityException(e); }
            Bundle health = new Bundle();
            health.putBoolean("ready", sharedPreferences != null);
            health.putString("version", BuildConfig.VERSION_NAME);
            return health;
        }
        if (!HookStatusContract.METHOD_PUBLISH.equals(method)) {
            return super.call(method, arg, extras);
        }
        if (Binder.getCallingUid() != 0) {
            throw new SecurityException("Hook status may only be published by the root loader");
        }
        Bundle result = new Bundle();
        if (!HookStatusContract.isValidPayload(arg) || getContext() == null) {
            result.putBoolean("stored", false);
            return result;
        }
        boolean stored = getContext()
                .getSharedPreferences(HookStatusContract.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(HookStatusContract.PAYLOAD_KEY, arg)
                .commit();
        result.putBoolean("stored", stored);
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Log.i("$$$", "QUERY1");
        DriveSelectionPolicy driveSelection = DriveSelectionPreferences.read(sharedPreferences);
        energy = DriveSelectionPreferences.energy(sharedPreferences);
        recycle = sharedPreferences.getString("recycle", "LOW");
        customCommand = sharedPreferences.getString("customCommand", "");
        customCommandCount = sharedPreferences.getInt("customCommandCount", 1);
        autoLight = sharedPreferences.getBoolean("autoLight", false);
        driveEnabled          = sharedPreferences.getBoolean("driveEnabled",          false);
        recycleEnabled        = sharedPreferences.getBoolean("recycleEnabled",        false);
        energyEnabled         = sharedPreferences.getBoolean("energyEnabled",         false);
        // Opt-out setting: upgrades without the key keep the historical remember-last behaviour.
        driveRememberLast     = sharedPreferences.getBoolean("driveRememberLast",     true);
        energyRememberLast    = sharedPreferences.getBoolean("energyRememberLast",    true);
        recycleRememberLast   = sharedPreferences.getBoolean("recycleRememberLast",   true);
        lightSensorThreshold    = sharedPreferences.getInt("lightSensorThreshold",    3);
        lightSensorThresholdOff = sharedPreferences.getInt("lightSensorThresholdOff", 5);
        disablePedestrianSound  = sharedPreferences.getBoolean("disablePedestrianSound", false);
        forcedEv                = sharedPreferences.getBoolean("forcedEv", false);
        suspensionMaintenance = sharedPreferences.getBoolean("suspensionMaintenance", false);
        debugMode               = sharedPreferences.getBoolean("debugMode",              false);
        wiperColdMode           = sharedPreferences.getBoolean("wiperColdMode",          false);
        customCommandStarButton1 = sharedPreferences.getString("customCommandStarButton1", "");
        customCommandStarButton2 = sharedPreferences.getString("customCommandStarButton2", "");
        autoLaunchOnWake        = sharedPreferences.getBoolean("autoLaunchOnWake",         false);
        batteryHeatAuto         = sharedPreferences.getBoolean("batteryHeatAuto",          false);
        pauseMediaOnDoor        = sharedPreferences.getBoolean("pauseMediaOnDoor",         false);
        fragranceEnabled        = sharedPreferences.getBoolean(
                FragranceSettings.ENABLED, FragranceSettings.DEFAULT_ENABLED);
        fragranceTaste          = FragranceSettings.normalizeTaste(sharedPreferences.getInt(
                FragranceSettings.TASTE, FragranceSettings.DEFAULT_TASTE));
        fragranceDuration       = FragranceSettings.normalizeDuration(sharedPreferences.getInt(
                FragranceSettings.DURATION, FragranceSettings.DEFAULT_DURATION));
        fragranceIntensity      = FragranceSettings.normalizeIntensity(sharedPreferences.getInt(
                FragranceSettings.INTENSITY, FragranceSettings.DEFAULT_INTENSITY));
        apolloTlcEnabled        = sharedPreferences.getBoolean(
                ApolloSettings.TLC, ApolloSettings.DEFAULT_ENABLED);
        apolloTrafficLightsEnabled = sharedPreferences.getBoolean(
                ApolloSettings.TRAFFIC_LIGHTS, ApolloSettings.DEFAULT_ENABLED);
        apolloGreenSoundEnabled = sharedPreferences.getBoolean(
                ApolloSettings.GREEN_SOUND, ApolloSettings.DEFAULT_ENABLED);
        apolloTrafficSignsEnabled = sharedPreferences.getBoolean(
                ApolloSettings.TRAFFIC_SIGNS, ApolloSettings.DEFAULT_ENABLED);
        apolloStockUiEnabled = sharedPreferences.getBoolean(
                ApolloSettings.STOCK_UI, ApolloSettings.DEFAULT_ENABLED);

        MatrixCursor cursor = new MatrixCursor(new String[]{
                "driveMode",               // 0
                "energy",                  // 1
                "recycle",                 // 2
                "customCommand",           // 3
                "customCommandCount",      // 4
                "autoLight",               // 5
                "driveEnabled",            // 6
                "recycleEnabled",          // 7
                "energyEnabled",           // 8
                "lightSensorThreshold",    // 9
                "lightSensorThresholdOff", // 10
                "disablePedestrianSound",  // 11
                "debugMode",               // 12
                "wiperColdMode",           // 13
                "customCommandStarButton1",// 14
                "customCommandStarButton2",// 15
                "autoLaunchOnWake",        // 16
                "batteryHeatAuto",         // 17
                "pauseMediaOnDoor",        // 18
                "forcedEv",                // 19 — форсированный электрорежим
                FragranceSettings.ENABLED,  // 20 — opt-in восстановление ароматизатора
                FragranceSettings.TASTE,    // 21 — 1..3
                FragranceSettings.DURATION, // 22 — 0=без таймера, 1=30 мин, 2=60 мин
                FragranceSettings.INTENSITY,// 23 — 1=низкая, 2=средняя, 3=высокая
                ApolloSettings.TLC,          // 24 — желаемое состояние TLC
                ApolloSettings.TRAFFIC_LIGHTS, // 25 — распознавание светофоров
                ApolloSettings.GREEN_SOUND, // 26 — звук зелёного сигнала
                ApolloSettings.TRAFFIC_SIGNS,// 27 — распознавание дорожных знаков
                ApolloSettings.STOCK_UI,      // 28 — эмуляция подписки/экзамена для штатного UI
                "driveRememberLast",        // 29 — null/нет колонки трактуется Native как true
                "energyRememberLast",       // 30 — null/нет колонки трактуется Native как true
                "recycleRememberLast",      // 31 — null/нет колонки трактуется Native как true
                "suspensionMaintenance",    // 32 — сервисный режим подвески
                DriveSelectionPolicy.OVERRIDE, // 33
                DriveSelectionPolicy.MEDIUM,   // 34
                DriveSelectionPolicy.CONFIGURED, // 35
                DriveSelectionPolicy.CURRENT, // 36
        });

        cursor.addRow(new Object[]{
                driveSelection.effective(), energy, recycle, customCommand, customCommandCount,
                autoLight ? 1 : 0,
                driveEnabled   ? 1 : 0,
                recycleEnabled ? 1 : 0,
                energyEnabled  ? 1 : 0,
                lightSensorThreshold,
                lightSensorThresholdOff,
                disablePedestrianSound ? 1 : 0,
                debugMode ? 1 : 0,
                wiperColdMode ? 1 : 0,
                customCommandStarButton1,
                customCommandStarButton2,
                autoLaunchOnWake ? 1 : 0,
                batteryHeatAuto ? 1 : 0,
                pauseMediaOnDoor ? 1 : 0,
                forcedEv ? 1 : 0,
                fragranceEnabled ? 1 : 0,
                fragranceTaste,
                fragranceDuration,
                fragranceIntensity,
                apolloTlcEnabled ? 1 : 0,
                apolloTrafficLightsEnabled ? 1 : 0,
                apolloGreenSoundEnabled ? 1 : 0,
                apolloTrafficSignsEnabled ? 1 : 0,
                apolloStockUiEnabled ? 1 : 0,
                driveRememberLast ? 1 : 0,
                energyRememberLast ? 1 : 0,
                recycleRememberLast ? 1 : 0,
                suspensionMaintenance ? 1 : 0,
                driveSelection.override, driveSelection.medium, driveSelection.configured, driveSelection.current,
        });
       return cursor;

    }

    /**
     * Разрешаем записывать режимы и бинарные настройки, доступные с кнопок руля, в prefs DrivePreferences — тот же
     * источник истины, что читают query() и UI VoyahTune, и что восстанавливает Native на пробуждении.
     * Нужно, чтобы смена режима кнопкой руля (и внешняя смена) синхронизировала «последний активированный»
     * режим сюда → он переживёт пробуждение и отразится в настройках. Пишет Native (см.
     * MainActivity.persistSavedMode). Прочие ключи игнорируем (провайдер остаётся почти read-only).
     */
    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        if (values == null || sharedPreferences == null) return 0;
        if (values.containsKey(DriveSelectionPolicy.SOURCE)) {
            if (Binder.getCallingUid() != 0) getContext().enforceCallingOrSelfPermission(
                    "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE", "Drive selection update");
            return DriveSelectionPreferences.select(sharedPreferences,
                    values.getAsString(DriveSelectionPolicy.MODE),
                    values.getAsString(DriveSelectionPolicy.SOURCE)) ? 1 : 0;
        }
        if (values.containsKey("energySelection")) {
            if (Binder.getCallingUid() != 0) getContext().enforceCallingOrSelfPermission(
                    "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE", "Energy selection update");
            return DriveSelectionPreferences.selectEnergy(sharedPreferences, values.getAsString("energySelection"), false) ? 1 : 0;
        }
        SharedPreferences.Editor e = sharedPreferences.edit();
        int n = 0;
        for (String key : new String[]{"driveMode", "energy", "recycle"}) {
            String rememberKey = "driveMode".equals(key) ? "driveRememberLast"
                    : "energy".equals(key) ? "energyRememberLast" : "recycleRememberLast";
            if (values.containsKey(key) && sharedPreferences.getBoolean(rememberKey, true)) {
                String v = values.getAsString(key);
                if (v != null && !v.isEmpty()) { e.putString(key, v); n++; Log.i("$$$", "provider UPDATE " + key + "=" + v); }
            }
        }
        for (String key : new String[]{"forcedEv", "disablePedestrianSound", "suspensionMaintenance"}) {
            if (values.containsKey(key)) {
                Boolean v = values.getAsBoolean(key);
                if (v != null) { e.putBoolean(key, v); n++; Log.i("$$$", "provider UPDATE " + key + "=" + v); }
            }
        }
        if (n > 0) e.apply();
        return n;
    }
}
