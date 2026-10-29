package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.WindowPolicy;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

public final class WindowGeometryModule implements ModuleRuntime.Module {
    public interface Events {
        void reload();
        void screen(boolean interactive);
        void lift(int value);
    }

    public interface Platform {
        default WindowLayout.Access access() { return new WindowLayout.Reflection(); }
        WindowPolicy policy() throws Exception;
        boolean interactive();
        long now();
        int lift() throws Exception;
        void watch(ResourceGroup resources, Events events) throws Exception;
        void withWindowManager(AutoCloseable action) throws Exception;
        void traversal() throws Exception;
        void error(Exception failure);
    }

    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final ThreadLocal<Boolean> reparenting = ThreadLocal.withInitial(() -> false);
    private volatile WindowPolicy policy;
    private volatile boolean screenOn;
    private volatile boolean pending;
    private ResourceGroup hot;
    private WindowLayout layout;
    private WindowTaskDensity density;
    private Method layoutMethod;
    private Method configurationMethod;
    private Method displayMethod;
    private Method removeMethod;
    private AutoCloseable attachTimer;
    private AutoCloseable reloadTimer;
    private AutoCloseable replayTimer;
    private long attachAt;
    private long lastTransition = -1;
    private int rapidTransitions;
    private volatile boolean initial = true;

