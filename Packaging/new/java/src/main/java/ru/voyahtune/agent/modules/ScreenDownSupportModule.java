package ru.voyahtune.agent.modules;

import android.content.pm.ApplicationInfo;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

public final class ScreenDownSupportModule implements ModuleRuntime.Module {
    @FunctionalInterface
    public interface ApplicationFlags {
        int read(String packageName) throws Exception;
    }

    private static final String OWNER = "com.qinggan.app.ScreenUpdateUtils";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final ApplicationFlags applications;
    private Method mainScreen;
    private Method passengerScreen;

    public ScreenDownSupportModule(
            MethodAccess methods, HookRegistry hooks, ApplicationFlags applications) {
        this.methods = methods;
        this.hooks = hooks;
        this.applications = applications;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        mainScreen = query("supportScreenDownInMainScreen");
        passengerScreen = query("supportScreenDownInSecondScreen");
    }

    private Method query(String name) throws ReflectiveOperationException {
        Method method = methods.require(OWNER, name, "java.lang.String");
        if (method.getReturnType() != boolean.class || !Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("Unsupported screen-down query: " + method);
        }
        return method;
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (Method query : new Method[] {mainScreen, passengerScreen}) {
            scope.own(
                    hooks.install(
                            "screen-lift",
                            query,
                            scope.callback(
                                    invocation -> {
                                        String packageName = (String) invocation.arguments()[0];
                                        return supportsApplication(packageName)
                                                ? true
                                                : invocation.proceed();
                                    })));
        }
    }

    private boolean supportsApplication(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            int flags = applications.read(packageName);
            // Native hosts third-party windows while installed as a privileged system APK.
            return packageName.equals("ru.big.town.anative")
                    || packageName.equals("ru.big.town.restoremode")
                    || (flags
                                    & (ApplicationInfo.FLAG_SYSTEM
                                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                            == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
