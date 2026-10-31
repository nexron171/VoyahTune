package ru.voyahtune.contracts;

import ru.voyahtune.agent.policy.OemNowPlaying;

public final class OemNowPlayingTest {
    public static void main(String[] arguments) {
        check(new OemNowPlaying("ru.yandex.music", "", "", "Яндекс Музыка", 2, false, true).overridesOem(),
                "remembered source is selected before its session exists");
        check(!new OemNowPlaying("com.android.bluetooth", "", "", "Bluetooth", 2, false, true).overridesOem(),
                "remembered OEM source stays on OEM route");
        check(snapshot("com.spotify.music", "Track", 3, 1).overridesOem(), "playing session");
        check(snapshot("ru.yandex.music", "Track", 2, 1).overridesOem(), "paused session");
        check(!snapshot("", "Track", 3, 1).overridesOem(), "missing session");
        check(!snapshot("com.qinggan.media", "Track", 3, 1).overridesOem(), "OEM player");
        check(!snapshot("com.android.bluetooth", "Track", 3, 1).overridesOem(), "Bluetooth");
        check(!snapshot("com.pateo.rdsapp", "Station", 3, 1).overridesOem(), "OEM radio");
        check(!snapshot("player", "", 3, 0).overridesOem(), "empty metadata");
        check(!snapshot("player", "Track", 0, 1).overridesOem(), "inactive session");
        check(snapshot("player", "Track", 1, 0).overridesOem(), "stopped player can resume");
        check(!snapshot("player", "Track", 7, 1).overridesOem(), "failed player");
        check(new OemNowPlaying(null, null, null, null, 0, false).overridesOem() == false,
                "missing provider values");
        check(new OemNowPlaying("player", "", "Artist", "", 3, false).overridesOem(),
                "artist-only metadata");
        check(new OemNowPlaying("player", "", "", "", 3, true).overridesOem(),
                "artwork-only metadata");
        System.out.println("OemNowPlayingTest: passed");
    }

    private static OemNowPlaying snapshot(String player, String title, int state, long revision) {
        return new OemNowPlaying(player, title, "", "", state, revision > 0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
