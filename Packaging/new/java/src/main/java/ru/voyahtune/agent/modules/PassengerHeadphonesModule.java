package ru.voyahtune.agent.modules;

import android.content.pm.ApplicationInfo;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PassengerHeadphonesModule implements ModuleRuntime.Module {
    public interface Platform {
        int read(String packageName) throws Exception;

        Object engine() throws Exception;

        Map<String, Integer> displays() throws Exception;

        void onCollector(Object collector, ModuleScope.ConfigurationAction action) throws Exception;
    }

    private static final String ID = "passenger-headphones";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final Map<Object, CollectorState> collectors = new ConcurrentHashMap<>();
    private PassengerHeadphonesOem oem;
    private volatile boolean initialized;
    private volatile Object activeCollector;

    public PassengerHeadphonesModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        oem = new PassengerHeadphonesOem(methods);
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        HookRegistry.Callback trackedRequest = scope.callback(HookRegistry.Invocation::proceed);
        // Call/route guards outlive admission hooks and remain active until routes are reset.
        scope.own(hooks.install(ID, oem.switchChannel, invocation -> switchChannel(scope, invocation)));
        scope.own(hooks.install(ID, oem.requestFocus,
                invocation -> requestFocus(scope, trackedRequest, invocation)));
        scope.own(this::restore);
        install(scope, oem.packageGroup, invocation -> {
            Object original = invocation.proceed();
            String packageName = (String) invocation.arguments()[0];
            if (original != null || !isThirdParty(packageName)) return original;
            state(invocation.receiver()).packages.add(packageName);
            return packageName;
        });
        install(scope, oem.supportedPackage, invocation -> {
            boolean original = (Boolean) invocation.proceed();
            return original || extendsPackage(invocation.receiver(), (String) invocation.arguments()[0]);
        });
        install(scope, oem.matchAttributes, this::matchMedia);
        install(scope, oem.matchDisplay, this::matchMedia);
        install(scope, oem.supportsVolume, invocation -> {
            boolean original = (Boolean) invocation.proceed();
            if (original) return true;
            String packageName = (String) invocation.arguments()[0];
            Object collector = activeCollector;
            return collector != null && extendsPackage(collector, packageName) && !communication(collector, packageName);
        });
        // These scopes cover the whole OEM operation, including the eventual route write.
        install(scope, oem.switchDisplay, invocation -> switchDisplay(scope, invocation));
        install(scope, oem.connectionChanged, HookRegistry.Invocation::proceed);
        install(scope, oem.requestSourceFocus, this::requestSourceFocus);
        install(scope, oem.serviceCreated, invocation -> {
            Object result = invocation.proceed();
            initialized = false;
            activeCollector = null;
            scope.schedule(0, () -> initialize(scope));
            return result;
        });
    }

    private void install(ModuleScope scope, Method method, HookRegistry.Callback callback) throws Exception {
        scope.own(hooks.install(ID, method, scope.callback(callback)));
    }

    private Object matchMedia(HookRegistry.Invocation invocation) throws Throwable {
        Object[] arguments = invocation.arguments();
        String packageName = (String) arguments[0];
        if (!extendsPackage(invocation.receiver(), packageName)) return invocation.proceed();
        if (communication(invocation.receiver(), packageName)) return false;
        int content = (Integer) arguments[2];
        if ((Integer) arguments[1] != 1 || (content != 1 && content != 3)) return invocation.proceed();
        // Adapt only this predicate; the actual focus client retains its original media attributes.
        arguments[2] = 2;
        try {
            return invocation.proceed();
        } finally {
            arguments[2] = content;
        }
    }

    private Object switchDisplay(ModuleScope scope, HookRegistry.Invocation invocation) throws Throwable {
        Object bundle = invocation.arguments()[0];
        if (bundle == null) return invocation.proceed();
        Object value = OemAccess.call(bundle, "getCharSequence", "appinfo");
        String packageName = value == null ? null : value.toString();
        int display = ((Number) OemAccess.call(bundle, "getShort", "disp")).intValue();
        Object collector = invocation.receiver();
        if (display == 0 && extendsPackage(collector, packageName)) {
            invocation.disableFallback();
            try {
                // OEM display-0 handling detaches any source; only migrate this extension's owner.
                oem.displays(collector).put(packageName, 0);
                oem.restoreFocus(collector, Set.of(packageName));
                oem.route(collector, packageName, 0);
                return null;
            } catch (Exception failure) {
                scope.requireRestart(failure);
                throw failure;
            }
        }
        return invocation.proceed();
    }

    private Object switchChannel(ModuleScope scope, HookRegistry.Invocation invocation) throws Throwable {
        String packageName = (String) invocation.arguments()[0];
        if (!owned(invocation.receiver(), packageName)) return invocation.proceed();
        invocation.disableFallback();
        try {
            synchronized (oem.sourceLock) {
                if (Integer.valueOf(1).equals(invocation.arguments()[1])
                        && communication(invocation.receiver(), packageName)) {
                    invocation.arguments()[1] = 0;
                }
                return invocation.proceed();
            }
        } catch (Exception failure) {
            scope.requireRestart(failure);
            throw failure;
        }
    }

    private Object requestFocus(ModuleScope scope, HookRegistry.Callback trackedRequest,
            HookRegistry.Invocation invocation) throws Throwable {
        Object collector = oem.collectorForFocus(invocation.receiver());
        String packageName = (String) invocation.arguments()[5];
        int usage = (Integer) invocation.arguments()[3];
        if (!PassengerHeadphonesOem.communication(usage)
                || (!owned(collector, packageName) && !extendsPackage(collector, packageName))) {
            return trackedRequest.invoke(invocation);
        }
        invocation.disableFallback();
        CollectorState state = state(collector);
        state.pendingCalls.merge(packageName, 1, Integer::sum);
        try {
            oem.stop(oem.detachOwnedSource(collector, Set.of(packageName)));
            oem.route(collector, packageName, 0);
            return trackedRequest.invoke(invocation);
        } catch (Exception failure) {
            scope.requireRestart(failure);
            throw failure;
        } finally {
            state.pendingCalls.compute(packageName, (name, count) -> count == 1 ? null : count - 1);
        }
    }

    private Object requestSourceFocus(HookRegistry.Invocation invocation) throws Throwable {
        Object collector = oem.collectorForFocus(invocation.receiver());
        String packageName = (String) invocation.arguments()[5];
        if (!extendsPackage(collector, packageName)) return invocation.proceed();
        synchronized (oem.sourceLock) {
            if ((Integer) invocation.arguments()[3] == 1 && !communication(collector, packageName)) {
                return invocation.proceed();
            }
        }
        // Never acquire the cabin monitor while holding the source monitor.
        invocation.disableFallback();
        return PassengerHeadphonesOem.invoke(oem.requestFocus, invocation.receiver(), invocation.arguments());
    }

    private boolean communication(Object collector, String packageName) throws Exception {
        return state(collector).pendingCalls.containsKey(packageName) || oem.hasCommunication(collector, packageName);
    }

    private boolean owned(Object collector, String packageName) {
        return packageName != null && state(collector).packages.contains(packageName);
    }

    private boolean extendsPackage(Object collector, String packageName) throws Exception {
        if (!isThirdParty(packageName) || oem.stockPackage(collector, packageName)) return false;
        state(collector).packages.add(packageName);
        return true;
    }

    private boolean isThirdParty(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            int flags = platform.read(packageName);
            return (flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private CollectorState state(Object collector) {
        return collectors.computeIfAbsent(collector, ignored -> new CollectorState());
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (!initialized) scope.schedule(0, () -> initialize(scope));
    }

    private void initialize(ModuleScope scope) {
        try {
            Object engine = platform.engine();
            if (engine == null) {
                scope.schedule(100, () -> initialize(scope));
                return;
            }
            Object collector = oem.collector(engine);
            activeCollector = collector;
            Map<String, Integer> displays = platform.displays();
            platform.onCollector(collector, () -> {
                for (Map.Entry<String, Integer> entry : displays.entrySet()) {
                    int display = entry.getValue();
                    if ((display == 0 || display == 1) && extendsPackage(collector, entry.getKey())) {
                        // A display event received during the snapshot is newer than that snapshot.
                        oem.displays(collector).putIfAbsent(entry.getKey(), display);
                    }
                }
                oem.reconcilePlayingMedia(collector, state(collector).packages);
            });
            initialized = true;
        } catch (Exception failure) {
            scope.requireRestart(failure);
        }
    }

    @Override
    public boolean ready() {
        return initialized;
    }

    private void restore() throws Exception {
        Set<String> packages = new HashSet<>();
        for (CollectorState state : collectors.values()) packages.addAll(state.packages);
        Exception failure = null;
        Object currentCollector = null;
        try {
            Object engine = platform.engine();
            if (engine != null) currentCollector = oem.collector(engine);
        } catch (Exception caught) {
            failure = caught;
        }
        try {
            if (currentCollector != null) {
                Object collector = currentCollector;
                // Routes are process-wide; a recreated service owns the live focus and handler.
                platform.onCollector(collector, () -> restore(collector, packages));
            } else {
                for (Map.Entry<Object, CollectorState> entry : collectors.entrySet()) {
                    try {
                        restoreRoutes(entry.getKey(), entry.getValue().packages);
                    } catch (Exception caught) {
                        if (failure == null) failure = caught;
                        else failure.addSuppressed(caught);
                    }
                }
            }
        } catch (Exception caught) {
            if (failure == null) failure = caught;
            else failure.addSuppressed(caught);
        } finally {
            collectors.clear();
            activeCollector = null;
            initialized = false;
        }
        if (failure != null) throw failure;
    }

    private void restore(Object collector, Set<String> packages) throws Exception {
        Exception failure = null;
        try {
            oem.restoreFocus(collector, packages);
        } catch (Exception caught) {
            failure = caught;
        }
        try {
            restoreRoutes(collector, packages);
        } catch (Exception caught) {
            if (failure == null) failure = caught;
            else failure.addSuppressed(caught);
        }
        if (failure != null) throw failure;
    }

    private void restoreRoutes(Object collector, Set<String> packages) throws Exception {
        Exception failure = null;
        for (String packageName : packages) {
            try {
                oem.route(collector, packageName, 0);
                oem.displays(collector).remove(packageName);
            } catch (Exception caught) {
                if (failure == null) failure = caught;
                else failure.addSuppressed(caught);
            }
        }
        if (failure != null) throw failure;
    }

    private static final class CollectorState {
        final Set<String> packages = ConcurrentHashMap.newKeySet();
        final Map<String, Integer> pendingCalls = new ConcurrentHashMap<>();
    }
}
