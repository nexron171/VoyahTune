package ru.voyahtune.agent.policy;

import java.util.function.Consumer;
import java.util.function.Supplier;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class SteeringPress implements AutoCloseable {
    private final ModuleScope.Scheduler scheduler;
    private final Supplier<String> shortAction;
    private final Supplier<String> longAction;
    private final Consumer<String> dispatch;
    private AutoCloseable timer;
    private boolean longFired;
    private boolean nativeLongFallback;
    private long generation;

    public SteeringPress(ModuleScope.Scheduler scheduler, Supplier<String> shortAction,
                         Supplier<String> longAction, Consumer<String> dispatch) {
        this.scheduler = scheduler;
        this.shortAction = shortAction;
        this.longAction = longAction;
        this.dispatch = dispatch;
    }

    public boolean passthrough() {
        return shortAction.get().equals("none") && longAction.get().equals("none");
    }

    public synchronized void down(int repeatCount) throws Exception {
        if (repeatCount > 0) {
            return;
        }
        close();
        longFired = false;
        nativeLongFallback = false;
        String action = longAction.get();
        long current = generation;
        timer = scheduler.schedule(600, () -> {
            synchronized (SteeringPress.this) {
                if (generation != current) {
                    return;
                }
                timer = null;
                nativeLongFallback = action.equals("none");
                longFired = !nativeLongFallback;
                if (longFired) {
                    dispatch.accept(action);
                }
            }
        });
    }

    public synchronized boolean up() throws Exception {
        close();
        if (nativeLongFallback) {
            nativeLongFallback = false;
            return false;
        }
        if (!longFired) {
            String action = shortAction.get();
            if (action.equals("none")) {
                return false;
            }
            dispatch.accept(action);
        }
        return true;
    }

    @Override
    public synchronized void close() throws Exception {
        generation++;
        if (timer != null) {
            AutoCloseable obsolete = timer;
            timer = null;
            obsolete.close();
        }
    }
}
