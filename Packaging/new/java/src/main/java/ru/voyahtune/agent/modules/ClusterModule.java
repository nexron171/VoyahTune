package ru.voyahtune.agent.modules;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ClusterModule implements ModuleRuntime.Module {
    public interface Card {
        void prepare() throws Exception;

        void install(ModuleScope scope, ResourceGroup resources) throws Exception;

        void configure(ModuleScope scope) throws Exception;
    }

    public interface Platform {
        boolean enabled() throws Exception;

        boolean transliteration();

        Object findTarget() throws Exception;

        void postMain(Runnable action) throws Exception;

        void watch(ResourceGroup resources, Runnable changed) throws Exception;

        Card card() throws Exception;

        void error(Exception failure);
    }

    public static final String CONTRACT = "com.qinggan.cluster.IInstrumentClusterManagerService";
    public static final String INFO = "com.qinggan.cluster.info.NaviInfo";
    private static final String GUIDE_STATE = "com.qinggan.cluster.info.GuideState";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final Object targetLock = new Object();
    private final Set<Class<?>> hookedWriters = new HashSet<>();
    private final ThreadLocal<Boolean> clearing = ThreadLocal.withInitial(() -> false);
    private volatile boolean enabled = true;
    private Method units;
    private Method transact;
    private Class<?> contract;
    private Class<?> info;
    private Object stoppedState;
    private Object target;
    private Method targetUpdate;
    private ResourceGroup writerResources;
    private Card card;
    private boolean writerInstalled;
    private int attempts;

    public ClusterModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        try {
            units =
                    methods.optional(
                            "com.qinggan.cluster.GuidanceFormat",
                            "toDistanceUnit",
                            "android.content.Context",
                            "int");
        } catch (ClassNotFoundException absent) {
            units = null;
        }
        if (units != null && units.getReturnType() != String.class) units = null;
        try {
            contract = methods.type(CONTRACT);
            transact =
                    methods.require(
                            CONTRACT + "$Stub",
                            "onTransact",
                            "int",
                            "android.os.Parcel",
                            "android.os.Parcel",
                            "int");
            info = methods.type(INFO);
            stoppedState = OemAccess.read(methods.type(GUIDE_STATE), "MAN_STOP");
            methods.require(INFO, "setGuideState", GUIDE_STATE);
            methods.require(INFO, "setDisplay", "boolean");
            for (String name : new String[] {"setTbtIconId", "setNextDistance"})
                methods.require(INFO, name, "int");
            for (String name :
                    new String[] {"setCurrentRoadName", "setTurnRoadName", "setDestName"})
                methods.require(INFO, name, "java.lang.String");
            stopped();
        } catch (ReflectiveOperationException unsupported) {
            transact = null;
            error(unsupported);
        }
        card = platform.card();
        if (card != null) {
            try {
                card.prepare();
            } catch (ReflectiveOperationException unsupported) {
                card = null;
                error(unsupported);
            }
        }
        if (units == null && transact == null && card == null)
            throw new UnsupportedOperationException("No supported cluster ABI");
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        boolean installed = false;
        if (units != null) {
            try {
                scope.own(
                        hooks.install(
                                "cluster-units",
                                units,
                                scope.callback(
                                        invocation -> {
                                            boolean kilometers =
                                                    (Integer) invocation.arguments()[1] >= 1000;
                                            if (platform.transliteration())
                                                return kilometers ? "km" : "m";
                                            return kilometers ? "км" : "м";
                                        })));
                installed = true;
            } catch (HookRegistry.RejectedException unsupported) {
                error(unsupported);
            }
        }
        if (transact != null) {
            writerResources = new ResourceGroup();
            scope.own(writerResources);
            try {
                writerResources.own(
                        hooks.install(
                                "cluster-navigation",
                                transact,
                                scope.callback(
                                        invocation -> {
                                            try {
                                                capture(scope, invocation.receiver());
                                            } catch (Exception unavailable) {
                                                error(unavailable);
                                            }
                                            return invocation.proceed();
                                        })));
                platform.watch(writerResources, scope.listener(() -> refresh(scope)));
                writerInstalled = true;
                installed = true;
            } catch (HookRegistry.RestartRequiredException uncertain) {
                throw uncertain;
            } catch (Exception unsupported) {
                writerResources.close();
                error(unsupported);
            }
        }
        if (card != null) {
            ResourceGroup resources = new ResourceGroup();
            scope.own(resources);
            try {
                card.install(scope, resources);
                installed = true;
            } catch (HookRegistry.RestartRequiredException uncertain) {
                throw uncertain;
            } catch (Exception unsupported) {
                resources.close();
                card = null;
                error(unsupported);
            }
        }
        if (!installed)
            throw new UnsupportedOperationException(
                    "Cluster hooks rejected all supported capabilities");
        scope.own(
                () -> {
                    synchronized (targetLock) {
                        target = null;
                        targetUpdate = null;
                        hookedWriters.clear();
                    }
                });
    }

    private Object stopped() throws Exception {
        Object value = OemAccess.construct(info);
        OemAccess.call(value, "setGuideState", stoppedState);
        OemAccess.call(value, "setDisplay", false);
        OemAccess.call(value, "setTbtIconId", 0);
        OemAccess.call(value, "setNextDistance", 0);
        for (String name : new String[] {"setCurrentRoadName", "setTurnRoadName", "setDestName"})
            OemAccess.call(value, name, "");
        return value;
    }

    private void capture(ModuleScope scope, Object instance) throws Exception {
        if (instance == null || !contract.isInstance(instance)) return;
        synchronized (targetLock) {
            if (target == instance) return;
            Class<?> type = instance.getClass();
            Method update = methods.require(type.getName(), "updateNaviInfo", INFO);
            if (!hookedWriters.contains(type)) {
                writerResources.own(
                        hooks.install(
                                "cluster-navigation",
                                update,
                                scope.callback(
                                        invocation -> {
                                            if (!enabled && !clearing.get())
                                                invocation.arguments()[0] = stopped();
                                            return invocation.proceed();
                                        })));
                hookedWriters.add(type);
            }
            target = instance;
            targetUpdate = update;
        }
        if (!enabled) clear();
    }

    private void clear() {
        Object writer;
        Method update;
        synchronized (targetLock) {
            writer = target;
            update = targetUpdate;
        }
        if (enabled || writer == null) return;
        boolean previous = clearing.get();
        clearing.set(true);
        try {
            update.setAccessible(true);
            update.invoke(writer, stopped());
        } catch (Exception unavailable) {
            Throwable failure =
                    unavailable instanceof InvocationTargetException
                            ? ((InvocationTargetException) unavailable).getCause()
                            : unavailable;
            error(
                    failure instanceof Exception
                            ? (Exception) failure
                            : new IllegalStateException(failure));
        } finally {
            if (previous) clearing.set(true);
            else clearing.remove();
        }
    }

    private void refresh(ModuleScope scope) {
        boolean next;
        try {
            next = platform.enabled();
        } catch (Exception unavailable) {
            error(unavailable);
            return;
        }
        boolean wasEnabled = enabled;
        enabled = next;
        if (wasEnabled && !enabled) {
            try {
                scope.schedule(0, this::clear);
            } catch (Exception unavailable) {
                error(unavailable);
            }
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (writerInstalled) {
            refresh(scope);
            attempts = 0;
            scope.schedule(0, () -> discover(scope));
        }
        if (card != null) card.configure(scope);
    }

    private void discover(ModuleScope scope) {
        boolean found;
        synchronized (targetLock) {
            found = target != null;
        }
        if (found) {
            if (!enabled) clear();
            return;
        }
        try {
            platform.postMain(
                    scope.listener(
                            () -> {
                                try {
                                    Object discovered = platform.findTarget();
                                    if (discovered != null) {
                                        capture(scope, discovered);
                                        return;
                                    }
                                } catch (Exception unavailable) {
                                    error(unavailable);
                                }
                                if (++attempts < 15) {
                                    try {
                                        scope.schedule(2000, () -> discover(scope));
                                    } catch (Exception unavailable) {
                                        error(unavailable);
                                    }
                                }
                            }));
        } catch (Exception unavailable) {
            error(unavailable);
        }
    }

    private void error(Exception failure) {
        try {
            platform.error(failure);
        } catch (RuntimeException ignored) {
            /* Diagnostics cannot alter Binder transaction behavior. */
        }
    }
}
