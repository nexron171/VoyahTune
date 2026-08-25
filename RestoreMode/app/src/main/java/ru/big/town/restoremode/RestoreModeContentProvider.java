package ru.big.town.restoremode;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

public class RestoreModeContentProvider extends ContentProvider {
    private SharedPreferences sharedPreferences;
    private String driveMode="INDIVIDUAL";
    private String energy="SREV";
    private  String recycle="LOW";
    private  String customCommand="";
    private  int customCommandCount=1;
    private  boolean autoLight=false;
    private  boolean driveEnabled=false;
    private  boolean recycleEnabled=false;
    private  boolean energyEnabled=false;
    private  int lightSensorThreshold=3;
    private  int lightSensorThresholdOff=5;
    private  boolean disablePedestrianSound=false;
    private  boolean forcedEv=false;
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

    /**
     * Root-only, state-change delivery from {@code /data/local/bin/load.bin}. The CLI never runs on
     * a permanent cadence: load.bin calls it after its bounded status record changes and allows at
     * most three delivery attempts for that revision. SharedPreferences uses Android's AtomicFile
     * implementation, so the diagnostics screen never sees a torn value.
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
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
        driveMode = sharedPreferences.getString("driveMode", "INDIVIDUAL");
        energy = sharedPreferences.getString("energy", "SREV");
        recycle = sharedPreferences.getString("recycle", "LOW");
        customCommand = sharedPreferences.getString("customCommand", "");
        customCommandCount = sharedPreferences.getInt("customCommandCount", 1);
        autoLight = sharedPreferences.getBoolean("autoLight", false);
        driveEnabled          = sharedPreferences.getBoolean("driveEnabled",          false);
        recycleEnabled        = sharedPreferences.getBoolean("recycleEnabled",        false);
        energyEnabled         = sharedPreferences.getBoolean("energyEnabled",         false);
        lightSensorThreshold    = sharedPreferences.getInt("lightSensorThreshold",    3);
        lightSensorThresholdOff = sharedPreferences.getInt("lightSensorThresholdOff", 5);
        disablePedestrianSound  = sharedPreferences.getBoolean("disablePedestrianSound", false);
        forcedEv                = sharedPreferences.getBoolean("forcedEv", false);
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
                ApolloSettings.STOCK_UI,      // 28 — подписка/экзамен в штатном UI
        });

        cursor.addRow(new Object[]{
                driveMode, energy, recycle, customCommand, customCommandCount,
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
        SharedPreferences.Editor e = sharedPreferences.edit();
        int n = 0;
        for (String key : new String[]{"driveMode", "energy", "recycle"}) {
            if (values.containsKey(key)) {
                String v = values.getAsString(key);
                if (v != null && !v.isEmpty()) { e.putString(key, v); n++; Log.i("$$$", "provider UPDATE " + key + "=" + v); }
            }
        }
        for (String key : new String[]{"forcedEv", "disablePedestrianSound"}) {
            if (values.containsKey(key)) {
                Boolean v = values.getAsBoolean(key);
                if (v != null) { e.putBoolean(key, v); n++; Log.i("$$$", "provider UPDATE " + key + "=" + v); }
            }
        }
        if (n > 0) e.apply();
        return n;
    }
}
