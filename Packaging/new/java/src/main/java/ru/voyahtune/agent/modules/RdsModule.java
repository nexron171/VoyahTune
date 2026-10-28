package ru.voyahtune.agent.modules;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.RadioRestoreController;
import ru.voyahtune.agent.policy.RadioStation;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

public final class RdsModule implements ModuleRuntime.Module {
    @FunctionalInterface public interface Originals { Object call(String role, Object receiver, Object... arguments) throws Exception; }
    public interface Platform {
        void prepare() throws Exception;
        RadioStation saved();
        RadioRestoreController.IO restoration(ModuleScope scope, Originals originals) throws Exception;
        RadioStation snapshot(Object station) throws Exception;
        Object selectedStation();
        boolean ownedStation(Object station);
        boolean hasOwnedStation();
        void beginDispatch(RadioStation station);
        void finishDispatch() throws Exception;
        RadioStation dispatching();
        Object band(boolean fm) throws Exception;
        long playbackCalls();
        void postMain(Runnable action) throws Exception;
        void bridges(ModuleScope scope, HookRegistry hooks, RadioRestoreController controller, Originals originals) throws Exception;
        void restoreAccSelection(RadioRestoreController controller) throws Exception;
        default void startup() throws Exception {}
        void cleanup() throws Exception;
        void error(Exception failure);
    }

    public static final String MANAGER = "com.pateo.overSideRadio.base.dab.RdsManager";
    public static final String MODEL = "com.pateo.rdsapp.main.model.RdsDataModel";
    public static final String STATION = "com.adayo.proxy.dab.aidl.beans.RadioStation";
    public static final String BAND = "com.adayo.proxy.dab.DABConstants$BAND_DEF";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final Map<String, Method> targets = new LinkedHashMap<>();
    private final ThreadLocal<Set<String>> bypassed = ThreadLocal.withInitial(HashSet::new);
    private final AtomicBoolean automaticPlaybackHandled = new AtomicBoolean();
    private RadioRestoreController.IO io;
    private RadioRestoreController controller;
    private boolean configured;

