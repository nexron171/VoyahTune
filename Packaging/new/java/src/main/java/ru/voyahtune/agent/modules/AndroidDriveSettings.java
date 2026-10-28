package ru.voyahtune.agent.modules;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import ru.voyahtune.agent.policy.DriveSelection;

public final class AndroidDriveSettings {
    public static final Uri ENDPOINT = Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");
    private static final Uri INDIVIDUAL = Uri.parse("content://qinggan.settings/global");

    private AndroidDriveSettings() {}

    public static DriveSelection.Targets selected(ContentResolver resolver, DriveSelection.Account account) throws Exception {
        long identity = Binder.clearCallingIdentity();
        try {
            Cursor cursor = resolver.query(ENDPOINT, null, null, null, null);
            return DriveSelection.read(cursor == null ? null : new Row(cursor), account, (key, id) -> {
                try (Cursor individual = resolver.query(INDIVIDUAL, new String[] {"value"}, "name=?", new String[] {key + id}, null)) {
                    if (individual == null) throw new IllegalStateException("Individual settings provider unavailable");
                    return !individual.moveToFirst() || individual.isNull(0) ? null : individual.getString(0);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public static Bundle hook(ContentResolver resolver, String action, Bundle arguments) {
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle response = resolver.call(ENDPOINT, "driveHookV2", action, arguments == null ? new Bundle() : arguments);
            if (response == null || response.getInt("protocol", -1) != 2) throw new IllegalStateException("Drive hook provider unavailable");
            return response;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static final class Row implements DriveSelection.Row {
        private final Cursor cursor;
        Row(Cursor cursor) { this.cursor = cursor; }
        @Override public boolean moveToFirst() { return cursor.moveToFirst(); }
        @Override public int columnCount() { return cursor.getColumnCount(); }
        @Override public boolean isNull(int column) { return cursor.isNull(column); }
        @Override public String string(int column) { return cursor.getString(column); }
        @Override public int integer(int column) { return cursor.getInt(column); }
        @Override public void close() { cursor.close(); }
    }
}
