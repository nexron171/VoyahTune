package ru.voyahtune.agent.policy;

import java.util.Set;

public final class StatusBarController {
    public interface IO {
        String top(int display) throws Exception;
        boolean hideSystemBars() throws Exception;
        long revealRemaining(int display) throws Exception;
        boolean stockHidden(String activity, int display) throws Exception;
        void apply(int display, boolean visible, Object receiver) throws Exception;
        void reportHidden(int display, boolean hidden) throws Exception;
        void schedule(long delay, Runnable action) throws Exception;
        void error(Exception failure);
    }

    private static final Set<String> HOSTS = Set.of("ru.big.town.anative.CarPlayFullscreenActivity",
            "ru.big.town.anative/.CarPlayFullscreenActivity", "ru.big.town.anative/ru.big.town.anative.CarPlayFullscreenActivity");
    private final IO io;
    private final boolean[] owned = new boolean[2];
    private boolean timerPending;

    public StatusBarController(IO io) { this.io = io; }

    public static boolean isHost(String activity, int display) { return (display == 0 || display == 1) && HOSTS.contains(activity == null ? "" : activity); }

    public synchronized void request(int display, boolean visible, Object receiver) {
        try {
            boolean host = isHost(top(display), display);
            boolean carPlay = host && io.hideSystemBars();
            apply(display, host ? !carPlay || io.revealRemaining(display) > 0 : visible, carPlay, receiver);
        } catch (Exception unavailable) { io.error(unavailable); }
    }

    public synchronized void reconcile() {
        for (int display = 0; display < 2; display++) {
            try {
                String activity = top(display);
                boolean host = isHost(activity, display);
                boolean carPlay = host && io.hideSystemBars();
                if (!host && !owned[display]) continue;
                boolean visible = host ? !carPlay || io.revealRemaining(display) > 0 : !io.stockHidden(activity, display);
                apply(display, visible, carPlay, null);
            } catch (Exception unavailable) { io.error(unavailable); }
        }
        schedule();
    }

    public synchronized void resetTimer() { timerPending = false; }

    public synchronized void release() throws Exception {
        Exception failure = null;
        for (int display = 0; display < 2; display++) {
            if (!owned[display]) continue;
            try {
                io.apply(display, !io.stockHidden(top(display), display), null);
                io.reportHidden(display, false);
                owned[display] = false;
            } catch (Exception unavailable) {
                if (failure == null) failure = unavailable; else failure.addSuppressed(unavailable);
            }
        }
        if (failure != null) throw failure;
    }

    private String top(int display) {
        try { String value = io.top(display); return value == null ? "" : value; }
        catch (Exception unavailable) { io.error(unavailable); return ""; }
    }

    private void apply(int display, boolean visible, boolean carPlay, Object receiver) {
        if (carPlay) owned[display] = true;
        try {
            io.apply(display, visible, receiver);
            io.reportHidden(display, carPlay && !visible);
            owned[display] = carPlay;
        } catch (Exception unavailable) { io.error(unavailable); }
        schedule();
    }

    private void schedule() {
        if (timerPending || !owned[0] && !owned[1]) return;
        try {
            long delay = 1000;
            for (int display = 0; display < 2; display++) {
                long remaining = io.revealRemaining(display);
                if (remaining > 0) delay = Math.min(delay, remaining);
            }
            timerPending = true;
            io.schedule(delay, () -> {
                synchronized (this) { timerPending = false; }
                reconcile();
            });
        } catch (Exception unavailable) { timerPending = false; io.error(unavailable); }
    }
}
