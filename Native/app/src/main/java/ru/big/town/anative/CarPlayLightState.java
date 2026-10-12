package ru.big.town.anative;

import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Serial owner state; blocking reads return through the owner's executor. */
final class CarPlayLightState {
    private final Executor queryExecutor;
    private final Executor ownerExecutor;
    private final Supplier<Integer> reader;
    private final IntConsumer listener;
    private long revision;
    private boolean active;

    CarPlayLightState(
            Executor queryExecutor,
            Executor ownerExecutor,
            Supplier<Integer> reader,
            IntConsumer listener) {
        this.queryExecutor = queryExecutor;
        this.ownerExecutor = ownerExecutor;
        this.reader = reader;
        this.listener = listener;
    }

    void refresh() {
        active = true;
        long requestedRevision = ++revision;
        queryExecutor.execute(() -> {
            Integer reason = reader.get();
            ownerExecutor.execute(() -> {
                // A live event, reconnect or closed session supersedes an in-flight read.
                if (active && revision == requestedRevision && reason != null) {
                    listener.accept(reason);
                }
            });
        });
    }

    void onEvent(int reason) {
        if (!active) return;
        revision++;
        listener.accept(reason);
    }

    void reset() {
        active = false;
        revision++;
    }
}
