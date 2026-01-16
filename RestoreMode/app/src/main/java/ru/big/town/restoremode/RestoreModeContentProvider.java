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



        MatrixCursor cursor = new MatrixCursor(new String[]{
                "driveMode",
                "energy",
                "recycle",
                "customCommand",
                "customCommandCount",

        });

        cursor.addRow(new Object[]{driveMode,energy,recycle,customCommand,customCommandCount});
       return cursor;

    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        Log.i("$$$", "UPDATE");
        return 0;
    }
}