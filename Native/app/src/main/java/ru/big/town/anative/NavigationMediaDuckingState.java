package ru.big.town.anative;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class NavigationMediaDuckingState {
    static final String APPLICATION_NAVIGATION = "applications";
    private static final String CARPLAY_PACKAGE = "ru.big.town.restoremode";
    private static final int MEDIA_STREAM = 3;
    private static final int USAGE_UNKNOWN = 0;
    private static final int USAGE_MEDIA = 1;
    private static final int USAGE_GAME = 14;
    private static final int CONTENT_SPEECH = 1;
    private static final int FIRST_APPLICATION_UID = 10000;
    private static final int USER_UID_RANGE = 100000;

    interface Player {
        int id();

        boolean active();

        boolean shouldDuck(boolean applicationNavigation);

        void duck() throws Exception;

        void restore() throws Exception;
    }

    private final Set<String> owners = new HashSet<>();
    private final Map<Integer, Player> ducked = new HashMap<>();
    private final Set<Integer> applied = new HashSet<>();
    private final Consumer<Exception> report;

    NavigationMediaDuckingState(Consumer<Exception> report) {
        this.report = report;
    }

    static boolean shouldDuck(
            String packageName,
            int uid,
            int stream,
            int usage,
            int content,
            boolean applicationNavigation) {
        boolean mediaUsage = usage == USAGE_UNKNOWN || usage == USAGE_MEDIA || usage == USAGE_GAME;
        if (stream != MEDIA_STREAM || content == CONTENT_SPEECH || !mediaUsage) return false;
        if (packageName == null || packageName.isEmpty() || "none".equals(packageName)) return false;
        // RDS shares the system UID, so only its exact playback package may bypass the app range.
        boolean radio =
                "com.adayo.service.dab".equals(packageName)
                        || "com.pateo.rdsapp".equals(packageName);
        if (uid < 0 || (uid % USER_UID_RANGE < FIRST_APPLICATION_UID && !radio)) return false;
        // The CarPlay sink already applies iPhone duckAudio to its own music.
        return applicationNavigation || !CARPLAY_PACKAGE.equals(packageName);
    }

    void setNavigationActive(String owner, boolean active) {
        if (active) owners.add(owner);
        else owners.remove(owner);
        if (owners.isEmpty()) restore();
    }

    void updatePlayers(List<? extends Player> players) {
        Set<Integer> live = new HashSet<>();
        for (Player player : players) live.add(player.id());
        ducked.keySet().retainAll(live);
        applied.retainAll(live);
        if (owners.isEmpty()) {
            restore();
            return;
        }
        boolean applicationNavigation = owners.contains(APPLICATION_NAVIGATION);
        for (Player player : players) {
            if (!player.shouldDuck(applicationNavigation)) {
                restore(player.id());
                continue;
            }
            if (!player.active() || applied.contains(player.id())) continue;
            // A failed Binder reply may still leave a shaper on the remote player.
            ducked.put(player.id(), player);
            try {
                player.duck();
                applied.add(player.id());
            } catch (Exception failure) {
                report.accept(failure);
            }
        }
    }

    boolean needsPlayback() {
        return !owners.isEmpty() || !ducked.isEmpty();
    }

    void close() {
        owners.clear();
        restore();
    }

    private void restore() {
        for (Integer id : new HashSet<>(ducked.keySet())) restore(id);
    }

    private void restore(int id) {
        Player player = ducked.get(id);
        if (player == null) return;
        try {
            player.restore();
            ducked.remove(id);
            applied.remove(id);
        } catch (Exception failure) {
            // The remote restore may have succeeded before its Binder reply failed.
            applied.remove(id);
            report.accept(failure);
        }
    }
}
