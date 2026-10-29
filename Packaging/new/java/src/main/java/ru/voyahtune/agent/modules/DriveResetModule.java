package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.DriveSelection;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class DriveResetModule implements ModuleRuntime.Module {
    public interface Platform extends AutoCloseable {
        boolean supportsReset();
        boolean supportsUserSelection();
        DriveSelection.Targets selected() throws Exception;
        void save(String key, String value) throws Exception;
        void energyMenu(Object activity) throws Exception;
        void optionalBridges(ModuleScope scope, HookRegistry hooks) throws Exception;
        void error(String operation, Exception failure);
        @Override default void close() throws Exception {}
    }

    private static final String MEMORY = "com.qinggan.app.vehiclesetting.accountdata.VehicleMemoryManager";
    private static final String FRAGMENT = "com.qinggan.app.vehiclesetting.fragments.drivepreference.DrivePreferenceFragment";
    private static final String CANBUS = "com.qinggan.canbus.CanBusManager";
    private static final String STATE = "com.qinggan.canbus.VehicleState";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final ThreadLocal<Boolean> automatic = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> user = ThreadLocal.withInitial(() -> false);
    private Method setter;
    private Method[] resets;
    private Method[] choices;
    private Method hint;
    private Method single;
    private Method recuperation;
    private Method resume;

    public DriveResetModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override
    public void prepare(Map<String, String> configuration) throws Exception {
        resets = new Method[] {methods.require(MEMORY, "resetSettings"), methods.require(MEMORY, "resetOverseaDriveMode")};
        choices = new Method[] {methods.require(FRAGMENT, "setDriveMode", "int"), methods.require(FRAGMENT, "setPowerMode", "int")};
        hint = methods.require(FRAGMENT, "onHintSwitchClick", STATE, "int");
        setter = methods.require(CANBUS, "setVehicleAndAirConditionBundleState", "android.os.Bundle", "android.os.Bundle");
        single = methods.require(CANBUS, "setVehicleState", STATE, "int");
        recuperation = methods.require(FRAGMENT, "setEnergyRecyclerMode", "int");
        try {
            resume = methods.optional("com.qinggan.app.vehiclesetting.CarSettingActivity", "onResume");
        } catch (ClassNotFoundException absent) {
            resume = null;
        }
    }

    @Override
    public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        scope.own(platform);
        install(scope, setter, invocation -> {
            Object vehicle = invocation.arguments()[1];
            Object outgoing = vehicle;
            if (user.get() && vehicle != null) {
                outgoing = copy(vehicle);
                OemAccess.call(outgoing, "putBoolean", "__vt_user", true);
            }
            if (automatic.get() && vehicle != null) {
                outgoing = copy(vehicle);
                OemAccess.call(outgoing, "putBoolean", "__vt_auto", true);
                try {
                    DriveSelection.Targets targets = platform.selected();
                    if (targets != null && (Boolean) OemAccess.call(vehicle, "containsKey", "DRIVING_MODE_SET")) {
                        int[] drive = targets.drive();
                        if (drive != null) {
                            OemAccess.call(outgoing, "putInt", "DRIVING_MODE_SET", drive[0]);
                            OemAccess.call(outgoing, "putInt", "EPS_MODE_SET", drive[1]);
                            OemAccess.call(outgoing, "putInt", "PROP_MODE_SET", drive[2]);
                        }
                        if (targets.recycle != null) OemAccess.call(outgoing, "putInt", "HUM_ENERGY_PTREGEN_LEVL", targets.recycle);
                        if (targets.energy != null) OemAccess.call(outgoing, "putInt", "IVI_SOC_MODESET", targets.energy);
                        OemAccess.call(outgoing, "putInt", "ASC_MAINTAIN_SWITCH", targets.maintenance);
                        if (drive != null && drive[0] == 6) OemAccess.call(outgoing, "remove", "HUM_ENERGY_PTREGEN_LEVL");
                    }
                } catch (Exception failure) {
                    outgoing = vehicle;
                    error("saved-profile", failure);
                }
            }
            invocation.arguments()[1] = outgoing;
            return invocation.proceed();
        });
        for (Method reset : resets) install(scope, reset, invocation -> platform.supportsReset() ? within(automatic, invocation) : invocation.proceed());
        for (Method choice : choices) install(scope, choice, invocation -> platform.supportsUserSelection() ? within(user, invocation) : invocation.proceed());
        install(scope, hint, invocation -> platform.supportsUserSelection() && "IVI_SOC_MODESET".equals(String.valueOf(invocation.arguments()[0]))
                ? within(user, invocation) : invocation.proceed());
        install(scope, single, invocation -> {
            int value = (Integer) invocation.arguments()[1];
            if (user.get() && "IVI_SOC_MODESET".equals(String.valueOf(invocation.arguments()[0]))) {
                String name = DriveSelection.energyName(value);
                if (name != null) save("energy", name);
            }
            return invocation.proceed();
        });
        install(scope, recuperation, invocation -> {
            Object result = invocation.proceed();
            int value = (Integer) invocation.arguments()[0];
            if (platform.supportsReset() && value >= 2 && value <= 4) save("recycle", new String[] {"LOW", "MEDIUM", "HIGH"}[value - 2]);
            return result;
        });
        if (resume != null) try {
            install(scope, resume, invocation -> {
            Object result = invocation.proceed();
            try {
                platform.energyMenu(invocation.receiver());
            } catch (Exception failure) {
                error("energy-menu", failure);
            }
            return result;
            });
        } catch (HookRegistry.RejectedException unsupported) {
            error("energy-menu-unsupported", unsupported);
        }
        platform.optionalBridges(scope, hooks);
    }

    private Object copy(Object bundle) throws Exception {
        return OemAccess.construct(methods.type("android.os.Bundle"), bundle);
    }

    private void install(ModuleScope scope, Method target, HookRegistry.Callback callback) throws Exception {
        scope.own(hooks.install("drive-reset", target, scope.callback(callback)));
    }

    private Object within(ThreadLocal<Boolean> local, HookRegistry.Invocation invocation) throws Throwable {
        boolean previous = local.get();
        local.set(true);
        try {
            return invocation.proceed();
        } finally {
            if (previous) local.set(true); else local.remove();
        }
    }

    private void save(String key, String value) {
        try {
            platform.save(key, value);
        } catch (Exception failure) {
            error("user-selection", failure);
        }
    }

    private void error(String operation, Exception failure) {
        try {
            platform.error(operation, failure);
        } catch (RuntimeException ignored) {
            // Persistence diagnostics cannot replay a CAN command.
        }
    }

    @Override
    public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
