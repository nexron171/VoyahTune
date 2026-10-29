package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;

public final class WindowPrivilegesModule implements ModuleRuntime.Module {
    public interface Platform {
        int nativeUid();
        int callingUid();
        long clearIdentity();
        void restoreIdentity(long identity);
        void error(Exception failure);
    }

    private static final String NATIVE = "ru.big.town.anative";
    private static final Set<String> DISPLAY_PERMISSIONS = Set.of("android.permission.ADD_TRUSTED_DISPLAY", "android.permission.INTERNAL_SYSTEM_WINDOW");
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final Map<String, Method> targets = new LinkedHashMap<>();
    private final AtomicBoolean imeWarning = new AtomicBoolean();

    public WindowPrivilegesModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) throws Exception {
        require("remove", "com.android.server.wm.ActivityTaskManagerService", "removeTask", "int");
        require("ime", "com.android.server.wm.DisplayWindowSettings", "shouldShowImeLocked", "com.android.server.wm.DisplayContent");
        require("input", "com.android.server.input.InputManagerService", "checkInjectEventsPermission", "int", "int");
        require("permission", "com.android.server.display.DisplayManagerService$BinderService", "checkCallingPermission", "java.lang.String", "java.lang.String");
        require("launch", "com.android.server.wm.ActivityStackSupervisor", "isCallerAllowedToLaunchOnDisplay", "int", "int", "int", "android.content.pm.ActivityInfo");
        require("activity", "com.android.server.wm.ActivityRecord", "canBeLaunchedOnDisplay", "int");
        require("feature", "com.android.server.pm.PackageManagerService", "hasSystemFeature", "java.lang.String", "int");
        methods.require("com.android.server.wm.DisplayContent", "getDisplayInfo");
        methods.require("com.android.server.wm.DisplayContent", "isTrusted");
    }

    private void require(String role, String owner, String name, String... arguments) throws Exception {
        Method method = methods.require(owner, name, arguments);
        if (method.getReturnType() != boolean.class) throw new IllegalArgumentException("Boolean window policy method required: " + name);
        targets.put(role, method);
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        for (Map.Entry<String, Method> entry : targets.entrySet()) {
            scope.own(hooks.install("windows-privileges", entry.getValue(), scope.callback(invocation -> {
                switch (entry.getKey()) {
                    case "remove":
                        if (!nativeUid(platform.callingUid())) return invocation.proceed();
                        long identity = platform.clearIdentity();
                        try { return invocation.proceed(); }
                        finally {
                            try { platform.restoreIdentity(identity); }
                            catch (RuntimeException failure) { scope.requireRestart(failure); throw failure; }
                        }
                    case "ime":
                        if (ownedIme(invocation.arguments()[0])) return true;
                        return invocation.proceed();
                    case "input":
                    case "launch":
                        return nativeUid((Integer) invocation.arguments()[1]) ? true : invocation.proceed();
                    case "permission":
                        return DISPLAY_PERMISSIONS.contains(invocation.arguments()[0]) && nativeUid(platform.callingUid()) ? true : invocation.proceed();
                    case "activity":
                        return !invocation.arguments()[0].equals(0) ? true : invocation.proceed();
                    case "feature":
                        return "android.software.activities_on_secondary_displays".equals(invocation.arguments()[0]) ? true : invocation.proceed();
                    default: throw new IllegalArgumentException("Unknown window policy role");
                }
            })));
        }
    }

    private boolean nativeUid(int uid) { int expected = platform.nativeUid(); return expected >= 0 && uid == expected; }

    private boolean ownedIme(Object display) {
        try {
            int uid = platform.nativeUid();
            if (uid < 0 || display == null) return false;
            Object info = OemAccess.call(display, "getDisplayInfo");
            return info != null && Integer.valueOf(5).equals(OemAccess.read(info, "type")) && Integer.valueOf(uid).equals(OemAccess.read(info, "ownerUid"))
                    && NATIVE.equals(OemAccess.read(info, "ownerPackageName")) && Boolean.TRUE.equals(OemAccess.call(display, "isTrusted"));
        } catch (Exception unavailable) {
            if (imeWarning.compareAndSet(false, true)) {
                try { platform.error(unavailable); }
                catch (RuntimeException ignored) { /* Metadata diagnostics do not alter stock IME policy. */ }
            }
            return false;
        }
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
