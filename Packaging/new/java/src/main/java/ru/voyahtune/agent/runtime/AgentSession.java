package ru.voyahtune.agent.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentSession implements AutoCloseable {
    @FunctionalInterface
    public interface Factory {
        ModuleRuntime create(String runtimeId) throws Exception;
    }

    private final String identity;
    private final Factory factory;
    private volatile ModuleRuntime runtime;
    private boolean closed;

    public AgentSession(String identity, Factory factory) {
        this.identity = Objects.requireNonNull(identity);
        this.factory = Objects.requireNonNull(factory);
    }

    public synchronized ModuleRuntime.Snapshot apply(String expectedIdentity, String runtimeId,
                                                      long revision, List<String> modules,
                                                      Map<String, String> configuration) throws Exception {
        if (!identity.equals(expectedIdentity)) {
            throw new IllegalArgumentException("Target identity changed");
        }
        if (closed) {
            throw new IllegalStateException("Agent session is closed");
        }
        if (runtime == null) {
            runtime = Objects.requireNonNull(factory.create(runtimeId));
        }
        return runtime.apply(runtimeId, revision, modules, configuration);
    }

    public ModuleRuntime.Snapshot snapshot() {
        ModuleRuntime current = runtime;
        return current == null ? null : current.snapshot();
    }

    @Override
    public void close() throws Exception {
        ModuleRuntime current;
        synchronized (this) {
            closed = true;
            current = runtime;
        }
        if (current != null) {
            current.close();
        }
    }
}
