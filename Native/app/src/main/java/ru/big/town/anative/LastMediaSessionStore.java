package ru.big.town.anative;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.session.PlaybackState;

import java.util.List;

final class LastMediaSessionStore {
    private static final String PREFERENCES = "last_media_session";
    private static final String PACKAGE = "package";

    private LastMediaSessionStore() {}

    static void remember(Context context, String packageName, int state) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        remember(preferences, packageName, state);
    }

    static void remember(SharedPreferences preferences, String packageName, int state) {
        if (state != PlaybackState.STATE_PLAYING || packageName.isEmpty()) return;
        if (!packageName.equals(preferences.getString(PACKAGE, ""))) {
            preferences.edit().putString(PACKAGE, packageName).apply();
        }
    }

    static String read(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PACKAGE, "");
    }

    static int choose(
            List<MediaControlPolicy.Candidate> sessions, int selected, String lastPackage) {
        if (selected >= 0
                && selected < sessions.size()
                && sessions.get(selected).playbackClass == MediaControlPolicy.STATE_ACTIVE)
            return selected;
        if (lastPackage.isEmpty()) return selected;
        if (selected >= 0
                && selected < sessions.size()
                && lastPackage.equals(sessions.get(selected).packageName)) return selected;
        for (int index = 0; index < sessions.size(); index++) {
            if (lastPackage.equals(sessions.get(index).packageName)) return index;
        }
        // Let Android address its remembered receiver instead of an unrelated paused session.
        return -1;
    }
}
