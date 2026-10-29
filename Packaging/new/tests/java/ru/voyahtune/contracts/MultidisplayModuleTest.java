package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.MultidisplayModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class MultidisplayModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("MultidisplayModuleTest: passed");
    }

    static void verify() throws Throwable {
        FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        JsonObject input = new JsonObject();
        TestPlatform platform = new TestPlatform(input);
        MethodAccess methods = new MethodAccess(name -> name.equals("com.qinggan.systemservice.multidisplay.MultiDisplayImpl")
                ? MultidisplayContractDriver.Service.class : Class.forName(name));
        MultidisplayContractDriver.Service receiver = new MultidisplayContractDriver.Service();
        Method query = receiver.getClass().getDeclaredMethod("isWhiteListApp", String.class);
        try (HookRegistry hooks = new HookRegistry(backend);
             ModuleRuntime runtime = new ModuleRuntime("code", new ManualScheduler(), id -> new MultidisplayModule(methods, hooks, platform))) {
            runtime.apply("code", 1, List.of("multidisplay"), Map.of());
            require(runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Multidisplay not ready");
            for (String packageName : new String[] {null, "", "com.qinggan.app.launcher", "com.qinggan.app.launcher.extra", "com.qinggan.mainlauncher", "com.android.systemui"}) {
                require(Boolean.FALSE.equals(backend.invoke(query, receiver, packageName)), "Protected package was allowed");
            }
            require(Boolean.TRUE.equals(backend.invoke(query, receiver, "org.maps")), "External app was not allowed");
            require(receiver.queries == 0, "Enabled whitelist invoked OEM decision");
            Method indexed = receiver.getClass().getDeclaredMethod("isWhiteListApp", int.class, String.class);
            require(Boolean.TRUE.equals(backend.invoke(indexed, receiver, 1, "org.maps")), "Package argument was not found in non-first slot");
            Method boxed = receiver.getClass().getDeclaredMethod("isWhiteListApp", Boolean.class, String.class);
            require(Boolean.TRUE.equals(backend.invoke(boxed, receiver, Boolean.FALSE, "org.maps")), "Boxed boolean ABI failed");
            Method ambiguous = receiver.getClass().getDeclaredMethod("isWhiteListApp", String.class, String.class);
            require(Boolean.FALSE.equals(backend.invoke(ambiguous, receiver, "org.maps", "Activity")) && receiver.queries == 1,
                    "Ambiguous package/activity ABI was overridden");
            input.addProperty("enabled", false);
            platform.reload.run();
            require(Boolean.TRUE.equals(backend.invoke(query, receiver, "stock.allowed")) && receiver.queries == 2,
                    "Disabled mode lost OEM result or called twice");
            RuntimeException original = new IllegalStateException("original-query-failure");
            receiver.queryFailure = original;
            try {
                backend.invoke(query, receiver, "org.maps");
                throw new AssertionError("Original exception was lost");
            } catch (RuntimeException caught) {
                require(caught == original && receiver.queries == 3, "Original exception identity/call count changed");
            }
            receiver.queryFailure = null;
            runtime.apply("code", 2, List.of("multidisplay"), Map.of("revision", "second"));
            input.addProperty("enabled", true);
            platform.reload.run();
            require(Boolean.TRUE.equals(backend.invoke(query, receiver, "org.maps")), "Receiver stopped after live configuration change");
            platform.failTrace = true;
            require(backend.invoke(receiver.getClass().getDeclaredMethod("showDelayDialog", String.class), receiver, "test").equals(7)
                    && receiver.dialogs == 1, "Diagnostic hook changed original result/call count");
            require(Boolean.FALSE.equals(backend.invoke(receiver.getClass().getDeclaredMethod("setEnableActivityAnimation", String.class, String.class, boolean.class),
                    receiver, "org.maps", "Activity", false)) && receiver.animations == 1, "Animation policy was changed");
            runtime.apply("code", 3, List.of(), Map.of());
            int reads = platform.reads;
            platform.reload.run();
            require(platform.reads == reads && platform.closed == 1 && backend.installed.isEmpty(), "Disabled listener/hooks remained active");
            require(Boolean.FALSE.equals(backend.invoke(query, receiver, "org.maps")), "Whitelist hook was not restored");
        }
    }

    private static final class TestPlatform extends MultidisplayContractDriver.Platform {
        Runnable reload;
        int reads;
        int closed;
        boolean failTrace;
        TestPlatform(JsonObject input) { super(input); }
        @Override public boolean enabled() { reads++; return super.enabled(); }
        @Override public AutoCloseable reloadReceiver(Runnable reload) { this.reload = reload; return () -> closed++; }
        @Override public void trace(String event, Object[] arguments) { if (failTrace) throw new IllegalStateException("logging failed"); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
