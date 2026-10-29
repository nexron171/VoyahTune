package ru.voyahtune.agent.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import ru.voyahtune.agent.HookRegistry;

public final class ModuleRuntime implements AutoCloseable {
    public enum Phase { STARTING, READY, STOPPING, FAILED, RESTART_REQUIRED, CLOSED }

    public interface Module {
        default void prepare(Map<String, String> configuration) throws Exception {
        }
        void install(ModuleScope scope, Map<String, String> configuration) throws Exception;
        void configure(ModuleScope scope, Map<String, String> configuration) throws Exception;
        default boolean ready() {
            return true;
        }
    }

    @FunctionalInterface
    public interface Factory {
        Module create(String id) throws Exception;
    }

    public static final class Snapshot {
        public final Phase phase;
        public final long revision;
        public final List<String> modules;
        public final Throwable failure;
        private final List<Module> observed;

        Snapshot(Phase phase, long revision, List<String> modules, Throwable failure) {
            this(phase, revision, modules, failure, List.of());
        }

        Snapshot(Phase phase, long revision, List<String> modules, Throwable failure, List<Module> observed) {
            this.phase = phase;
            this.revision = revision;
            this.modules = List.copyOf(modules);
            this.failure = failure;
            this.observed = List.copyOf(observed);
        }
    }

    private final String runtimeId;
    private final ModuleScope.Scheduler scheduler;
    private final Factory factory;
    private final Map<String, Entry> modules = new LinkedHashMap<>();
    private Phase phase = Phase.STARTING;
    private long revision = -1;
    private List<String> desired = List.of();
    private Map<String, String> configuration = Map.of();
    private Throwable failure;
    private boolean cleanupUncertain;
    private volatile Snapshot published = new Snapshot(Phase.STARTING, -1, List.of(), null);
    private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();

    public ModuleRuntime(String runtimeId, ModuleScope.Scheduler scheduler, Factory factory) {
        this.runtimeId = Objects.requireNonNull(runtimeId);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.factory = Objects.requireNonNull(factory);
    }

    public synchronized Snapshot apply(
        String requestedRuntime, long nextRevision, List<String> requestedModules, Map<String, String> settings) {
        if (phase == Phase.CLOSED || phase == Phase.RESTART_REQUIRED) {
            return snapshot();
        }
        if (asynchronousFailure.get() != null)
            return snapshot();
        if (!runtimeId.equals(requestedRuntime)) {
            phase = Phase.RESTART_REQUIRED;
            return publish();
        }
        if (nextRevision < 0 || new HashSet<>(requestedModules).size() != requestedModules.size()) {
            throw new IllegalArgumentException("Invalid module plan");
        }
        List<String> nextDesired = List.copyOf(requestedModules);
        Map<String, String> nextConfiguration = Map.copyOf(settings);
        if (nextRevision < revision) {
            return snapshot();
        }
        if (nextRevision == revision) {
            if (!nextDesired.equals(desired) || !nextConfiguration.equals(configuration)) {
                throw new IllegalArgumentException("A configuration revision cannot identify different plans");
            }
            return snapshot();
        }
        phase = Phase.STARTING;
        publish();
        try {
            Map<String, Module> prepared = new LinkedHashMap<>();
            for (String id : nextDesired) {
                Entry current = modules.get(id);
                Module module = current == null ? Objects.requireNonNull(factory.create(id), "Unknown module: " + id)
                                                : current.module;
                module.prepare(nextConfiguration);
                if (current == null) {
                    prepared.put(id, module);
                }
            }
            List<String> removed = new ArrayList<>(modules.keySet());
            removed.removeAll(nextDesired);
            for (String id : removed) {
                modules.remove(id).scope.close();
            }
            for (String id : nextDesired) {
                Entry entry = modules.get(id);
                if (entry == null) {
                    entry = new Entry(prepared.get(id),
                        new ModuleScope(
                            scheduler, nextRevision, caught -> asynchronousFailure.compareAndSet(null, caught)));
                    modules.put(id, entry);
                    entry.module.install(entry.scope, nextConfiguration);
                }
                Entry configured = entry;
                entry.scope.configure(
                    nextRevision, () -> configured.module.configure(configured.scope, nextConfiguration));
            }
            revision = nextRevision;
            desired = nextDesired;
            configuration = nextConfiguration;
            failure = null;
            phase = Phase.READY;
        } catch (Throwable caught) {
            failure = caught;
            cleanupUncertain = caught instanceof ModuleScope.CleanupException
                || caught instanceof HookRegistry.RestartRequiredException || asynchronousFailure.get() != null;
            phase = cleanupUncertain ? Phase.RESTART_REQUIRED : Phase.FAILED;
            try {
                closeModules();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                cleanupUncertain = true;
                phase = Phase.RESTART_REQUIRED;
            }
        }
        return publish();
    }

    public Snapshot snapshot() {
        Snapshot current = published;
        Throwable asynchronous = asynchronousFailure.get();
        if (current.phase == Phase.CLOSED)
            return current;
        if (asynchronous != null)
            return new Snapshot(Phase.RESTART_REQUIRED, current.revision, current.modules, asynchronous);
        if (current.phase == Phase.READY && current.observed.stream().anyMatch(module -> !module.ready())) {
            return new Snapshot(Phase.STARTING, current.revision, current.modules, null);
        }
        return current;
    }

    private Snapshot publish() {
        List<Module> observed = new ArrayList<>();
        modules.values().forEach(entry -> observed.add(entry.module));
        published = new Snapshot(phase, revision, new ArrayList<>(modules.keySet()), failure, observed);
        return snapshot();
    }

    @Override
    public synchronized void close() throws Exception {
        if (phase == Phase.CLOSED) {
            return;
        }
        if (cleanupUncertain) {
            throw new IllegalStateException("Module cleanup remains unconfirmed", failure);
        }
        phase = Phase.STOPPING;
        publish();
        try {
            closeModules();
            phase = Phase.CLOSED;
        } catch (Throwable caught) {
            failure = caught;
            cleanupUncertain = true;
            phase = Phase.RESTART_REQUIRED;
            if (caught instanceof Exception)
                throw (Exception) caught;
            throw new IllegalStateException("Module cleanup aborted", caught);
        } finally {
            publish();
        }
    }

    private void closeModules() throws Exception {
        List<ModuleScope> scopes = new ArrayList<>();
        modules.values().forEach(entry -> scopes.add(entry.scope));
        modules.clear();
        ModuleScope.closeAll(scopes);
    }

    private static final class Entry {
        final Module module;
        final ModuleScope scope;

        Entry(Module module, ModuleScope scope) {
            this.module = module;
            this.scope = scope;
        }
    }
}
