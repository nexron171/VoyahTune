package ru.big.town.anative;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Signature permission in the manifest protects all location/history reads. */
public final class TripHistoryProvider extends ContentProvider {
    private TripStore store;
    @Override public boolean onCreate() { store = new TripStore(getContext()); return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        java.util.List<String> path = uri.getPathSegments();
        if (path.size() == 1 && "trips".equals(path.get(0))) return store.summariesCursor();
        if (path.size() == 3 && "trips".equals(path.get(0)) && "samples".equals(path.get(2)))
            return store.samples(Long.parseLong(path.get(1)));
        throw new IllegalArgumentException("Unknown trip URI");
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.voyahtune.trip"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
