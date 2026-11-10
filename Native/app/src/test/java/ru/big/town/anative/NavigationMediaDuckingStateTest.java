package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class NavigationMediaDuckingStateTest {
    @Test
    public void restoresOnlyAfterBothAppAndCarPlayNavigationEnd() {
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failure -> {});
        RecordingPlayer radio = new RecordingPlayer(41, true);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(radio));
        state.setNavigationActive("carplay", true);
        state.updatePlayers(List.of(radio));
        assertEquals(List.of("duck"), radio.actions);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, false);
        assertEquals(List.of("duck"), radio.actions);
        state.setNavigationActive("carplay", false);
        assertEquals(List.of("duck", "restore"), radio.actions);
        assertFalse(state.needsPlayback());
    }

    @Test
    public void newPlayersAreDuckedAndPausedPlayersStillRestore() {
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failure -> {});
        RecordingPlayer radio = new RecordingPlayer(41, true);
        RecordingPlayer recreated = new RecordingPlayer(42, false);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(radio, recreated));
        assertEquals(List.of(), recreated.actions);
        recreated.active = true;
        state.updatePlayers(List.of(radio, recreated));
        assertEquals(List.of("duck"), recreated.actions);
        radio.active = false;
        state.updatePlayers(List.of(radio, recreated));
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, false);
        assertEquals(List.of("duck", "restore"), radio.actions);
        assertEquals(List.of("duck", "restore"), recreated.actions);
    }

    @Test
    public void releasedPlayersAreForgottenAndTheNextPromptDucksAgain() {
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failure -> {});
        RecordingPlayer released = new RecordingPlayer(41, true);
        RecordingPlayer current = new RecordingPlayer(42, true);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(released));
        state.updatePlayers(List.of(current));
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, false);
        assertEquals(List.of("duck"), released.actions);
        assertEquals(List.of("duck", "restore"), current.actions);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(current));
        state.close();
        assertEquals(List.of("duck", "restore", "duck", "restore"), current.actions);
        assertFalse(state.needsPlayback());
    }

    @Test
    public void failuresAreRetriedAndClosingRestoresEveryRemainingPlayer() {
        List<Exception> failures = new ArrayList<>();
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failures::add);
        RecordingPlayer radio = new RecordingPlayer(41, true);
        radio.failDuck = true;
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(radio));
        assertEquals(1, failures.size());
        radio.failDuck = false;
        state.updatePlayers(List.of(radio));
        radio.failRestore = true;
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, false);
        assertTrue(state.needsPlayback());
        assertEquals(2, failures.size());
        radio.failRestore = false;
        state.updatePlayers(List.of(radio));
        assertEquals(List.of("duck", "restore"), radio.actions);
        assertFalse(state.needsPlayback());
    }

    @Test
    public void navigationDucksRadioAndAnOrdinaryMusicPlayerWithOneGainEach() {
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failure -> {});
        RecordingPlayer music = new RecordingPlayer(42, true);
        music.packageName = "ru.yandex.music";
        music.uid = 10057;
        RecordingPlayer radio = new RecordingPlayer(43, true);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(music, radio));
        state.updatePlayers(List.of(music, radio));
        assertEquals(List.of("duck"), music.actions);
        assertEquals(List.of("duck"), radio.actions);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, false);
        assertEquals(List.of("duck", "restore"), music.actions);
        assertEquals(List.of("duck", "restore"), radio.actions);
    }

    @Test
    public void speechNavigationPhoneAssistantAndSystemSoundsKeepTheirOwnGain() {
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failure -> {});
        List<RecordingPlayer> protectedPlayers = new ArrayList<>();
        for (int stream : new int[] {1, 6, 22}) {
            RecordingPlayer player = new RecordingPlayer(stream, true);
            player.stream = stream;
            protectedPlayers.add(player);
        }
        for (int usage : new int[] {2, 3, 4, 5, 6, 11, 12, 13, 16}) {
            RecordingPlayer player = new RecordingPlayer(100 + usage, true);
            player.usage = usage;
            protectedPlayers.add(player);
        }
        RecordingPlayer speech = new RecordingPlayer(201, true);
        speech.content = 1;
        protectedPlayers.add(speech);
        RecordingPlayer system = new RecordingPlayer(202, true);
        system.packageName = "com.qinggan.app.setting";
        system.uid = 1000;
        protectedPlayers.add(system);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(protectedPlayers);
        state.close();
        for (RecordingPlayer player : protectedPlayers) assertEquals(List.of(), player.actions);
    }

    @Test
    public void onlyExternalNavigationAddsGainToCarPlayMusic() {
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failure -> {});
        RecordingPlayer carPlay = new RecordingPlayer(42, true);
        carPlay.packageName = "ru.big.town.restoremode";
        carPlay.uid = 10055;
        RecordingPlayer music = new RecordingPlayer(43, true);
        music.packageName = "ru.yandex.music";
        music.uid = 10057;
        state.setNavigationActive("carplay", true);
        state.updatePlayers(List.of(carPlay, music));
        assertEquals(List.of(), carPlay.actions);
        assertEquals(List.of("duck"), music.actions);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(carPlay, music));
        assertEquals(List.of("duck"), carPlay.actions);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, false);
        state.updatePlayers(List.of(carPlay, music));
        assertEquals(List.of("duck", "restore"), carPlay.actions);
        assertEquals(List.of("duck"), music.actions);
        state.setNavigationActive("carplay", false);
        assertEquals(List.of("duck", "restore"), music.actions);
    }

    @Test
    public void changingAnExistingPlayerToNavigationRestoresItsMediaGain() {
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failure -> {});
        RecordingPlayer player = new RecordingPlayer(42, true);
        player.packageName = "ru.yandex.music";
        player.uid = 10057;
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(player));
        player.stream = 1;
        state.updatePlayers(List.of(player));
        state.close();
        assertEquals(List.of("duck", "restore"), player.actions);
    }

    @Test
    public void failedRestoreDoesNotSuppressTheNextPromptDucking() {
        NavigationMediaDuckingState state = new NavigationMediaDuckingState(failure -> {});
        RecordingPlayer player = new RecordingPlayer(42, true);
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(player));
        player.failRestore = true;
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, false);
        player.failRestore = false;
        state.setNavigationActive(NavigationMediaDuckingState.APPLICATION_NAVIGATION, true);
        state.updatePlayers(List.of(player));
        state.close();
        assertEquals(List.of("duck", "duck", "restore"), player.actions);
    }

    @Test
    public void identityAndPlaybackRoleAreBothRequired() {
        assertTrue(NavigationMediaDuckingState.shouldDuck("com.adayo.service.dab", 1000, 3, 1, 2, false));
        assertTrue(NavigationMediaDuckingState.shouldDuck("com.pateo.rdsapp", 1000, 3, 1, 2, true));
        assertTrue(NavigationMediaDuckingState.shouldDuck("ru.yandex.music", 10057, 3, 1, 2, true));
        assertTrue(NavigationMediaDuckingState.shouldDuck("some.game", 10058, 3, 14, 0, true));
        assertFalse(NavigationMediaDuckingState.shouldDuck("com.adayo.service.dab", -1, 3, 1, 2, true));
        assertFalse(NavigationMediaDuckingState.shouldDuck("com.adayo.service.dab", 1000, 1, 12, 1, true));
        assertFalse(NavigationMediaDuckingState.shouldDuck("com.qinggan.app.setting", 1000, 3, 1, 2, true));
        assertFalse(NavigationMediaDuckingState.shouldDuck("none", 10057, 3, 1, 2, true));
        assertFalse(NavigationMediaDuckingState.shouldDuck(null, 10057, 3, 1, 2, true));
    }

    private static final class RecordingPlayer implements NavigationMediaDuckingState.Player {
        final int id;
        final List<String> actions = new ArrayList<>();
        boolean active;
        boolean failDuck;
        boolean failRestore;
        String packageName = "com.adayo.service.dab";
        int uid = 1000;
        int stream = 3;
        int usage = 1;
        int content = 2;

        RecordingPlayer(int id, boolean active) {
            this.id = id;
            this.active = active;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public boolean shouldDuck(boolean applicationNavigation) {
            return NavigationMediaDuckingState.shouldDuck(
                    packageName, uid, stream, usage, content, applicationNavigation);
        }

        @Override
        public void duck() {
            if (failDuck) throw new IllegalStateException("duck failed");
            actions.add("duck");
        }

        @Override
        public void restore() {
            if (failRestore) throw new IllegalStateException("restore failed");
            actions.add("restore");
        }
    }
}
