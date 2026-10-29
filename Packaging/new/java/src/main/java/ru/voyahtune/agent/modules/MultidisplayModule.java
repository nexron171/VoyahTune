package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class MultidisplayModule implements ModuleRuntime.Module {
    public interface Platform {
        boolean enabled() throws Exception;
        boolean supportsTransfer();
        String top(int display, int field) throws Exception;
        AutoCloseable reloadReceiver(Runnable reload) throws Exception;
        void trace(String event, Object[] arguments);
    }

    private static final String OWNER = "com.qinggan.systemservice.multidisplay.MultiDisplayImpl";
    private static final List<String> NEVER = List.of("com.qinggan.app.launcher", "com.qinggan.mainlauncher", "com.android.systemui");
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final Map<Method, Integer> whitelist = new LinkedHashMap<>();
    private final List<Method> diagnostics = new ArrayList<>();
    private final Map<String, Integer> traceCounts = new LinkedHashMap<>();
    private volatile boolean enabled = true;
    private Method swap;
    private boolean prepared;

    public MultidisplayModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        if (prepared) return;
        for (Method method : methods.overloads(OWNER, "isWhiteListApp")) {
            if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) continue;
            Class<?>[] parameters = method.getParameterTypes();
            int stringIndex = -1;
            int strings = 0;
            for (int index = 0; index < parameters.length; index++) {
                if (parameters[index] == String.class) {
                    stringIndex = index;
                    strings++;
                }
            }
            if (strings == 1) whitelist.put(method, stringIndex);
        }
        if (whitelist.isEmpty()) throw new NoSuchMethodException("No compatible MultiDisplayImpl whitelist overload");
        Method move = methods.optional(OWNER, "moveActivity", "java.lang.String", "java.lang.String", "boolean");
        Method candidate = methods.optional(OWNER, "swapActivity");
        if (candidate != null && move != null && platform.supportsTransfer()
                && (candidate.getReturnType() == boolean.class || candidate.getReturnType() == Boolean.class)
                && (move.getReturnType() == boolean.class || move.getReturnType() == Boolean.class)) swap = candidate;
        diagnostics.addAll(methods.overloads(OWNER, "showDelayDialog"));
        diagnostics.addAll(methods.overloads(OWNER, "setEnableActivityAnimation"));
        prepared = true;
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        refresh();
        for (Map.Entry<Method, Integer> entry : whitelist.entrySet()) {
            scope.own(hooks.install("multidisplay", entry.getKey(), scope.callback(invocation -> {
                String packageName = (String) invocation.arguments()[entry.getValue()];
                if (!enabled) return invocation.proceed();
                return !never(packageName);
            })));
        }
        if (swap != null) {
            scope.own(hooks.install("multidisplay", swap, scope.callback(invocation -> {
                if (enabled) {
                    try {
                        String first = platform.top(0, 1);
                        String second = platform.top(1, 1);
                        if (never(first) != never(second)) {
                            int display = never(first) ? 1 : 0;
                            String activity = platform.top(display, 2);
                            if (activity != null && !activity.isEmpty()
                                    && Boolean.TRUE.equals(OemAccess.call(invocation.receiver(), "moveActivity",
                                            display == 0 ? first : second, activity, false))) return true;
                        }
                    } catch (Exception failure) {
                        trace("single-app-transfer-unavailable", new Object[] {failure.getClass().getName()}, 8);
                    }
                }
                return invocation.proceed();
            })));
        }
        for (Method method : diagnostics) {
            try {
                scope.own(hooks.install("multidisplay", method, scope.callback(invocation -> {
                    trace(method.getName(), invocation.arguments(), method.getName().equals("showDelayDialog") ? 8 : 12);
                    return invocation.proceed();
                })));
            } catch (HookRegistry.RestartRequiredException uncertain) {
                throw uncertain;
            } catch (IllegalStateException unavailable) {
                trace("optional-diagnostic-unavailable", new Object[] {method.getName()}, 8);
            }
        }
        try {
            scope.own(platform.reloadReceiver(scope.listener(this::refresh)));
        } catch (Exception unavailable) {
            trace("reload-receiver-unavailable", new Object[] {unavailable.getClass().getName()}, 1);
        }
    }

    public static boolean never(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        return NEVER.stream().anyMatch(packageName::startsWith);
    }

    private void refresh() {
        try {
            enabled = platform.enabled();
        } catch (Exception failure) {
            trace("configuration-read-failed", new Object[] {failure.getClass().getName()}, 8);
        }
    }

    private synchronized void trace(String name, Object[] arguments, int maximum) {
        int count = traceCounts.getOrDefault(name, 0);
        if (count < maximum) {
            traceCounts.put(name, count + 1);
            try {
                platform.trace(name, arguments);
            } catch (RuntimeException ignored) {
                // Diagnostics cannot change the OEM operation or its result.
            }
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {
        refresh();
    }
}
