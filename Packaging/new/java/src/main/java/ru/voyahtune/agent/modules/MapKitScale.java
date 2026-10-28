package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

final class MapKitScale {
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final ResourceGroup resources;
    private final ModuleScope scope;
    private final ClientGeometryModule.Platform platform;
    private final WeakIdentityMap<Object, Scale> windows = new WeakIdentityMap<>();
    private final Set<Method> installed = new HashSet<>();
    private final Set<Class<?>> pendingClasses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Class<?>> preparedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private final boolean supportedPackage;
    private Class<?> mapView;
    private Class<?> group;
    private volatile int dpi;
    private boolean available;

    MapKitScale(MethodAccess methods, HookRegistry hooks, ResourceGroup resources, ModuleScope scope,
        ClientGeometryModule.Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.resources = resources;
        this.scope = scope;
        this.platform = platform;
        supportedPackage = Set.of("ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.yango.maps.android")
                               .contains(platform.packageName());
    }

    void install() throws Exception {
        if (supportedPackage)
            available = installFactories();
    }

    boolean ready() {
        synchronized (pendingClasses) {
            return pendingClasses.isEmpty();
        }
    }

    void configuring() {
        synchronized (pendingClasses) {
            pendingClasses.removeIf(owner -> !preparedClasses.contains(owner));
        }
    }

    void configure(int density) throws Exception {
        dpi = supportedPackage && density >= 100 && density <= 640 ? density : 0;
        if (!supportedPackage)
            return;
        if (!available && dpi > 0)
            throw new NoSuchMethodException("MapKit renderer density API unavailable");
        for (Object window : windows.keys())
            apply(window);
    }

    private boolean installFactories() throws Exception {
        try {
            methods.type("com.yandex.mapkit.map.MapWindow");
        } catch (ClassNotFoundException absent) {
            return false;
        }
        boolean available = false;
        try {
            mapView = methods.type("com.yandex.mapkit.mapview.MapView");
            Method getter = methods.require("com.yandex.mapkit.mapview.MapView", "getMapWindow");
            installFactory(getter);
            available = true;
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            mapView = null;
        }
        try {
            for (Method factory :
                methods.overloads("com.yandex.mapkit.internal.MapKitBinding", "createMapWindow")) {
                installFactory(factory);
                available = true;
            }
        } catch (
            ClassNotFoundException absent) { /* A MapView factory is sufficient on older MapKit versions. */
        }
        group = methods.type("android.view.ViewGroup");
        return available;
    }

    private synchronized void installFactory(Method factory) throws Exception {
        if (installed.contains(factory))
            return;
        resources.own(hooks.install("client-mapkit-factory", factory, scope.callback(invocation -> {
            Object result = invocation.proceed();
            try {
                apply(result);
            } catch (Exception unavailable) {
                scope.requireRestart(unavailable);
                error(unavailable);
            }
            return result;
        })));
        installed.add(factory);
    }

    private synchronized void installSetter(Class<?> owner) throws Exception {
        MethodAccess concrete = new MethodAccess(name -> Class.forName(name, false, owner.getClassLoader()));
        Method setter = concrete.require(owner.getName(), "setScaleFactor", "float");
        if (installed.contains(setter))
            return;
        if (setter.getReturnType() != void.class)
            throw new NoSuchMethodException("MapKit scale setter must return void");
        resources.own(hooks.install("client-mapkit-scale", setter, scope.callback(invocation -> {
            if (!applying.get()) {
                Scale scale = remember(invocation.receiver());
                float desired = (Float) invocation.arguments()[0];
                if (dpi > 0) {
                    invocation.arguments()[0] = dpi / 160f;
                    scale.applied = dpi / 160f;
                    scale.owned = true;
                } else if (Float.isFinite(desired) && desired > 0)
                    scale.baseline = desired;
            }
            return invocation.proceed();
        })));
        installed.add(setter);
    }

    private Scale remember(Object window) throws Exception {
        Scale known = windows.get(window);
        if (known != null)
            return known;
        if (windows.size() >= 256)
            throw new IllegalStateException("MapKit window ownership limit reached");
        float current = ((Number) OemAccess.call(window, "getScaleFactor")).floatValue();
        Scale scale = new Scale(Float.isFinite(current) && current > 0 ? current : 1f);
        windows.put(window, scale);
        return scale;
    }

