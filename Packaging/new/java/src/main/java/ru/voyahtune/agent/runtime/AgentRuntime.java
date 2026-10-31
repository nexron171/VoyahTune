package ru.voyahtune.agent.runtime;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import ru.voyahtune.agent.HookBridge;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.AccModule;
import ru.voyahtune.agent.modules.AndroidAccPlatform;
import ru.voyahtune.agent.modules.AndroidClientGeometryPlatform;
import ru.voyahtune.agent.modules.AndroidClientInputMethodPlatform;
import ru.voyahtune.agent.modules.AndroidClusterPlatform;
import ru.voyahtune.agent.modules.AndroidDrivePersistence;
import ru.voyahtune.agent.modules.AndroidDriveResetPlatform;
import ru.voyahtune.agent.modules.AndroidLauncherPlatform;
import ru.voyahtune.agent.modules.AndroidMultidisplayPlatform;
import ru.voyahtune.agent.modules.AndroidNavigationAudioPlatform;
import ru.voyahtune.agent.modules.AndroidNavigationSourcePlatform;
import ru.voyahtune.agent.modules.AndroidRdsPlatform;
import ru.voyahtune.agent.modules.AndroidStatusBarPlatform;
import ru.voyahtune.agent.modules.AndroidWindowsPlatform;
import ru.voyahtune.agent.modules.ApolloModule;
import ru.voyahtune.agent.modules.ClientGeometryModule;
import ru.voyahtune.agent.modules.ClientInputMethodModule;
import ru.voyahtune.agent.modules.ClientModule;
import ru.voyahtune.agent.modules.ClusterModule;
import ru.voyahtune.agent.modules.DriveResetModule;
import ru.voyahtune.agent.modules.KeyboardModule;
import ru.voyahtune.agent.modules.LauncherModule;
import ru.voyahtune.agent.modules.MultidisplayModule;
import ru.voyahtune.agent.modules.NavigationAudioModule;
import ru.voyahtune.agent.modules.NavigationSourceModule;
import ru.voyahtune.agent.modules.OemMediaModule;
import ru.voyahtune.agent.modules.PhoneModule;
import ru.voyahtune.agent.modules.RdsModule;
import ru.voyahtune.agent.modules.StatusBarModule;
import ru.voyahtune.agent.modules.SteeringModule;
import ru.voyahtune.agent.modules.VehicleAirModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AgentRuntime {
    private static final boolean USE_DIRECT_HOOKS = true;
    private static volatile AgentRuntime current;

    private final String bootId;
    private final int pid;
    private final int uid;
    private final String startTicks;
    private final String nativeId;
    private final String runtimeId;
    private final String identity;
    private final AndroidCallers callers;
    private final HookRegistry hooks;
    private final ScheduledExecutorService executor;
    private final AgentSession session;
    private volatile Throwable quiesceFailure;
    private volatile Throwable reportedFailure;

    private AgentRuntime(JSONObject metadata, ClassLoader targetLoader, Object applicationContext)
            throws Exception {
        bootId = metadata.getString("bootId");
        pid = metadata.getInt("pid");
        uid = metadata.getInt("uid");
        startTicks = metadata.getString("startTicks");
        nativeId = metadata.getString("nativeId");
        runtimeId = metadata.getString("runtimeId");
        if (bootId.isEmpty()
                || pid <= 0
                || uid < 0
                || startTicks.isEmpty()
                || nativeId.isEmpty()
                || runtimeId.isEmpty()
                || targetLoader == null) {
            throw new IllegalArgumentException("Invalid native runtime identity");
        }
        identity = bootId + ":" + pid + ":" + startTicks + ":" + uid;
        MethodAccess methods = new MethodAccess(SystemServerClasses.android(targetLoader));
        if (USE_DIRECT_HOOKS) {
            callers = null;
            hooks = new HookRegistry(HookBridge.backend());
        } else {
            callers = new AndroidCallers(targetLoader);
            hooks = new HookRegistry(HookBridge.backend(callers));
        }
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            Thread thread = new Thread(task, "VoyahTuneAgent");
                            thread.setDaemon(true);
                            return thread;
                        });
        ModuleScope.Scheduler scheduler =
                (delay, task) -> {
                    var future = executor.schedule(task, delay, TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                };
        session =
                new AgentSession(
                        identity,
                        code ->
                                new ModuleRuntime(
                                        code,
                                        scheduler,
                                        id -> {
                                            if (id.equals("phone")) {
                                                return new PhoneModule(methods, hooks);
                                            }
                                            if (id.equals("windows")) {
                                                return AndroidWindowsPlatform.create(
                                                        (Context) applicationContext,
                                                        methods,
                                                        hooks);
                                            }
                                            if (id.equals("launcher")) {
                                                return new LauncherModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidLauncherPlatform(
                                                                (Context) applicationContext,
                                                                methods));
                                            }
                                            if (id.startsWith("navigation-audio.")) {
                                                Context context = (Context) applicationContext;
                                                if (context == null
                                                        || !id.equals(
                                                                "navigation-audio."
                                                                        + context
                                                                                .getPackageName())) {
                                                    throw new IllegalArgumentException(
                                                            "Navigation audio module package"
                                                                + " mismatch");
                                                }
                                                return new NavigationAudioModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidNavigationAudioPlatform(
                                                                context, methods));
                                            }
                                            if (id.startsWith("client.")) {
                                                Context context = (Context) applicationContext;
                                                if (context == null
                                                        || !id.equals(
                                                                "client."
                                                                        + context.getPackageName()))
                                                    throw new IllegalArgumentException(
                                                            "Client module package mismatch");
                                                return new ClientModule(
                                                        new ClientGeometryModule(
                                                                methods,
                                                                hooks,
                                                                new AndroidClientGeometryPlatform(
                                                                        context, methods)),
                                                        new ClientInputMethodModule(
                                                                methods,
                                                                hooks,
                                                                new AndroidClientInputMethodPlatform(
                                                                        methods)));
                                            }
                                            if (id.equals("steering")) {
                                                return new SteeringModule(
                                                        methods,
                                                        hooks,
                                                        (Context) applicationContext);
                                            }
                                            if (id.equals("apollo")) {
                                                return new ApolloModule(methods, hooks, bootId);
                                            }
                                            if (id.equals("keyboard-en")
                                                    || id.equals("keyboard-ru")) {
                                                return new KeyboardModule(
                                                        id,
                                                        methods,
                                                        hooks,
                                                        (Context) applicationContext);
                                            }
                                            if (id.equals("multidisplay")) {
                                                return new MultidisplayModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidMultidisplayPlatform(
                                                                (Context) applicationContext,
                                                                methods));
                                            }
                                            if (id.equals("vehicle-air")) {
                                                return new VehicleAirModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidDrivePersistence(
                                                                (Context) applicationContext),
                                                        failure ->
                                                                android.util.Log.e(
                                                                        "vt_vehicle_air",
                                                                        "Drive persistence failed"
                                                                            + " after accepted OEM"
                                                                            + " send",
                                                                        failure));
                                            }
                                            if (id.equals("drive-reset")) {
                                                return new DriveResetModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidDriveResetPlatform(
                                                                (Context) applicationContext,
                                                                methods));
                                            }
                                            if (id.equals("acc")) {
                                                return new AccModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidAccPlatform(
                                                                (Context) applicationContext,
                                                                methods));
                                            }
                                            if (id.equals("yandex-navigation")
                                                    || id.equals("dgis-navigation")) {
                                                return new NavigationSourceModule(
                                                        id,
                                                        methods,
                                                        hooks,
                                                        new AndroidNavigationSourcePlatform(
                                                                (Context) applicationContext));
                                            }
                                            if (id.equals("statusbar")) {
                                                return new StatusBarModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidStatusBarPlatform(
                                                                (Context) applicationContext,
                                                                methods));
                                            }
                                            if (id.equals("rds")) {
                                                return new RdsModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidRdsPlatform(
                                                                (Context) applicationContext,
                                                                methods));
                                            }
                                            if (id.equals("cluster")) {
                                                return new ClusterModule(
                                                        methods,
                                                        hooks,
                                                        new AndroidClusterPlatform(
                                                                (Context) applicationContext,
                                                                methods,
                                                                hooks));
                                            }
                                            if (id.equals("oem-media")) {
                                                return new OemMediaModule(
                                                        (Context) applicationContext,
                                                        methods,
                                                        hooks);
                                            }
                                            throw new UnsupportedOperationException(
                                                    "Java module unavailable: " + id);
                                        }));
    }

    public static synchronized String start(
            String metadata, ClassLoader targetLoader, Object applicationContext) throws Exception {
        JSONObject parsed = new JSONObject(metadata);
        if (current == null) {
            current = new AgentRuntime(parsed, targetLoader, applicationContext);
        } else if (!current.matches(parsed)) {
            return current.statusValue("restart-required", "runtime-identity-mismatch").toString();
        }
        return status();
    }

    public static String apply(String plan) throws Exception {
        AgentRuntime runtime = requireCurrent();
        try {
            JSONObject value = new JSONObject(plan);
            Set<String> fields =
                    Set.of("schema", "runtimeId", "revision", "modules", "configuration");
            Iterator<String> keys = value.keys();
            while (keys.hasNext()) {
                if (!fields.contains(keys.next())) {
                    throw new IllegalArgumentException("Unknown runtime plan field");
                }
            }
            if (!(value.get("schema") instanceof Integer) || value.getInt("schema") != 1) {
                throw new IllegalArgumentException("Unsupported runtime plan schema");
            }
            Object revision = value.get("revision");
            if (!(revision instanceof Integer) && !(revision instanceof Long)) {
                throw new IllegalArgumentException("Integer configuration revision required");
            }
            List<String> modules = new ArrayList<>();
            JSONArray requested = value.getJSONArray("modules");
            if (requested.length() > 64) {
                throw new IllegalArgumentException("Too many modules");
            }
            for (int index = 0; index < requested.length(); index++) {
                Object module = requested.get(index);
                if (!(module instanceof String) || ((String) module).isEmpty()) {
                    throw new IllegalArgumentException("Module ID required");
                }
                modules.add((String) module);
            }
            if (modules.contains("keyboard-en") && modules.contains("keyboard-ru")) {
                throw new IllegalArgumentException(
                        "Only one keyboard language module can own a process");
            }
            Map<String, String> configuration = new HashMap<>();
            JSONObject settings = value.getJSONObject("configuration");
            Iterator<String> names = settings.keys();
            while (names.hasNext()) {
                String key = names.next();
                Object setting = settings.get(key);
                if (!(setting instanceof String)) {
                    throw new IllegalArgumentException("String configuration value required");
                }
                configuration.put(key, (String) setting);
            }
            Object code = value.get("runtimeId");
            if (!(code instanceof String)) {
                throw new IllegalArgumentException("Runtime ID required");
            }
            runtime.session.apply(
                    runtime.identity,
                    (String) code,
                    ((Number) revision).longValue(),
                    modules,
                    configuration);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (runtime.session.snapshot().phase == ModuleRuntime.Phase.STARTING) {
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("Module preparation deadline exceeded");
                Thread.sleep(25);
            }
            return status();
        } catch (Throwable failure) {
            return runtime.statusValue(null, runtime.describeFailure(failure)).toString();
        } finally {
            runtime.releaseCallerIndex();
        }
    }

    public static String quiesce() throws Exception {
        AgentRuntime runtime = requireCurrent();
        Throwable failure = null;
        try {
            runtime.session.close();
        } catch (Throwable caught) {
            failure = caught;
        }
        try {
            runtime.hooks.close();
        } catch (Throwable caught) {
            if (failure == null) {
                failure = caught;
            } else {
                failure.addSuppressed(caught);
            }
        } finally {
            runtime.executor.shutdownNow();
            runtime.releaseCallerIndex();
        }
        if (failure != null) {
            runtime.quiesceFailure = failure;
            return runtime.statusValue("restart-required", runtime.describeFailure(failure))
                    .toString();
        }
        return status();
    }

    private void releaseCallerIndex() {
        if (callers != null) {
            callers.releaseIndex();
        }
    }

    public static String status() throws Exception {
        return requireCurrent().statusValue(null, null).toString();
    }

    private JSONObject statusValue(String override, String error) throws Exception {
        ModuleRuntime.Snapshot snapshot = session.snapshot();
        String state = snapshot == null ? "starting" : phase(snapshot.phase);
        if (hooks.requiresRestart()) {
            override = "restart-required";
            error = "native-hook-state-uncertain";
        }
        if (quiesceFailure != null) {
            override = "restart-required";
            error = describeFailure(quiesceFailure);
        }
        JSONObject result = new JSONObject();
        result.put("schema", 1);
        result.put("bootId", bootId);
        result.put("pid", pid);
        result.put("uid", uid);
        result.put("startTicks", startTicks);
        result.put("nativeId", nativeId);
        result.put("runtimeId", runtimeId);
        result.put("revision", snapshot == null ? -1 : snapshot.revision);
        result.put("state", override == null ? state : override);
        JSONObject modules = new JSONObject();
        if (snapshot != null) {
            for (String id : snapshot.modules) {
                modules.put(
                        id,
                        override != null
                                ? override
                                : snapshot.phase == ModuleRuntime.Phase.READY ? "active" : state);
            }
            if (snapshot.failure != null && error == null) {
                error = describeFailure(snapshot.failure);
            }
        }
        result.put("modules", modules);
        if (error != null) {
            result.put("error", error);
        }
        return result;
    }

    private String describeFailure(Throwable failure) {
        if (reportedFailure != failure) {
            reportedFailure = failure;
            android.util.Log.e("VoyahTuneAgent", "Java module failure in PID " + pid, failure);
        }
        return FailureDescription.format(failure);
    }

    private boolean matches(JSONObject metadata) throws Exception {
        return bootId.equals(metadata.getString("bootId"))
                && pid == metadata.getInt("pid")
                && uid == metadata.getInt("uid")
                && startTicks.equals(metadata.getString("startTicks"))
                && nativeId.equals(metadata.getString("nativeId"))
                && runtimeId.equals(metadata.getString("runtimeId"));
    }

    private static AgentRuntime requireCurrent() {
        AgentRuntime runtime = current;
        if (runtime == null) {
            throw new IllegalStateException(
                    "Native bootstrap has not initialized the Java runtime");
        }
        return runtime;
    }

    private static String phase(ModuleRuntime.Phase phase) {
        switch (phase) {
            case READY:
                return "ready";
            case CLOSED:
                return "quiesced";
            case STOPPING:
                return "quiescing";
            case RESTART_REQUIRED:
                return "restart-required";
            case FAILED:
                return "failed";
            default:
                return "starting";
        }
    }
}