    public RdsModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        require("tune", MANAGER, "playStation", STATION);
        require("play", MANAGER, "play");
        require("resume", MANAGER, "resumePlay");
        require("model-play", MODEL, "play");
        require("model-current", MODEL, "getCurRadioStation");
        require("model-private-current", MODEL, "getCurStation");
        require("model-update", MODEL + "$1", "onCurrentStationUpdate", STATION);
        require("pause", MANAGER, "pause");
        require("pause-no-abandon", MANAGER, "pauseNoAbandon");
        require("scan", MANAGER, "fullScan");
        require("scan-band", MANAGER, "fullScan", BAND);
        require("switch-band", MANAGER, "switchRadioSource", BAND);
        require("update", MANAGER + "$3", "onCurrentStationUpdate", STATION);
        require("scan-state", MANAGER + "$3", "onFreqSeekStateChanged", "com.adayo.proxy.dab.DABConstants$ScanState");
        require("init", MANAGER, "initData");
        require("audio-status", MANAGER + "$4", "onAudioPlayStatus", "boolean");
        methods.require(MANAGER, "requestAudioFocus");
        platform.prepare();
    }

    private void require(String role, String owner, String method, String... arguments) throws Exception {
        targets.put(role, methods.require(owner, method, arguments));
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.own(platform::cleanup);
        io = platform.restoration(scope, this::original);
        controller = new RadioRestoreController(io, platform.saved());
        scope.own(controller);
        for (Map.Entry<String, Method> entry : targets.entrySet()) {
            String role = entry.getKey();
            scope.own(hooks.install("rds", entry.getValue(), scope.callback(invocation -> {
                if (bypassed.get().contains(role)) return invocation.proceed();
                return invoke(role, invocation);
            })));
        }
        platform.bridges(scope, hooks, controller, this::original);
    }

    private Object invoke(String role, HookRegistry.Invocation invocation) throws Throwable {
        switch (role) {
            case "model-current":
            case "model-private-current":
                Object selected = platform.selectedStation();
                return selected != null ? selected : invocation.proceed();
            case "model-update":
                if (platform.selectedStation() != null) invocation.arguments()[0] = platform.selectedStation();
                return invocation.proceed();
            case "tune":
                Object station = invocation.arguments()[0];
                if (station != null && platform.ownedStation(station)) {
                    platform.beginDispatch(platform.snapshot(station));
                    try { return invocation.proceed(); }
                    finally { platform.finishDispatch(); }
                }
                safely(() -> controller.select(platform.snapshot(station)));
                Object tuned = invocation.proceed();
                safely(() -> controller.observe(io.current()));
                return tuned;
            case "play":
            case "resume":
            case "model-play":
                invocation.disableFallback();
                resume(invocation);
                return null;
            case "pause":
            case "pause-no-abandon": safely(controller::pause); return invocation.proceed();
            case "scan":
            case "scan-band": safely(controller::scan); return invocation.proceed();
            case "switch-band":
                RadioStation dispatching = platform.dispatching();
                if (dispatching != null) invocation.arguments()[0] = platform.band(dispatching.fm);
                return invocation.proceed();
            case "update":
                Object result = invocation.proceed();
                safely(() -> controller.observe(platform.snapshot(invocation.arguments()[0])));
                return result;
            case "scan-state":
                Object scanResult = invocation.proceed();
                if ("FINISH".equals(String.valueOf(invocation.arguments()[0]))) safely(controller::scanFinished);
                return scanResult;
            case "init":
                Object initialized = invocation.proceed();
                safely(() -> {
                    controller.observe(io.current());
                    automaticPlaybackHandled.set(false);
                    resumeActiveRadio();
                });
                return initialized;
            case "audio-status":
                Object audioResult = invocation.proceed();
                safely(() -> {
                    if (!(Boolean) invocation.arguments()[0]) resumeActiveRadio();
                    else {
                        automaticPlaybackHandled.set(false);
                        if (!platform.hasOwnedStation() || !io.hasFocus()) controller.pause();
                    }
                });
                return audioResult;
            default: throw new IllegalArgumentException("Unknown radio hook role");
        }
    }

    private void resume(HookRegistry.Invocation invocation) throws Exception {
        boolean[] called = {false};
        long callsBefore = platform.playbackCalls();
        RadioRestoreController.Action fallback = () -> { called[0] = true; proceed(invocation); };
        try { controller.resume(fallback, false); }
        catch (Exception failure) {
            safely(controller::pause);
            if (called[0] || platform.playbackCalls() != callsBefore) throw failure;
            error(failure);
            fallback.run();
        }
    }

    private Object proceed(HookRegistry.Invocation invocation) throws Exception {
        try { return invocation.proceed(); }
        catch (Error failure) { throw failure; }
        catch (Exception failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException(failure); }
    }

    private void resumeActiveRadio() throws Exception {
        if (io.isPlaying() && automaticPlaybackHandled.compareAndSet(false, true)) controller.resume(() -> {}, true);
    }

    private Object original(String role, Object receiver, Object... arguments) throws Exception {
        Method method = targets.get(role);
        if (method == null) throw new IllegalArgumentException("Unknown radio original role");
        Set<String> bypass = bypassed.get();
        boolean added = bypass.add(role);
        try {
            method.setAccessible(true);
            try { return method.invoke(receiver, arguments); }
            catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Error) throw (Error) cause;
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IllegalStateException(cause);
            }
        } finally {
            if (added) bypass.remove(role);
            if (bypass.isEmpty()) bypassed.remove();
        }
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (configured) controller.cancelPending();
        configured = true;
        scope.schedule(0, () -> {
            try {
                platform.postMain(scope.listener(() -> {
                    safely(() -> platform.restoreAccSelection(controller));
                    safely(() -> controller.observe(io.current()));
                    safely(platform::startup);
                }));
            } catch (Exception unavailable) { error(unavailable); }
        });
    }

    private void safely(RadioRestoreController.Action operation) {
        try { operation.run(); }
        catch (Exception unavailable) { error(unavailable); }
    }

    private void error(Exception failure) {
        try { platform.error(failure); }
        catch (RuntimeException ignored) { /* Radio diagnostics cannot replay playback. */ }
    }
}
