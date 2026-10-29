package ru.voyahtune.agent.modules;

import java.util.function.LongSupplier;

public final class WindowPreparation {
    public enum State { PREPARING, READY, FAILED }
    @FunctionalInterface public interface Probe { void run() throws Exception; }
    public static final class Pending extends Exception {
        public Pending(String message) { super(message); }
    }

    private final LongSupplier clock;
    private final Probe probe;
    private final long deadline;
    private int attempts;
    private State state = State.PREPARING;
    private Exception failure;

    public WindowPreparation(LongSupplier clock, Probe probe) {
        this.clock = clock;
        this.probe = probe;
        deadline = clock.getAsLong() + 20000;
    }

    public State poll() {
        if (state != State.PREPARING) return state;
        try {
            if (clock.getAsLong() >= deadline) throw new IllegalStateException("Window preparation deadline exceeded");
            attempts++;
            probe.run();
            state = State.READY;
        } catch (Pending | ClassNotFoundException pending) {
            failure = pending;
            if (attempts >= 41 || clock.getAsLong() >= deadline) state = State.FAILED;
        } catch (Exception incompatible) {
            failure = incompatible;
            state = State.FAILED;
        }
        return state;
    }

    public Exception failure() { return failure; }
}
