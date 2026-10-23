package ru.big.town.anative;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import android.media.session.PlaybackState;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LastMediaSessionStoreTest {
    @Test
    public void remembersPlaybackWithoutReplacingItWithAPausedSession() {
        Map<String, String> data = new HashMap<>();
        SharedPreferences preferences = preferences(data);
        LastMediaSessionStore.remember(preferences, "music", PlaybackState.STATE_PLAYING);
        LastMediaSessionStore.remember(preferences, "radio", PlaybackState.STATE_PAUSED);
        assertEquals("music", data.get("package"));
        LastMediaSessionStore.remember(preferences, "", PlaybackState.STATE_PLAYING);
        assertEquals("music", data.get("package"));
    }

    @Test
    public void resumeFindsLastPlayerInsteadOfFirstPausedSession() {
        List<MediaControlPolicy.Candidate> sessions =
                Arrays.asList(session("radio", false), session("music", false));
        assertEquals(1, LastMediaSessionStore.choose(sessions, 0, "music"));
        assertEquals(-1, LastMediaSessionStore.choose(sessions, 0, "gone-player"));
        assertEquals(0, LastMediaSessionStore.choose(sessions, 0, ""));
    }

    @Test
    public void alreadyPlayingSessionWinsSoDoorDoesNotStartAnotherSource() {
        List<MediaControlPolicy.Candidate> sessions =
                Arrays.asList(session("radio", true), session("music", false));
        assertEquals(0, LastMediaSessionStore.choose(sessions, 0, "music"));
    }

    private MediaControlPolicy.Candidate session(String packageName, boolean active) {
        return new MediaControlPolicy.Candidate(
                packageName,
                packageName,
                active ? MediaControlPolicy.STATE_ACTIVE : MediaControlPolicy.STATE_INACTIVE,
                false,
                false,
                false);
    }

    private SharedPreferences preferences(Map<String, String> data) {
        Object[] editor = new Object[1];
        editor[0] =
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {SharedPreferences.Editor.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("putString")) {
                                data.put((String) args[0], (String) args[1]);
                                return editor[0];
                            }
                            return null;
                        });
        return (SharedPreferences)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {SharedPreferences.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("edit")) return editor[0];
                            if (method.getName().equals("getString"))
                                return data.getOrDefault((String) args[0], (String) args[1]);
                            return null;
                        });
    }
}
