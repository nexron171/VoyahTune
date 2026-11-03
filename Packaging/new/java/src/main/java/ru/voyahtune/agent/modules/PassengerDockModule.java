package ru.voyahtune.agent.modules;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

public final class PassengerDockModule implements ModuleRuntime.Module {
    private static final String NAVIGATION = "com.qinggan.launcher.navigation.NavigationBarSecond";
    private static final String ALL_APPS = "com.qinggan.secondlauncher.activity.AllAppActivity";
    private static final long[] BOOT_PASSES = {0, 800, 2500, 5000, 8000, 15000};
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final LauncherPlatform platform;
    private final List<Method> lifecycle = new ArrayList<>();
    private final WeakIdentityMap<Object, WeakReference<PassengerDockNavigation>> navigation =
            new WeakIdentityMap<>();
    private final WeakIdentityMap<Object, Boolean> pending = new WeakIdentityMap<>();
    private Class<?> navigationType;

    public PassengerDockModule(MethodAccess methods, HookRegistry hooks, LauncherPlatform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override
    public void prepare(Map<String, String> configuration) {
        if (navigationType != null) return;
        try {
            Class<?> candidate = methods.type(NAVIGATION);
            methods.type(ALL_APPS);
            methods.type("android.view.View$OnClickListener");
            methods.type("android.view.View$OnLayoutChangeListener");
            OemAccess.field(candidate, "mScreenUpRadioGroup");
            for (String name : List.of("initScreenUpViews", "updateTheme", "initAllAppShow",
                    "getScreenUpView")) bind(name);
            bind("showScreenAllAPPTab", "int", "boolean");
            bind("updateSelectedApp", "java.lang.String", "java.lang.String");
            navigationType = candidate;
        } catch (ReflectiveOperationException unavailable) {
            lifecycle.clear();
        }
    }

    private void bind(String name, String... parameters) throws ClassNotFoundException {
        Method method = methods.optional(NAVIGATION, name, parameters);
        if (method != null && !lifecycle.contains(method)) lifecycle.add(method);
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (navigationType == null) return;
        scope.own(() -> platform.drainMain(this::release));
        for (Method method : lifecycle) {
            try {
                scope.own(hooks.install("launcher-passenger", method, scope.callback(invocation -> {
                    Object result = invocation.proceed();
                    Object receiver = invocation.receiver();
                    if (navigationType.isInstance(receiver)) queue(scope, receiver);
                    return result;
                })));
            } catch (HookRegistry.RejectedException unsupported) {
                error(unsupported);
            }
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (navigationType == null) return;
        pending.keys().forEach(pending::remove);
        for (long delay : BOOT_PASSES) scope.schedule(delay, () -> post(scope, () -> {
            try {
                Object model = platform.model();
                Object controller = field(model, "mSecondScreenNavigationBar");
                Object nested = field(controller, "mNavigationBar");
                Object bar = nested == null ? controller : nested;
                if (navigationType.isInstance(bar)) reconcile(scope, bar);
            } catch (Exception failure) {
                error(failure);
            }
        }));
    }

    private void queue(ModuleScope scope, Object bar) {
        synchronized (pending) {
            if (pending.get(bar) != null) return;
            pending.put(bar, true);
        }
        post(scope, () -> {
            pending.remove(bar);
            reconcile(scope, bar);
        });
    }

    private void reconcile(ModuleScope scope, Object bar) {
        try {
            Object display = field(bar, "mScreenId");
            if (display instanceof Integer && (Integer) display != 1) return;
            Object group = field(bar, "mScreenUpRadioGroup");
            if (group == null) return;
            WeakReference<PassengerDockNavigation> reference = navigation.get(bar);
            PassengerDockNavigation current = reference == null ? null : reference.get();
            if (current != null && !current.matches(group)) {
                try {
                    current.close();
                } catch (Exception cleanup) {
                    scope.requireRestart(cleanup);
                    throw cleanup;
                }
                navigation.remove(bar);
                current = null;
            }
            if (current == null) {
                current = PassengerDockNavigation.attach(methods, platform, scope, bar, group);
                if (current == null) return;
                navigation.put(bar, new WeakReference<>(current));
            }
            current.reconcile();
        } catch (Exception failure) {
            error(failure);
        }
    }

    private void post(ModuleScope scope, Runnable action) {
        try {
            platform.postMain(scope.listener(action));
        } catch (Exception failure) {
            error(failure);
        }
    }

    private void release() throws Exception {
        Exception failure = null;
        for (Object bar : navigation.keys()) {
            WeakReference<PassengerDockNavigation> reference = navigation.get(bar);
            PassengerDockNavigation current = reference == null ? null : reference.get();
            if (current == null) continue;
            try {
                current.close();
                navigation.remove(bar);
            } catch (Exception caught) {
                if (failure == null) failure = caught;
                else failure.addSuppressed(caught);
            }
        }
        if (failure != null) throw failure;
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        if (owner == null) return null;
        try {
            return OemAccess.read(owner, name);
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private void error(Exception failure) {
        try {
            platform.error(failure);
        } catch (RuntimeException ignored) {
            // Optional passenger diagnostics must preserve the driver dock.
        }
    }
}
