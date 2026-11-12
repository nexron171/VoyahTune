package ru.voyahtune.contracts;

import android.content.pm.ApplicationInfo;

import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.ScreenDownSupportModule;
import ru.voyahtune.agent.runtime.MethodAccess;
import ru.voyahtune.agent.runtime.ModuleRuntime;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ScreenDownSupportModuleTest {
    public static void main(String[] arguments) throws Throwable {
        preservesThirdPartyTasksOnBothDisplays();
        preservesOemDecisionsForSystemAndUnknownApplications();
        rejectsIncompatibleFirmwareBeforeInstallingHooks();
        restoresOemBehaviorWhenDisabled();
        System.out.println("ScreenDownSupportModuleTest: passed");
    }

    private static void preservesThirdPartyTasksOnBothDisplays() throws Throwable {
        try (Harness harness = new Harness(ScreenUpdates.class)) {
            harness.applications.put("org.example.player", 0);
            harness.applications.put("com.tencent.userapp", 0);
            harness.applications.put("ru.big.town.anative", ApplicationInfo.FLAG_SYSTEM);
            harness.applications.put("ru.big.town.restoremode", ApplicationInfo.FLAG_SYSTEM);
            for (String packageName : harness.applications.keySet()) {
                require(
                        harness.supports(false, packageName),
                        "Main screen rejected " + packageName);
                require(
                        harness.supports(true, packageName),
                        "Passenger screen rejected " + packageName);
            }
            harness.applications.put("org.example.newly.installed", 0);
            require(
                    harness.supports(false, "org.example.newly.installed"),
                    "New application was not detected");
        }
    }

    private static void preservesOemDecisionsForSystemAndUnknownApplications() throws Throwable {
        try (Harness harness = new Harness(ScreenUpdates.class)) {
            harness.applications.put("stock.main", ApplicationInfo.FLAG_SYSTEM);
            harness.applications.put("stock.second", ApplicationInfo.FLAG_SYSTEM);
            harness.applications.put("stock.denied", ApplicationInfo.FLAG_SYSTEM);
            harness.applications.put("stock.updated", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
            require(harness.supports(false, "stock.main"), "OEM main-screen allowance was lost");
            require(!harness.supports(true, "stock.main"), "OEM passenger-screen denial was lost");
            require(
                    harness.supports(true, "stock.second"),
                    "OEM passenger-screen allowance was lost");
            require(!harness.supports(false, "stock.second"), "OEM main-screen denial was lost");
            for (String packageName :
                    new String[] {"stock.denied", "stock.updated", "unknown", "", null}) {
                require(
                        !harness.supports(false, packageName),
                        "Unresolved/system app was allowed on main");
                require(
                        !harness.supports(true, packageName),
                        "Unresolved/system app was allowed on passenger");
            }
            harness.applications.clear();
            require(harness.supports(false, "stock.main"), "Lookup failure lost an OEM allowance");
            harness.applications.put("org.example.player", 0);
            harness.lookupFailure = new SecurityException("Package manager unavailable");
            require(
                    !harness.supports(false, "org.example.player"),
                    "Failed classification overrode OEM");
            require(
                    harness.supports(true, "stock.second"),
                    "Package manager failure changed OEM result");
            RuntimeException originalFailure = new IllegalStateException("OEM query failed");
            ScreenUpdates.failure = originalFailure;
            try {
                harness.supports(false, "stock.denied");
                throw new AssertionError("OEM exception was swallowed");
            } catch (RuntimeException caught) {
                require(caught == originalFailure, "OEM exception identity changed");
            } finally {
                ScreenUpdates.failure = null;
            }
        }
    }

    private static void rejectsIncompatibleFirmwareBeforeInstallingHooks() throws Exception {
        for (Class<?> fixture :
                new Class<?>[] {
                    MissingPassengerQuery.class, WrongReturnType.class, InstanceQueries.class
                }) {
            try (Harness harness = new Harness(fixture)) {
                require(
                        harness.runtime.snapshot().phase != ModuleRuntime.Phase.READY,
                        "Incompatible firmware became ready");
                require(
                        harness.backend.installed.isEmpty(),
                        "Part of an incompatible module was installed");
            }
        }
    }

    private static void restoresOemBehaviorWhenDisabled() throws Throwable {
        try (Harness harness = new Harness(ScreenUpdates.class)) {
            harness.applications.put("org.example.player", 0);
            require(harness.supports(false, "org.example.player"), "Module was not active");
            harness.runtime.apply("code", 2, List.of(), Map.of());
            require(
                    !harness.supports(false, "org.example.player"),
                    "Main override survived removal");
            require(
                    !harness.supports(true, "org.example.player"),
                    "Passenger override survived removal");
            require(harness.backend.installed.isEmpty(), "Hooks remained after removal");
        }
    }

    private static final class Harness implements AutoCloseable {
        final FixtureHookBackend backend = new FixtureHookBackend(false);
        final HookRegistry hooks = new HookRegistry(backend);
        final Map<String, Integer> applications = new HashMap<>();
        final ModuleRuntime runtime;
        RuntimeException lookupFailure;

        Harness(Class<?> fixture) {
            MethodAccess methods =
                    new MethodAccess(
                            name ->
                                    name.equals("com.qinggan.app.ScreenUpdateUtils")
                                            ? fixture
                                            : Class.forName(name));
            runtime =
                    new ModuleRuntime(
                            "code",
                            (delay, task) -> {
                                throw new AssertionError(
                                        "Screen support must not schedule task restoration");
                            },
                            id ->
                                    new ScreenDownSupportModule(
                                            methods,
                                            hooks,
                                            packageName -> {
                                                if (lookupFailure != null) {
                                                    throw lookupFailure;
                                                }
                                                Integer flags = applications.get(packageName);
                                                if (flags == null) {
                                                    throw new IllegalArgumentException(
                                                            "Unknown package");
                                                }
                                                return flags;
                                            }));
            runtime.apply("code", 1, List.of("screen-lift"), Map.of());
            if (fixture == ScreenUpdates.class) {
                require(
                        runtime.snapshot().phase == ModuleRuntime.Phase.READY,
                        "Screen support did not become ready");
            }
        }

        boolean supports(boolean passenger, String packageName) throws Throwable {
            Method method =
                    ScreenUpdates.class.getDeclaredMethod(
                            passenger
                                    ? "supportScreenDownInSecondScreen"
                                    : "supportScreenDownInMainScreen",
                            String.class);
            return (Boolean) backend.invoke(method, null, packageName);
        }

        @Override
        public void close() throws Exception {
            runtime.close();
            hooks.close();
        }
    }

    public static final class ScreenUpdates {
        static RuntimeException failure;

        public static boolean supportScreenDownInMainScreen(String packageName) {
            if (failure != null) {
                throw failure;
            }
            return "stock.main".equals(packageName);
        }

        public static boolean supportScreenDownInSecondScreen(String packageName) {
            return "stock.second".equals(packageName);
        }
    }

    public static final class MissingPassengerQuery {
        public static boolean supportScreenDownInMainScreen(String packageName) {
            return false;
        }
    }

    public static final class WrongReturnType {
        public static String supportScreenDownInMainScreen(String packageName) {
            return "unsupported";
        }

        public static boolean supportScreenDownInSecondScreen(String packageName) {
            return false;
        }
    }

    public static final class InstanceQueries {
        public boolean supportScreenDownInMainScreen(String packageName) {
            return false;
        }

        public boolean supportScreenDownInSecondScreen(String packageName) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
