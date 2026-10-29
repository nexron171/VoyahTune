package ru.voyahtune.agent.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ClusterCardController implements AutoCloseable {
    public interface IO {
        long now();
        boolean enabled() throws Exception;
        void enabled(boolean enabled) throws Exception;
        void component(String component) throws Exception;
        void theme(String theme) throws Exception;
        int display() throws Exception;
        boolean navigatorRunning() throws Exception;
        void launch(int display, boolean restart) throws Exception;
        void announce(int state) throws Exception;
        void switchCard(String category) throws Exception;
        void applyTheme() throws Exception;
        void schedule(long delay, Runnable task) throws Exception;
        void error(Exception failure);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    public static final String NAVI = "qg.car.cluster.NAVI";
    public static final String NAVI_FULL = NAVI + "_FULL";
    public static final String MEDIA_SMALL = "qg.car.cluster.MEDIA_SMALL";
    private final IO io;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private long lastAnswer = -1;
    private long lastShow = -1;
    private long epoch;
    private int launchedDisplay = -1;
    private boolean startupScheduled;
    private boolean owned;
    private boolean closed;

    public ClusterCardController(IO io) { this.io = io; }

    public synchronized void configure() throws Exception {
        epoch++;
        for (Pending task : new ArrayList<>(pending.values())) schedule(task);
        if (startupScheduled) return;
        startupScheduled = true;
        defer("startup", 4000, () -> {
            if (io.enabled()) { lastAnswer = io.now(); show(true, NAVI_FULL); }
            else io.applyTheme();
        });
    }

    public synchronized void broadcast(String component, String theme, Integer enable, boolean restart) throws Exception {
        if (closed) return;
        if (component != null && component.indexOf('/') > 0) { io.component(component); launchedDisplay = -1; lastShow = -1; }
        if (theme != null) {
            String requested = theme.trim().toLowerCase(Locale.ROOT);
            if (Set.of("day", "night", "auto").contains(requested)) { io.theme(requested); io.applyTheme(); }
        }
        if (enable == null && theme != null && component == null) return;
        boolean enabled = enable == null ? !io.enabled() : enable == 1;
        io.enabled(enabled);
        if (enabled) { lastAnswer = io.now(); show(restart, NAVI_FULL); }
        else hide();
    }

    public synchronized void presentation(String category, int state) throws Exception {
        if (closed || category == null || !category.startsWith(NAVI) || state != 1) return;
        if (!io.enabled()) { defer("theme", 0, io::applyTheme); return; }
        if (lastAnswer >= 0 && io.now() - lastAnswer < 5000) return;
        lastAnswer = io.now();
        String card = category.equals(NAVI) ? NAVI_FULL : category;
        defer("presentation", 0, () -> show(false, card));
    }

    public synchronized void displayRecreated() throws Exception {
        if (closed) return;
        launchedDisplay = -1;
        lastShow = -1;
        pending.remove("show-card");
        defer("recreated", 2500, () -> {
            if (io.enabled()) { lastAnswer = io.now(); show(true, NAVI_FULL); }
            else io.applyTheme();
        });
    }

    private void show(boolean restart, String category) throws Exception {
        if (closed || !io.enabled() || lastShow >= 0 && io.now() - lastShow < 3000) return;
        lastShow = io.now();
        int display = io.display();
        if (display < 0) return;
        pending.remove("startup");
        owned = true;
        io.announce(1);
        if (restart || launchedDisplay != display || !io.navigatorRunning()) { io.launch(display, restart); launchedDisplay = display; }
        defer("show-card", 1500, () -> {
            if (!io.enabled()) return;
            io.switchCard(category);
            io.applyTheme();
        });
    }

    private void hide() throws Exception {
        lastAnswer = io.now();
        lastShow = -1;
        pending.remove("show-card");
        pending.remove("presentation");
        io.announce(0);
        io.switchCard(MEDIA_SMALL);
        owned = false;
    }

    private void defer(String name, long delay, Action action) throws Exception {
        Pending task = new Pending(name, io.now() + delay, action);
        pending.put(name, task);
        schedule(task);
    }

    private void schedule(Pending task) throws Exception {
        long scheduledEpoch = epoch;
        io.schedule(Math.max(0, task.due - io.now()), () -> {
            synchronized (this) {
                if (closed || scheduledEpoch != epoch || pending.get(task.name) != task) return;
                pending.remove(task.name);
                try { task.action.run(); }
                catch (Exception unavailable) { io.error(unavailable); }
            }
        });
    }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        epoch++;
        pending.clear();
        if (owned) { io.announce(0); io.switchCard(MEDIA_SMALL); owned = false; }
    }

    private static final class Pending {
        final String name;
        final long due;
        final Action action;
        Pending(String name, long due, Action action) { this.name = name; this.due = due; this.action = action; }
    }
}
