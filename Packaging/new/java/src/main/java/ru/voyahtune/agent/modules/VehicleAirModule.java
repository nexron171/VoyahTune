package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.policy.DriveSelection;

public final class VehicleAirModule implements ModuleRuntime.Module {
    @FunctionalInterface
    public interface Persistence {
        void save(String mode) throws Exception;
    }

    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Persistence persistence;
    private final Consumer<Exception> diagnostics;
    private final ThreadLocal<Boolean> userScope = ThreadLocal.withInitial(() -> false);
    private Method handler;
    private Method confirmation;
    private Method setter;

    public VehicleAirModule(MethodAccess methods, HookRegistry hooks, Persistence persistence) {
        this(methods, hooks, persistence, failure -> {});
    }

    public VehicleAirModule(MethodAccess methods, HookRegistry hooks, Persistence persistence, Consumer<Exception> diagnostics) {
        this.methods = methods;
        this.hooks = hooks;
        this.persistence = persistence;
        this.diagnostics = diagnostics;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        handler = methods.require("com.qinggan.app.vehiclebase.ui97.DriveModeViewManager$1", "handleMessage", "android.os.Message");
        confirmation = methods.require("com.qinggan.app.vehiclebase.ui97.DriveModeViewManager$2", "onClick", "android.view.View");
        setter = methods.require("com.qinggan.canbus.CanBusManager", "setVehicleAndAirConditionBundleState", "android.os.Bundle", "android.os.Bundle");
        OemAccess.field(methods.type("android.os.Message"), "what");
        methods.require("android.os.Bundle", "getInt", "java.lang.String", "int");
        if (setter.getReturnType() != int.class) throw new NoSuchMethodException("Unknown CAN bundle acceptance result type");
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.own(hooks.install("vehicle-air", handler, scope.callback(invocation ->
                inUserScope(((Number) OemAccess.read(invocation.arguments()[0], "what")).intValue() == 3, invocation))));
        scope.own(hooks.install("vehicle-air", confirmation, scope.callback(invocation -> inUserScope(true, invocation))));
        scope.own(hooks.install("vehicle-air", setter, scope.callback(invocation -> {
            Object vehicle = invocation.arguments()[1];
            int mode = vehicle == null ? -1 : (Integer) OemAccess.call(vehicle, "getInt", "DRIVING_MODE_SET", -1);
            boolean user = userScope.get() && mode >= 1 && mode <= 6;
            Object result = invocation.proceed();
            if (user && Integer.valueOf(0).equals(result)) {
                try {
                    persistence.save(DriveSelection.driveName(mode));
                } catch (Exception failure) {
                    diagnostics.accept(failure);
                }
            }
            return result;
        })));
    }

    private Object inUserScope(boolean user, HookRegistry.Invocation invocation) throws Throwable {
        boolean previous = userScope.get();
        if (user) userScope.set(true); else userScope.remove();
        try {
            return invocation.proceed();
        } finally {
            if (previous) userScope.set(true); else userScope.remove();
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
