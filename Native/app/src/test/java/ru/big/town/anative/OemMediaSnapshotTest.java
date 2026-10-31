package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class OemMediaSnapshotTest {
    @Test
    public void wakeRestoresLastPlayerBeforeItsSessionExists() {
        OemMediaBrowserService.Snapshot restored = OemMediaBrowserService.Snapshot.empty()
                .restoreSource("ru.yandex.music", "Яндекс Музыка");
        assertEquals("ru.yandex.music", restored.packageName);
        assertEquals("Яндекс Музыка", restored.appLabel);
        assertTrue(restored.rememberedSource);
        assertEquals(2, restored.state);
        assertEquals("", restored.title);
        assertFalse(restored.hasArt);
    }

    @Test
    public void idleOemSessionDoesNotReplaceRememberedPlayer() {
        OemMediaBrowserService.Snapshot idle = snapshot("com.qinggan.media", "Old", "", 2, 0, true, 1);
        OemMediaBrowserService.Snapshot restored = idle.restoreSource("ru.yandex.music", "Music");
        assertEquals("ru.yandex.music", restored.packageName);
        assertEquals("", restored.title);
        assertFalse(restored.hasArt);
    }

    @Test
    public void livePlaybackWinsAndSavedPlayerKeepsItsPausedMetadata() {
        for (int state : new int[] {3, 4, 5, 6, 8, 9, 10, 11}) {
            OemMediaBrowserService.Snapshot live = snapshot("com.android.bluetooth", "Track", "", state, 0, false, 0);
            assertSame(live, live.restoreSource("ru.yandex.music", "Music"));
        }
        OemMediaBrowserService.Snapshot paused = snapshot("ru.yandex.music", "Track", "", 2, 40, true, 1);
        assertSame(paused, paused.restoreSource("ru.yandex.music", "Music"));
    }

    @Test
    public void absentOrOemSavedPlayerDoesNotCreateAnExternalSource() {
        OemMediaBrowserService.Snapshot empty = OemMediaBrowserService.Snapshot.empty();
        for (String packageName : new String[] {"", "android", "com.android.bluetooth", "com.qinggan.media", "com.pateo.rdsapp", "ru.big.town.anative"}) {
            assertSame(empty, empty.restoreSource(packageName, "Player"));
        }
    }

    @Test
    public void progressAndPauseDoNotRepublishMetadata() {
        OemMediaBrowserService.Snapshot playing = snapshot("player", "Track", "Album", 3, 10, true, 1);
        assertTrue(playing.sameMetadata(snapshot("player", "Track", "Album", 3, 20, true, 1)));
        assertTrue(playing.sameMetadata(snapshot("player", "Track", "Album", 2, 20, true, 1)));
    }

    @Test
    public void trackSourceAndLateArtworkChangesRepublishMetadata() {
        OemMediaBrowserService.Snapshot first = snapshot("player", "Track", "Album", 3, 0, true, 1);
        assertFalse(first.sameMetadata(null));
        assertFalse(first.sameMetadata(snapshot("player", "Next", "Album", 3, 0, true, 1)));
        assertFalse(first.sameMetadata(snapshot("other", "Track", "Album", 3, 0, true, 1)));
        assertFalse(first.sameMetadata(snapshot("player", "Track", "Other album", 3, 0, true, 1)));
        assertFalse(first.sameMetadata(snapshot("player", "Track", "Album", 3, 0, true, 2)));
        assertFalse(first.sameMetadata(snapshot("player", "Track", "Album", 3, 0, false, 1)));
        assertFalse(first.sameMetadata(OemMediaBrowserService.Snapshot.empty()));
    }

    @Test
    public void missingArtworkDoesNotUseStaleImageRevision() {
        OemMediaBrowserService.Snapshot emptyArt = snapshot("player", "Track", "Album", 3, 0, false, 1);
        assertTrue(emptyArt.sameMetadata(snapshot("player", "Track", "Album", 3, 0, false, 2)));
        assertFalse(emptyArt.sameMetadata(snapshot("player", "Track", "Album", 3, 0, true, 2)));
    }

    private static OemMediaBrowserService.Snapshot snapshot(String player, String title, String album,
            int state, long position, boolean art, long revision) {
        return new OemMediaBrowserService.Snapshot(player, title, "Artist", album,
                state, position, 180000, art, revision);
    }
}