    public WindowGeometryModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        layoutMethod = methods.require("com.android.server.wm.DisplayPolicy", "layoutWindowLw", "com.android.server.wm.WindowState", "com.android.server.wm.WindowState", "com.android.server.wm.DisplayFrames");
        configurationMethod = methods.require("com.android.server.wm.ActivityRecord", "ensureActivityConfiguration", "int", "boolean", "boolean");
        displayMethod = methods.require("com.android.server.wm.ActivityRecord", "onDisplayChanged", "com.android.server.wm.DisplayContent");
        removeMethod = methods.require("com.android.server.wm.WindowState", "removeImmediately");
        methods.require("com.android.server.wm.WindowManagerService", "requestTraversal");
        if (layoutMethod.getReturnType() != void.class || configurationMethod.getReturnType() != boolean.class
                || displayMethod.getReturnType() != void.class || removeMethod.getReturnType() != void.class) throw new NoSuchMethodException("Window geometry ABI mismatch");
        for (String name : new String[] {"mRequestedWidth", "mRequestedHeight"}) {
            if (OemAccess.field(methods.type("com.android.server.wm.WindowState"), name).getType() != int.class) throw new NoSuchFieldException(name);
        }
        OemAccess.field(methods.type("com.android.server.wm.WindowManagerService"), "mGlobalLock");
        OemAccess.field(methods.type("com.android.server.wm.ActivityRecord"), "task");
        methods.type("com.android.server.wm.Task");
        methods.type("android.content.res.Configuration");
        if (initial) policy = platform.policy();
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        screenOn = platform.interactive();
        layout = new WindowLayout(platform.access(), scope::requireRestart);
        density = new WindowTaskDensity(methods.type("android.content.res.Configuration"));
        ResourceGroup resources = new ResourceGroup();
        scope.own(resources);
        resources.own(() -> restore(true));
        resources.own(hooks.install("windows-remove", removeMethod, scope.callback(invocation -> {
            try { layout.restore(invocation.receiver()); }
            catch (Exception failure) { scope.requireRestart(failure); }
            return invocation.proceed();
        })));
        resources.own(hooks.install("windows-reparent", displayMethod, scope.callback(invocation -> {
            Object result = invocation.proceed();
            if (!policy.enabled || !screenOn || reparenting.get()) return result;
            try {
                Object display = invocation.arguments()[0];
                if (display == null) return result;
                int destination = (Integer) OemAccess.call(display, "getDisplayId");
                if ((destination == 0 || destination == 1) && Integer.valueOf(destination).equals(OemAccess.call(invocation.receiver(), "getDisplayId"))) {
                    String name = (String) OemAccess.read(invocation.receiver(), "packageName");
                    Object task = OemAccess.read(invocation.receiver(), "task");
                    if (!WindowPolicy.blacklisted(name) && task != null) {
                        reparenting.set(true);
                        try { density.apply(task, name, policy); enqueue(scope, () -> replay(scope)); }
                        finally { reparenting.remove(); }
                    }
                }
            } catch (Exception unavailable) { error(unavailable); }
            return result;
        })));
        resources.own(() -> { if (hot != null) hot.close(); });
        platform.watch(resources, new Events() {
            @Override public void reload() { enqueue(scope, () -> reloadLater(scope)); }
            @Override public void screen(boolean interactive) { enqueue(scope, () -> screenChanged(scope, interactive)); }
            @Override public void lift(int value) { enqueue(scope, () -> liftChanged(scope, value)); }
        });
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        pending = true;
        scope.schedule(0, () -> {
            if (initial) {
                screenOn = platform.interactive();
                reconcile(scope);
                initial = false;
            } else {
                attachTimer = null;
                reloadTimer = null;
                replayTimer = null;
                reload(scope);
            }
        });
    }

    @Override public boolean ready() { return !initial && !pending; }
    public int trackedWindows() { return layout == null ? 0 : layout.size(); }
    public WindowPolicy currentPolicy() { return policy; }

    private void reloadLater(ModuleScope scope) throws Exception {
        cancel(reloadTimer);
        reloadTimer = scope.schedule(50, () -> { reloadTimer = null; reload(scope); });
    }

    private void reload(ModuleScope scope) {
        try { policy = platform.policy(); }
        catch (Exception invalid) { error(invalid); reconcile(scope); return; }
        try {
            restore(!policy.enabled);
            reconcile(scope);
        } catch (Exception failure) { scope.requireRestart(failure); }
    }

    private void reconcile(ModuleScope scope) {
        try {
            if (!policy.enabled || !screenOn) {
                cancel(attachTimer);
                cancel(replayTimer);
                attachTimer = replayTimer = null;
                attachAt = 0;
                pending = false;
                if (hot != null) { hot.close(); hot = null; }
            } else if (hot != null) { pending = false; replay(scope); }
            else if (attachTimer == null) {
                if (attachAt == 0) attachAt = platform.now() + 1000;
                pending = true;
                attachTimer = scope.schedule(Math.max(0, attachAt - platform.now()), () -> {
                    attachTimer = null;
                    attachAt = 0;
                    attach(scope);
                });
            }
        } catch (Exception failure) { scope.requireRestart(failure); }
    }

    private void restore(boolean all) throws Exception {
        platform.withWindowManager(() -> {
            Exception failure = null;
            try { layout.restoreAll(policy, all); }
            catch (Exception caught) { failure = caught; }
            try { density.restoreAll(policy, all); }
            catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
            if (failure != null) throw failure;
        });
    }

    private void attach(ModuleScope scope) {
        if (!policy.enabled || !screenOn || hot != null) return;
        ResourceGroup resources = new ResourceGroup();
        try {
            resources.own(hooks.install("windows-layout", layoutMethod, scope.callback(invocation -> {
                Object result = invocation.proceed();
                if (screenOn) layout.apply(invocation.arguments()[0], invocation.arguments()[2], policy);
                return result;
            })));
            resources.own(hooks.install("windows-density", configurationMethod, scope.callback(invocation -> {
                Object result = invocation.proceed();
                if (policy.enabled && screenOn) {
                    try { applyDensity(invocation.receiver()); }
                    catch (Exception unavailable) { error(unavailable); }
                }
                return result;
            })));
            hot = resources;
            pending = false;
            replay(scope);
        } catch (Exception failure) {
            try { resources.close(); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            scope.requireRestart(failure);
        }
    }

    private void applyDensity(Object activity) throws Exception {
        int display = (Integer) OemAccess.call(activity, "getDisplayId");
        String name = (String) OemAccess.read(activity, "packageName");
        if ((display == 0 || display == 1) && !WindowPolicy.blacklisted(name)) density.apply(OemAccess.read(activity, "task"), name, policy);
    }

    private void replay(ModuleScope scope) throws Exception {
        if (!policy.enabled || !screenOn || replayTimer != null) return;
        replayTimer = scope.schedule(0, () -> {
            replayTimer = null;
            try { platform.traversal(); }
            catch (Exception unavailable) { error(unavailable); }
        });
    }

    private void screenChanged(ModuleScope scope, boolean interactive) throws Exception {
        if (screenOn == interactive) return;
        long now = platform.now();
        rapidTransitions = lastTransition >= 0 && now >= lastTransition && now - lastTransition < 30000 ? rapidTransitions + 1 : 0;
        lastTransition = now;
        screenOn = interactive;
        if (interactive) {
            attachAt = now + (rapidTransitions >= 2 ? 5000 : 1000);
            reload(scope);
        } else reconcile(scope);
    }

    private void liftChanged(ModuleScope scope, int value) throws Exception {
        if ((value != 1 && value != 2) || value == policy.liftType || value != platform.lift()) return;
        policy = policy.withLift(value);
        replay(scope);
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void enqueue(ModuleScope scope, Work action) {
        try {
            scope.schedule(0, () -> {
                try { action.run(); }
                catch (Exception failure) { scope.requireRestart(failure); }
            });
        } catch (Exception failure) { scope.requireRestart(failure); }
    }
    private static void cancel(AutoCloseable timer) throws Exception { if (timer != null) timer.close(); }
    private void error(Exception failure) {
        try { platform.error(failure); }
        catch (RuntimeException ignored) { /* Window diagnostics must preserve the OEM result. */ }
    }
}
