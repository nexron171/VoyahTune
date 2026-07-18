package ru.big.town.restoremode;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
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
    private  boolean debugMode=false;
    private  boolean wiperColdMode=false;
    private  String customCommandStarButton1="";
    private  String customCommandStarButton2="";
    private  boolean autoLaunchOnWake=false;
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
        debugMode               = sharedPreferences.getBoolean("debugMode",              false);
        wiperColdMode           = sharedPreferences.getBoolean("wiperColdMode",          false);
        customCommandStarButton1 = sharedPreferences.getString("customCommandStarButton1", "");
        customCommandStarButton2 = sharedPreferences.getString("customCommandStarButton2", "");
        autoLaunchOnWake        = sharedPreferences.getBoolean("autoLaunchOnWake",         false);

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
        });
       return cursor;

    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        Log.i("$$$", "UPDATE");
        return 0;
    }
}