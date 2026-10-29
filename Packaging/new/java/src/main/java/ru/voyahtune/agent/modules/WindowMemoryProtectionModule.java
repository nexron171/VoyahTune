package ru.voyahtune.agent.modules;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.ResourceGroup;

public final class WindowMemoryProtectionModule implements ModuleRuntime.Module {
    public interface Platform {
        default void prepareMemory() throws Exception {}
        void recompute() throws Exception;
        void error(Exception failure);
    }

    private static final String RESTORE = "ru.big.town.restoremode";
    private static final String SESSION = "com.shilapi.xcertplay.DiPlaySessionService";
    private static final String HOST = "com.shilapi.xcertplay.CarPlayHostActivity";
    private final MethodAccess methods;
    private final HookRegistry hooks;
    private final Platform platform;
    private final Map<Object, Boolean> protectedWindows = Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean adjusted = new AtomicBoolean();
    private Method applyAdjustment;
    private Method isDestroyable;
    private boolean collectionField;

    public WindowMemoryProtectionModule(MethodAccess methods, HookRegistry hooks, Platform platform) {
        this.methods = methods;
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) {
        try {
            platform.prepareMemory();
            applyAdjustment = methods.require("com.android.server.am.OomAdjuster", "applyOomAdjLocked", "com.android.server.am.ProcessRecord", "boolean", "long", "long");
            isDestroyable = methods.require("com.android.server.wm.ActivityRecord", "isDestroyable");
            if (applyAdjustment.getReturnType() != boolean.class || isDestroyable.getReturnType() != boolean.class) {
                throw new NoSuchMethodException("Boolean memory policy methods required");
            }
            fields("com.android.server.am.ProcessRecord", "processName", "curAdj");
            fields("com.android.server.am.ServiceRecord", "name", "isForeground");
            fields("com.android.server.wm.ActivityRecord", "app", "mActivityComponent", "finishing");
            collectionField = methods.optional("com.android.server.am.ProcessRecord", "numberOfRunningServices") == null
                    || methods.optional("com.android.server.am.ProcessRecord", "getRunningServiceAt", "int") == null;
            if (collectionField) fields("com.android.server.am.ProcessRecord", "services");
            methods.require("com.android.server.am.ProcessRecord", "getWindowProcessController");
        } catch (Exception unsupported) {
            applyAdjustment = null;
            report(unsupported);
        }
    }

    private void fields(String owner, String... names) throws Exception {
        Class<?> type = methods.type(owner);
        for (String name : names) OemAccess.field(type, name);
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (applyAdjustment == null) return;
        ResourceGroup resources = new ResourceGroup();
        scope.own(resources);
        resources.own(() -> {
            protectedWindows.clear();
            // Recompute from current OEM state; an old adjustment can overwrite a newer priority.
            if (adjusted.getAndSet(false)) platform.recompute();
        });
        try {
            resources.own(hooks.install("windows-memory", applyAdjustment, scope.callback(invocation -> {
                try { protect(invocation.arguments()[0]); }
                catch (Exception unavailable) { report(unavailable); }
                return invocation.proceed();
            })));
            resources.own(hooks.install("windows-memory", isDestroyable, scope.callback(invocation -> {
                try { if (protectActivity(invocation.receiver())) return false; }
                catch (Exception unavailable) { report(unavailable); }
                return invocation.proceed();
            })));
        } catch (HookRegistry.RejectedException unsupported) {
            resources.close();
            report(unsupported);
        }
    }

    private void protect(Object process) throws Exception {
        if (failed.get() || !RESTORE.equals(OemAccess.read(process, "processName"))) return;
        Object windows = OemAccess.call(process, "getWindowProcessController");
        if (!hasSession(process)) {
            protectedWindows.remove(windows);
            return;
        }
        if (windows != null) protectedWindows.put(windows, true);
        Field adjustment = OemAccess.field(process.getClass(), "curAdj");
        if (adjustment.getInt(process) > 0) {
            adjusted.set(true);
            adjustment.setInt(process, 0);
        }
    }

    private boolean hasSession(Object process) throws Exception {
        Object collection = collectionField ? OemAccess.read(process, "services") : process;
        int count = (Integer) OemAccess.call(collection, collectionField ? "size" : "numberOfRunningServices");
        for (int index = 0; index < count; index++) {
            Object service = OemAccess.call(collection, collectionField ? "valueAt" : "getRunningServiceAt", index);
            if (Boolean.TRUE.equals(OemAccess.read(service, "isForeground")) && component(OemAccess.read(service, "name"), SESSION)) return true;
        }
        return false;
    }

    private boolean protectActivity(Object activity) throws Exception {
        if (failed.get() || Boolean.TRUE.equals(OemAccess.read(activity, "finishing"))
                || !component(OemAccess.read(activity, "mActivityComponent"), HOST)) return false;
        Object process = OemAccess.read(activity, "app");
        return process != null && protectedWindows.containsKey(process);
    }

    private boolean component(Object value, String name) throws Exception {
        return value != null && RESTORE.equals(OemAccess.call(value, "getPackageName")) && name.equals(OemAccess.call(value, "getClassName"));
    }

    private void report(Exception failure) {
        protectedWindows.clear();
        if (!failed.compareAndSet(false, true)) return;
        try { platform.error(failure); }
        catch (RuntimeException ignored) { /* Diagnostics must preserve the OEM memory policy. */ }
    }

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) {}
}
