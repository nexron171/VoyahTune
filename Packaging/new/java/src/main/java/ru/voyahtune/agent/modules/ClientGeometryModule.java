package ru.voyahtune.agent.modules;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

public final class ClientGeometryModule implements ModuleRuntime.Module {
    public interface Platform {
        String packageName();
        boolean fullscreen() throws Exception;
        int density() throws Exception;
        List<?> roots() throws Exception;
        void update(Object view, Object attributes) throws Exception;
        void postMain(Runnable action) throws Exception;
        void drainMain(AutoCloseable cleanup) throws Exception;
        void watch(ResourceGroup resources, Runnable changed) throws Exception;
        void error(Exception failure);
    }

    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final WeakIdentityMap<Object, Width> originals = new WeakIdentityMap<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final ThreadLocal<Boolean> replaying = ThreadLocal.withInitial(() -> false);
    private Method setView;
    private Method setLayout;
    private Class<?> attributesClass;
    private ResourceGroup resources;
    private MapKitScale mapkit;
    private volatile boolean enabled;
    private volatile boolean pending = true;

    public ClientGeometryModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        setView = methods.require("android.view.ViewRootImpl", "setView", "android.view.View", "android.view.WindowManager$LayoutParams", "android.view.View", "int");
        setLayout = methods.require("android.view.ViewRootImpl", "setLayoutParams", "android.view.WindowManager$LayoutParams", "boolean");
        attributesClass = methods.type("android.view.WindowManager$LayoutParams");
        methods.require("android.view.WindowManager$LayoutParams", "copyFrom", "android.view.WindowManager$LayoutParams");
        if (setView.getReturnType() != void.class || setLayout.getReturnType() != void.class) throw new NoSuchMethodException("Client window ABI mismatch");
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        resources = new ResourceGroup();
        scope.own(resources);
        mapkit = new MapKitScale(methods, hooks, resources, scope, platform);
        resources.own(() -> platform.drainMain(() -> {
            enabled = false;
            Exception failure = null;
            try { replay(true); }
            catch (Exception caught) { failure = caught; }
            try { mapkit.restore(); }
            catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
            if (failure != null) throw failure;
        }));
        install(scope, setView, 1);
        install(scope, setLayout, 0);
        platform.watch(resources, () -> requestReplay(scope));
    }

    private void install(ModuleScope scope, Method method, int argument) throws Exception {
        resources.own(hooks.install("client-geometry", method, scope.callback(invocation -> {
            Object root = invocation.receiver();
            Object attributes = invocation.arguments()[argument];
            boolean affected = enabled && eligible(root, attributes);
            if (affected) invocation.arguments()[argument] = normalized(root, attributes);
            Object result = invocation.proceed();
            if (affected && !replaying.get()) requestReplay(scope);
            return result;
        })));
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        pending = true;
        scope.schedule(0, () -> requestReplay(scope));
    }

    @Override public boolean ready() { return !pending; }

    private void requestReplay(ModuleScope scope) {
        if (scope.stoppingOrFailed() || !queued.compareAndSet(false, true)) return;
        try {
            platform.postMain(() -> {
                queued.set(false);
                boolean[] ran = {false};
                scope.listener(() -> {
                    ran[0] = true;
                    refresh(scope);
                }).run();
                if (!ran[0] && !scope.stoppingOrFailed()) {
                    try { scope.schedule(1, () -> requestReplay(scope)); }
                    catch (Exception failure) { scope.requireRestart(failure); }
                }
            });
        } catch (Exception failure) { queued.set(false); scope.requireRestart(failure); }
    }

    private void refresh(ModuleScope scope) {
        try { enabled = platform.fullscreen(); }
        catch (Exception unavailable) { enabled = false; error(unavailable); }
        int dpi;
        try { dpi = platform.density(); }
        catch (Exception unavailable) { dpi = 0; error(unavailable); }
        try {
            mapkit.configure(dpi);
            replay(false);
            mapkit.replay(platform.roots());
            pending = false;
        } catch (Exception failure) { scope.requireRestart(failure); error(failure); }
    }

    private Object normalized(Object root, Object attributes) throws Exception {
        if (!replaying.get() && originals.get(root) == null) {
            if (originals.size() >= 256) return attributes;
            originals.put(root, new Width((Integer) OemAccess.read(attributes, "width")));
        }
        if (Integer.valueOf(-1).equals(OemAccess.read(attributes, "width"))) return attributes;
        return copy(attributes, -1);
    }

    private Object copy(Object attributes, int width) throws Exception {
        Object result = OemAccess.construct(attributesClass);
        OemAccess.call(result, "copyFrom", attributes);
        OemAccess.write(result, "width", width);
        return result;
    }

    private boolean eligible(Object root, Object attributes) {
        try {
            if (root == null || attributes == null || !Integer.valueOf(1).equals(OemAccess.read(attributes, "type"))) return false;
            int display = (Integer) OemAccess.call(root, "getDisplayId");
            return display == 0 || display == 1;
        } catch (Exception unavailable) { return false; }
    }

    private void replay(boolean closing) throws Exception {
        List<?> views = platform.roots();
        Exception failure = null;
        for (Object view : views) {
            try {
                Object root = OemAccess.call(view, "getViewRootImpl");
                Object attributes = OemAccess.call(view, "getLayoutParams");
                if (!eligible(root, attributes)) continue;
                replay(root, view, attributes);
            } catch (Exception unavailable) {
                if (!closing) error(unavailable);
                else if (failure == null) failure = unavailable;
                else failure.addSuppressed(unavailable);
            }
        }
        if (!enabled) {
            for (Object root : originals.keys()) {
                Width width = originals.get(root);
                Object view = width.view.get();
                if (view != null) {
                    try { replay(root, view, OemAccess.call(view, "getLayoutParams")); }
                    catch (Exception unavailable) {
                        if (failure == null) failure = unavailable;
                        else failure.addSuppressed(unavailable);
                    }
                } else originals.remove(root);
            }
        }
        if (failure != null) throw failure;
    }

    private void replay(Object root, Object view, Object attributes) throws Exception {
        int current = (Integer) OemAccess.read(attributes, "width");
        Width width = originals.get(root);
        if (enabled && width == null) {
            if (originals.size() >= 256) return;
            width = new Width(current);
            originals.put(root, width);
        }
        if (width == null) return;
        width.view = new WeakReference<>(view);
        int desired = enabled ? -1 : width.original;
        if (current != desired || !enabled) {
            replaying.set(true);
            try { platform.update(view, copy(attributes, desired)); }
            finally { replaying.remove(); }
        }
        if (!enabled) originals.remove(root);
    }

    private void error(Exception failure) {
        try { platform.error(failure); }
        catch (RuntimeException ignored) { /* Client diagnostics must preserve application behavior. */ }
    }

    private static final class Width {
        final int original;
        WeakReference<Object> view = new WeakReference<>(null);
        Width(int original) { this.original = original; }
    }
}
