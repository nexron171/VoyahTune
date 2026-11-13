package ru.voyahtune.agent.modules;

import android.content.pm.ApplicationInfo;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class PassengerHeadphonesModule implements ModuleRuntime.Module {
    @FunctionalInterface
    public interface ApplicationFlags {
        int read(String packageName) throws Exception;
    }

    private static final String ID = "passenger-headphones";
    private static final String SERVICE = "com.qinggan.audiopolicy.service.OSAudioPolicyService";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final ApplicationFlags applications;
    private final Map<Object, Set<String>> extendedPackages = new WeakHashMap<>();
    private Method packageGroup;
    private Method supportedPackage;
    private Method switchChannel;

    public PassengerHeadphonesModule(
            MethodAccess methods, HookRegistry hooks, ApplicationFlags applications) {
        this.methods = methods;
        this.hooks = hooks;
        this.applications = applications;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        // Follow the service's types: JADX's defpackage names are not runtime class names.
        Class<?> engine = methods.type(SERVICE).getDeclaredField("b").getType();
        Class<?> collector = engine.getDeclaredField("u").getType();
        if (collector.getDeclaredField("n").getType() != String[].class
                || collector.getDeclaredField("c").getType() != ConcurrentHashMap.class) {
            throw new NoSuchFieldException("Unsupported OEM BTSourceCollector layout");
        }
        packageGroup = query(collector, "a", String.class, String.class);
        supportedPackage = query(collector, "d", boolean.class, String.class);
        switchChannel = query(collector, "b", void.class, String.class, int.class);
        query(collector, "a", boolean.class, String.class, int.class, int.class);
        query(collector, "a", boolean.class, String.class, int.class, int.class, int.class);
        query(collector, "e", boolean.class);
        switchChannel.setAccessible(true);
    }

    private static Method query(
            Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("Unsupported OEM headphone method: " + method);
        }
        return method;
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.own(this::restoreChannels);
        scope.own(
                hooks.install(
                        ID,
                        packageGroup,
                        scope.callback(
                                invocation -> {
                                    Object original = invocation.proceed();
                                    String packageName = (String) invocation.arguments()[0];
                                    if (original != null || !isThirdParty(packageName)) {
                                        return original;
                                    }
                                    synchronized (extendedPackages) {
                                        extendedPackages
                                                .computeIfAbsent(
                                                        invocation.receiver(),
                                                        ignored -> new HashSet<>())
                                                .add(packageName);
                                    }
                                    return packageName;
                                })));
        scope.own(
                hooks.install(
                        ID,
                        supportedPackage,
                        scope.callback(
                                invocation -> {
                                    boolean original = (Boolean) invocation.proceed();
                                    return original
                                            || isThirdParty((String) invocation.arguments()[0]);
                                })));
    }

    private boolean isThirdParty(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            int flags = applications.read(packageName);
            return (flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                    == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private void restoreChannels() throws Exception {
        Map<Object, Set<String>> snapshot;
        synchronized (extendedPackages) {
            snapshot = new HashMap<>(extendedPackages);
        }
        // OEM keeps package routes after hooks are removed; release only our additions.
        for (Map.Entry<Object, Set<String>> entry : snapshot.entrySet()) {
            for (String packageName : entry.getValue()) {
                switchChannel.invoke(entry.getKey(), packageName, 0);
            }
        }
        synchronized (extendedPackages) {
            extendedPackages.clear();
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
