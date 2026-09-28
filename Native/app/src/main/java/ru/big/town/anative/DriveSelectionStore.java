package ru.big.town.anative;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import ru.big.town.common.DriveSelectionPolicy;

/** One persistent restore target shared by the widget, Native and the ACC hook. */
final class DriveSelectionStore {
    private static final Uri URI = Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");
    static void applyConfigured(Context context) {
        try (Cursor c = context.getContentResolver().query(URI, null, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getInt(6) == 1
                    && c.getColumnIndex(DriveSelectionPolicy.CONFIGURED) >= 0) {
                record(context, c.getString(c.getColumnIndex(DriveSelectionPolicy.CONFIGURED)),
                        DriveSelectionPolicy.SETTINGS);
            }
        } catch (RuntimeException e) { Log.w("DriveSelection", "Configured selection unavailable", e); }
    }
    static DriveSelectionPolicy read(Context context) {
        try (Cursor c = context.getContentResolver().query(URI, null, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getColumnIndex(DriveSelectionPolicy.CONFIGURED) >= 0) {
                return new DriveSelectionPolicy(c.getString(c.getColumnIndex(DriveSelectionPolicy.CONFIGURED)),
                        c.getString(c.getColumnIndex(DriveSelectionPolicy.OVERRIDE)),
                        c.getString(c.getColumnIndex(DriveSelectionPolicy.MEDIUM)),
                        c.getColumnIndex(DriveSelectionPolicy.CURRENT) < 0 ? ""
                                : c.getString(c.getColumnIndex(DriveSelectionPolicy.CURRENT)));
            }
        } catch (RuntimeException e) { Log.w("DriveSelection", "Read failed", e); }
        return null;
    }

    static boolean record(Context context, String mode, String source) {
        if (context == null || !DriveSelectionPolicy.valid(mode)) return false;
        try {
            // Older providers do not support overrides: don't silently send a widget command
            // that will be undone at Drive. Both APKs must support this contract.
            if (read(context) == null) return false;
            ContentValues values = new ContentValues();
            values.put(DriveSelectionPolicy.SOURCE, source);
            values.put(DriveSelectionPolicy.MODE, mode);
            int changed = context.getContentResolver().update(URI, values, null, null);
            if (changed == 0 && !DriveSelectionPolicy.FEEDBACK.equals(source)) return false;
            DriveSelectionPolicy state = read(context);
            if (state == null) return false;
            MainActivity.driveMode = state.effective();
            ApplyEngine.noteSavedMode("driveMode", state.effective());
            context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE).edit()
                    .putString("cacheDriveMode", state.effective()).apply();
            if (DriveSelectionPolicy.WIDGET.equals(source) || DriveSelectionPolicy.EXPLICIT.equals(source)) {
                ApplyEngine.driveSelectionSaved();
            }
            Intent update = new Intent("ru.big.town.anative.MODE_SYNCED");
            update.setPackage("ru.big.town.restoremode");
            update.putExtra("modeKey", "driveMode");
            update.putExtra("mode", state.configured);
            context.sendBroadcast(update);
            Log.i("DriveSelection", source + " mode=" + mode + " restore=" + state.effective()
                    + " widget=" + state.override + " medium=" + state.medium);
            return true;
        } catch (RuntimeException e) { Log.w("DriveSelection", "Save failed", e); return false; }
    }
}
