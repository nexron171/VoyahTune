package ru.voyahtune.contracts;

import android.content.pm.ApplicationInfo;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.PassengerHeadphonesModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PassengerHeadphonesModuleTest {
    public static void main(String[] arguments) throws Throwable {
        extendsOnlyInstalledThirdPartyPackages();
        preservesOriginalResultsAndFailures();
        restoresRoutesAndHooks();
        rejectsUnknownFirmware();
        System.out.println("PassengerHeadphonesModuleTest: passed");
    }

    private static void extendsOnlyInstalledThirdPartyPackages() throws Throwable {
        try (Harness harness = new Harness(Service.class)) {
            harness.applications.put("org.example.player", 0);
            require(
                    "org.example.player".equals(harness.call("a", "org.example.player")),
                    "Missing package group");
            require(
                    Boolean.TRUE.equals(harness.call("d", "org.example.player")),
                    "Package rejected");
            harness.applications.put("org.example.system", ApplicationInfo.FLAG_SYSTEM);
            harness.applications.put(
                    "org.example.updated", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
            for (String name :
                    new String[] {
                        "org.example.system", "org.example.updated", "missing", "", null
                    }) {
                require(harness.call("a", name) == null, "Unexpected group for " + name);
                require(
                        Boolean.FALSE.equals(harness.call("d", name)),
                        "Unexpected support for " + name);
            }
            harness.applications.remove("org.example.player");
            require(
                    harness.call("a", "org.example.player") == null,
                    "Uninstalled package retained");
        }
    }

    private static void preservesOriginalResultsAndFailures() throws Throwable {
        try (Harness harness = new Harness(Service.class)) {
            harness.applications.put("stock.player", 0);
            require(
                    "stock.player;stock.service".equals(harness.call("a", "stock.player")),
                    "OEM group replaced");
            require(Boolean.TRUE.equals(harness.call("d", "stock.player")), "OEM support lost");
            harness.lookupFailure = true;
            require(harness.call("a", "org.example.player") == null, "Lookup failure allowed app");
            require(
                    "stock.player;stock.service".equals(harness.call("a", "stock.player")),
                    "OEM group depends on PM");
            Collector.failure = new IllegalStateException("OEM failure");
            try {
                harness.call("a", "org.example.player");
                throw new AssertionError("OEM failure swallowed");
            } catch (IllegalStateException expected) {
                require(expected == Collector.failure, "OEM failure replaced");
            } finally {
                Collector.failure = null;
            }
        }
    }

    private static void restoresRoutesAndHooks() throws Throwable {
        try (Harness harness = new Harness(Service.class)) {
            harness.applications.put("org.example.player", 0);
            harness.call("a", "org.example.player");
            harness.collector.routes.put("org.example.player", 1);
            harness.collector.routes.put("stock.player", 1);
            harness.runtime.apply("code", 2, List.of(), Map.of());
            require(
                    harness.collector.routes.get("org.example.player") == 0,
                    "Extended route survived disable");
            require(harness.collector.routes.get("stock.player") == 1, "OEM route changed");
            require(harness.backend.installed.isEmpty(), "Hooks survived disable");
            require(harness.call("a", "org.example.player") == null, "Override survived disable");
        }
    }

    private static void rejectsUnknownFirmware() throws Exception {
        try (Harness harness = new Harness(IncompatibleService.class)) {
            require(
                    harness.runtime.snapshot().phase != ModuleRuntime.Phase.READY,
                    "Unknown ABI accepted");
            require(harness.backend.installed.isEmpty(), "Partial hooks installed");
        }
    }

    private static final class Harness implements AutoCloseable {
        final FixtureHookBackend backend = new FixtureHookBackend(false);
        final HookRegistry hooks = new HookRegistry(backend);
        final Map<String, Integer> applications = new HashMap<>();
        final Collector collector = new Collector();
        final ModuleRuntime runtime;
        boolean lookupFailure;

        Harness(Class<?> service) {
            MethodAccess methods =
                    new MethodAccess(
                            name ->
                                    name.equals(
                                                    "com.qinggan.audiopolicy.service.OSAudioPolicyService")
                                            ? service
                                            : Class.forName(name));
            runtime =
                    new ModuleRuntime(
                            "code",
                            (delay, task) -> {
                                throw new AssertionError("Unexpected timer");
                            },
                            id ->
                                    new PassengerHeadphonesModule(
                                            methods,
                                            hooks,
                                            name -> {
                                                if (lookupFailure
                                                        || !applications.containsKey(name))
                                                    throw new IllegalArgumentException(
                                                            "Unavailable package");
                                                return applications.get(name);
                                            }));
            runtime.apply("code", 1, List.of("passenger-headphones"), Map.of());
            if (service == Service.class)
                require(runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Module not ready");
        }

        Object call(String name, String packageName) throws Throwable {
            return backend.invoke(
                    Collector.class.getDeclaredMethod(name, String.class), collector, packageName);
        }

        public void close() throws Exception {
            runtime.close();
            hooks.close();
        }
    }

    public static final class Service {
        private Engine b;
    }

    public static final class Engine {
        private Collector u;
    }

    public static final class IncompatibleService {
        private String b;
    }

    public static final class Collector {
        private final String[] n = {"stock.player;stock.service"};
        private final ConcurrentHashMap<String, Integer> c = new ConcurrentHashMap<>();
        final Map<String, Integer> routes = new HashMap<>();
        static RuntimeException failure;

        public String a(String name) {
            if (failure != null) throw failure;
            return "stock.player".equals(name) ? n[0] : null;
        }

        private boolean d(String name) {
            return "stock.player".equals(name);
        }

        public void b(String name, int channel) {
            routes.put(name, channel);
        }

        public boolean a(String name, int usage, int content) {
            return false;
        }

        public boolean a(String name, int usage, int content, int display) {
            return false;
        }

        public boolean e() {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
