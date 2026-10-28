package ru.voyahtune.contracts;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.runtime.AgentSession;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

final class ModuleContractDriver {
    private ModuleContractDriver() {}

    static JsonObject execute(JsonObject input) throws Exception {
        Harness harness = new Harness();
        AgentSession session = harness.session();
        JsonObject result = new JsonObject();
        try {
            if (input.has("installModules")) {
                harness.failModule = input.get("failModule").getAsString();
                List<String> modules = new ArrayList<>();
                input.getAsJsonArray("installModules").forEach(value -> modules.add(value.getAsString()));
                session.apply("target", "code-v1", 1, modules, Map.of("revision", "1"));
                result.addProperty("ready", session.snapshot().phase == ModuleRuntime.Phase.READY);
                result.addProperty("remainingHooks", harness.hooks);
                result.addProperty("remainingListeners", harness.listeners);
            } else if (input.has("revisions")) {
                for (var revision : input.getAsJsonArray("revisions")) {
                    configure(session, "code-v1", revision.getAsLong());
                }
                result.add("appliedRevisions", new Gson().toJsonTree(harness.appliedRevisions));
                result.addProperty("bootstrapCount", harness.bootstrapCount);
            } else {
                List<String> events = new ArrayList<>();
                input.getAsJsonArray("events").forEach(value -> events.add(value.getAsString()));
                for (String event : events) {
                    switch (event) {
                        case "enable":
                        case "ready-v1":
                            configure(session, "code-v1", 1);
                            break;
                        case "schedule-v1":
                            configure(session, "code-v1", 1);
                            harness.scope.schedule(1, () -> harness.effects.add("stale"));
                            break;
                        case "schedule":
                            harness.scope.schedule(1, () -> harness.effects.add("late"));
                            break;
                        case "disable":
                            session.close();
                            break;
                        case "configure-v2":
                            configure(session, "code-v1", 2);
                            break;
                        case "files-v2":
                            configure(session, "code-v2", 2);
                            break;
                        case "advance":
                        case "run-v1":
                            harness.advance();
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown module event: " + event);
                    }
                }
                if (events.contains("files-v2")) {
                    result.addProperty("state", session.snapshot().phase.name().toLowerCase().replace('_', '-'));
                    result.addProperty("bootstrapCount", harness.bootstrapCount);
                } else if (events.contains("disable")) {
                    result.add("effectsAfterDisable", new Gson().toJsonTree(harness.effects));
                    result.addProperty("listeners", harness.listeners);
                    result.addProperty("timers", harness.timers);
                } else {
                    result.add("effects", new Gson().toJsonTree(harness.effects));
                }
            }
        } finally {
            session.close();
        }
        return result;
    }

    private static void configure(AgentSession session, String code, long revision) throws Exception {
        session.apply("target", code, revision, List.of("test"), Map.of("revision", Long.toString(revision)));
    }

    private static final class Harness {
        final List<Task> tasks = new ArrayList<>();
        final List<String> effects = new ArrayList<>();
        final List<Long> appliedRevisions = new ArrayList<>();
        int bootstrapCount;
        int hooks;
        int listeners;
        int timers;
        String failModule;
        ModuleScope scope;

        AgentSession session() {
            return new AgentSession("target", code -> {
                bootstrapCount++;
                return new ModuleRuntime(code, this::schedule, id -> new ModuleRuntime.Module() {
                    @Override
                    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
                        Harness.this.scope = scope;
                        hooks++;
                        scope.own(() -> hooks--);
                        listeners++;
                        scope.own(() -> listeners--);
                        if (id.equals(failModule)) {
                            throw new IllegalStateException("Module install failed");
                        }
                    }

                    @Override
                    public void configure(ModuleScope scope, Map<String, String> configuration) {
                        appliedRevisions.add(Long.parseLong(configuration.get("revision")));
                    }
                });
            });
        }

        AutoCloseable schedule(long delay, Runnable runnable) {
            Task task = new Task(runnable);
            tasks.add(task);
            timers++;
            return task;
        }

        void advance() {
            List<Task> ready = new ArrayList<>(tasks);
            tasks.clear();
            for (Task task : ready) {
                task.close();
                task.runnable.run();
            }
        }

        private final class Task implements AutoCloseable {
            final Runnable runnable;
            boolean completed;

            Task(Runnable runnable) {
                this.runnable = runnable;
            }

            @Override
            public void close() {
                if (!completed) {
                    completed = true;
                    timers--;
                }
            }
        }
    }
}