    private void apply(Object window) throws Exception {
        if (window == null || !Boolean.TRUE.equals(OemAccess.call(window, "isValid")))
            return;
        Scale scale = remember(window);
        if (!prepareWindow(window.getClass()))
            return;
        float desired = dpi > 0 ? dpi / 160f : scale.baseline;
        float current = ((Number) OemAccess.call(window, "getScaleFactor")).floatValue();
        if (Math.abs(current - desired) >= 0.0001f) {
            scale.applied = desired;
            scale.owned = true;
            change(window, desired);
        }
        if (dpi == 0)
            scale.owned = false;
    }

    private boolean prepareWindow(Class<?> owner) throws Exception {
        synchronized (pendingClasses) {
            if (preparedClasses.contains(owner))
                return true;
            if (!pendingClasses.add(owner))
                return false;
        }
        // ART caller invalidation must never run from a renderer callback on the UI thread.
        scope.schedule(0, () -> {
            try {
                installSetter(owner);
                synchronized (pendingClasses) {
                    preparedClasses.add(owner);
                }
                replayPrepared(owner);
            } catch (Exception failure) {
                scope.requireRestart(failure);
                error(failure);
            }
        });
        return false;
    }

    private void replayPrepared(Class<?> owner) throws Exception {
        platform.postMain(() -> {
            boolean[] ran = {false};
            scope
                .listener(() -> {
                    ran[0] = true;
                    try {
                        for (Object window : windows.keys()) {
                            if (window.getClass() == owner)
                                apply(window);
                        }
                        synchronized (pendingClasses) {
                            pendingClasses.remove(owner);
                        }
                    } catch (Exception failure) {
                        scope.requireRestart(failure);
                        error(failure);
                    }
                })
                .run();
            if (!ran[0] && !scope.stoppingOrFailed()) {
                try {
                    scope.schedule(1, () -> {
                        try {
                            replayPrepared(owner);
                        } catch (Exception failure) {
                            scope.requireRestart(failure);
                            error(failure);
                        }
                    });
                } catch (Exception failure) {
                    scope.requireRestart(failure);
                    error(failure);
                }
            }
        });
    }

    private void change(Object window, float scale) throws Exception {
        applying.set(true);
        try {
            OemAccess.call(window, "setScaleFactor", scale);
        } finally {
            applying.remove();
        }
    }

    void replay(List<?> roots) throws Exception {
        if (!supportedPackage || mapView == null)
            return;
        ArrayDeque<Object> remaining = new ArrayDeque<>();
        for (Object root : roots)
            if (root != null)
                remaining.add(root);
        int visited = 0;
        while (!remaining.isEmpty()) {
            if (++visited > 4096)
                throw new IllegalStateException("MapKit view tree limit reached");
            Object view = remaining.removeFirst();
            if (mapView.isInstance(view))
                apply(OemAccess.call(view, "getMapWindow"));
            if (group.isInstance(view)) {
                int count = (Integer) OemAccess.call(view, "getChildCount");
                for (int index = 0; index < count; index++) {
                    Object child = OemAccess.call(view, "getChildAt", index);
                    if (child != null)
                        remaining.add(child);
                }
            }
        }
    }

    void restore() throws Exception {
        Exception failure = null;
        dpi = 0;
        for (Object window : windows.keys()) {
            Scale scale = windows.get(window);
            try {
                if (scale.owned && Boolean.TRUE.equals(OemAccess.call(window, "isValid"))) {
                    float current = ((Number) OemAccess.call(window, "getScaleFactor")).floatValue();
                    if (Math.abs(current - scale.applied) < 0.0001f)
                        change(window, scale.baseline);
                }
                windows.remove(window);
            } catch (Exception caught) {
                if (failure == null)
                    failure = caught;
                else
                    failure.addSuppressed(caught);
            }
        }
        if (failure != null)
            throw failure;
    }

    private void error(Exception failure) {
        try {
            platform.error(failure);
        } catch (RuntimeException ignored) { /* Renderer diagnostics must preserve factory results. */
        }
    }

    private static final class Scale {
        volatile float baseline;
        volatile float applied;
        volatile boolean owned;
        Scale(float baseline) {
            this.baseline = baseline;
        }
    }
}
