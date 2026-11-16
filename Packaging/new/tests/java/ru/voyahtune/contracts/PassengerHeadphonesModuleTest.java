package ru.voyahtune.contracts;

import android.content.pm.ApplicationInfo;

import ru.voyahtune.agent.runtime.ModuleRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ru.voyahtune.contracts.PassengerHeadphonesFixtures.*;

public final class PassengerHeadphonesModuleTest {
    @FunctionalInterface
    private interface Scenario {
        void run() throws Exception;
    }

    public static void main(String[] arguments) throws Exception {
        List<String> failures = new ArrayList<>();
        check("third-party admission", PassengerHeadphonesModuleTest::extendsOnlyInstalledThirdPartyPackages, failures);
        check("OEM boundaries", PassengerHeadphonesModuleTest::preservesOriginalResultsAndFailures, failures);
        check("media attributes", PassengerHeadphonesModuleTest::routesMovieAndSpeechMedia, failures);
        check("volume", PassengerHeadphonesModuleTest::usesHeadphoneVolumeAndCallbacks, failures);
        check("focus cleanup", PassengerHeadphonesModuleTest::restoresFocusAndRoutes, failures);
        check("OEM source cleanup", PassengerHeadphonesModuleTest::preservesStockSource, failures);
        check("cleanup race", PassengerHeadphonesModuleTest::waitsForWholeDisplayOperation, failures);
        check("focus and connection cleanup races", PassengerHeadphonesModuleTest::waitsForFocusAndConnectionOperations, failures);
        check("late attach", PassengerHeadphonesModuleTest::reconcilesExistingPassengerPlayback, failures);
        check("snapshot race", PassengerHeadphonesModuleTest::preservesNewerDisplayEvent, failures);
        check("disconnected attach", PassengerHeadphonesModuleTest::waitsForHeadphoneConnection, failures);
        check("engine startup", PassengerHeadphonesModuleTest::waitsForEngine, failures);
        check("service recreation", PassengerHeadphonesModuleTest::cleansUpAfterServiceRecreation, failures);
        check("communication", PassengerHeadphonesModuleTest::keepsCommunicationOutOfHeadphones, failures);
        check("direct source communication", PassengerHeadphonesModuleTest::rejectsCommunicationInSourceEntryPoint, failures);
        check("communication race", PassengerHeadphonesModuleTest::serializesCallWithRouteWrite, failures);
        check("communication during disable", PassengerHeadphonesModuleTest::protectsCallsWhileDisabling, failures);
        check("communication failure", PassengerHeadphonesModuleTest::rejectsCallWhenRouteCannotBeCleared, failures);
        check("cleanup failure", PassengerHeadphonesModuleTest::reportsUncertainCleanup, failures);
        check("ABI rejection", PassengerHeadphonesModuleTest::rejectsUnknownFirmware, failures);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("PassengerHeadphonesModuleTest: 20 scenarios passed");
    }

    private static void check(String name, Scenario scenario, List<String> failures) {
        try {
            scenario.run();
        } catch (Throwable failure) {
            failures.add(name + ": " + failure);
        }
    }

    private static void extendsOnlyInstalledThirdPartyPackages() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            require(PLAYER.equals(fixture.group(PLAYER)), "Missing package group");
            fixture.applications.put("system", ApplicationInfo.FLAG_SYSTEM);
            fixture.applications.put("updated", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
            for (String name : new String[] {"system", "updated", "missing", "", null}) {
                require(fixture.group(name) == null, "Unexpected group for " + name);
                require(!supported(fixture, name), "Unexpected support for " + name);
            }
            require(supported(fixture, PLAYER), "Third-party support missing");
            fixture.applications.remove(PLAYER);
            require(fixture.group(PLAYER) == null, "Uninstalled package retained");
        }
    }

    private static boolean supported(PassengerHeadphonesFixtures fixture, String name) throws Exception {
        return (Boolean) fixture.call(fixture.collector, "d", new Class<?>[] {String.class}, name);
    }

    private static void preservesOriginalResultsAndFailures() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.applications.put(STOCK, 0);
            fixture.enable();
            fixture.display(STOCK, 1);
            require(STOCK_GROUP.equals(fixture.group(STOCK)), "OEM group replaced");
            require(fixture.matches(STOCK, 1, 2, 1), "OEM music rejected");
            require(!fixture.matches(STOCK, 1, 3, 1), "OEM movie predicate changed");
            require(!fixture.matches(STOCK, 1, 1, 1), "OEM speech predicate changed");
            require(fixture.volume(STOCK) == 8, "OEM volume predicate changed");
            fixture.display("stock.volume", 1);
            require(fixture.volume("stock.volume") == 4, "OEM headphone volume lost");
            fixture.lookupFailure = true;
            require(fixture.group(PLAYER) == null, "PM failure admitted app");
            require(STOCK_GROUP.equals(fixture.group(STOCK)), "OEM depends on PM");
            RuntimeException failure = new IllegalStateException("OEM lookup failure");
            fixture.collector.lookupFailure = failure;
            try {
                fixture.group(PLAYER);
                throw new AssertionError("OEM failure swallowed");
            } catch (IllegalStateException expected) {
                require(expected == failure, "OEM exception replaced");
            } finally {
                fixture.collector.lookupFailure = null;
            }
        }
    }

    private static void routesMovieAndSpeechMedia() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            fixture.display(PLAYER, 1);
            for (int content : new int[] {0, 1, 2, 3, 19}) {
                require(fixture.matches(PLAYER, 1, content, 1), "Media content rejected: " + content);
                require(!fixture.matches(PLAYER, 1, content, 0), "Display check bypassed");
                require(fixture.request(PLAYER, 1, content) == 1, "Media did not obtain source focus");
                require(fixture.collector.routes.get(PLAYER) == 1, "Media routed to cabin");
                require(fixture.focus.a().content == content, "Actual client attributes rewritten");
            }
            for (int usage : new int[] {0, 2, 3, 6, 11, 12, 16, 22}) {
                require(!fixture.matches(PLAYER, usage, 1, 1), "Non-media usage admitted: " + usage);
                require(!fixture.matches(PLAYER, usage, 3, 1), "Non-media movie admitted: " + usage);
            }
            require(!fixture.matches(PLAYER, 1, 4, 1), "Unrelated content admitted");
        }
    }

    private static void usesHeadphoneVolumeAndCallbacks() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            fixture.display(PLAYER, 1);
            fixture.request(PLAYER, 1, 2);
            require(fixture.volume(PLAYER) == 4, "Getter reports cabin volume");
            fixture.volume(PLAYER, 6);
            require(fixture.engine.headphoneVolume == 6, "Headphone volume unchanged");
            require(fixture.engine.cabinVolume == 8, "Cabin volume changed");
            require(fixture.receivesVolume(PLAYER, true), "Headphone notification missing");
            require(!fixture.receivesVolume(PLAYER, false), "Cabin notification leaked");
            fixture.display(PLAYER, 0);
            require(fixture.volume(PLAYER) == 8, "Driver volume not restored");
            fixture.display(PLAYER, 1);
            fixture.connect(false);
            require(fixture.volume(PLAYER) == 8, "Disconnected device still controls volume");
        }
    }

    private static void restoresFocusAndRoutes() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.request(STOCK, 1, 2);
            Client cabin = fixture.focus.f();
            fixture.enable();
            fixture.display(PLAYER, 1);
            fixture.request(PLAYER, 1, 2);
            fixture.disable();
            require(fixture.focus.a() == null, "Source owner survived disable");
            require(PLAYER.equals(fixture.focus.f().packageName), "Media did not return through cabin arbitration");
            require(cabin.state == 301, "Previous cabin focus was not notified");
            require(fixture.engine.cabinNotifications > 0, "Cabin owner update missing");
            require(fixture.collector.routes.get(PLAYER) == 0, "Route survived disable");
            require(!fixture.collector.c(PLAYER), "Extension display cache survived");
            require(fixture.backend.installed.isEmpty(), "Hooks survived disable");
            require(fixture.group(PLAYER) == null, "Package extension survived");
        }
    }

    private static void preservesStockSource() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.display(STOCK, 1);
            fixture.request(STOCK, 1, 2);
            Client stock = fixture.focus.a();
            fixture.displays.put(PLAYER, 1);
            fixture.request(PLAYER, 1, 2);
            fixture.enable();
            require(fixture.focus.a() == stock, "Bootstrap replaced OEM source owner");
            fixture.display(PLAYER, 0);
            require(fixture.focus.a() == stock, "Third-party driver event detached OEM source");
            fixture.disable();
            require(fixture.focus.a() == stock, "Cleanup detached OEM source owner");
            require(fixture.collector.routes.get(STOCK) == 1, "Cleanup changed OEM route");
        }
    }

    private static void waitsForWholeDisplayOperation() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch lookedUp = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            fixture.afterGroup = () -> {
                lookedUp.countDown();
                await(resume);
            };
            Future<?> display = workers.submit(() -> {
                fixture.display(PLAYER, 1);
                return null;
            });
            require(lookedUp.await(2, TimeUnit.SECONDS), "Display operation did not reach lookup");
            Future<?> disable = workers.submit(fixture::disable);
            try {
                disable.get(150, TimeUnit.MILLISECONDS);
                throw new AssertionError("Disable overtook OEM display operation");
            } catch (TimeoutException expected) {
                // The caller still owns a route decision derived from our package lookup.
            } finally {
                resume.countDown();
                display.get(2, TimeUnit.SECONDS);
                disable.get(2, TimeUnit.SECONDS);
            }
            require(fixture.collector.routes.get(PLAYER) == 0, "Late operation overwrote cleanup");
            fixture.display(PLAYER, 0);
            require(fixture.collector.routes.get(PLAYER) == 0, "Route leaked after unhook");
        } finally {
            resume.countDown();
            workers.shutdownNow();
        }
    }

    private static void waitsForFocusAndConnectionOperations() throws Exception {
        for (boolean connection : new boolean[] {false, true}) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch matched = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
                fixture.enable();
                fixture.display(PLAYER, 1);
                if (connection) {
                    fixture.connect(false);
                    fixture.request(PLAYER, 1, 3);
                }
                fixture.afterMediaMatch = () -> {
                    matched.countDown();
                    await(resume);
                };
                Future<?> operation = workers.submit(() -> {
                    if (connection) fixture.connect(true);
                    else fixture.request(PLAYER, 1, 3);
                    return null;
                });
                require(matched.await(2, TimeUnit.SECONDS), "OEM caller did not reach match");
                Future<?> disable = workers.submit(fixture::disable);
                try {
                    disable.get(150, TimeUnit.MILLISECONDS);
                    throw new AssertionError("Disable overtook " + (connection ? "connection" : "focus"));
                } catch (TimeoutException expected) {
                    // Matching and writing the route belong to the same in-flight operation.
                } finally {
                    resume.countDown();
                    operation.get(2, TimeUnit.SECONDS);
                    disable.get(2, TimeUnit.SECONDS);
                }
                require(fixture.focus.a() == null, "Late focus survived disable");
                require(fixture.collector.routes.get(PLAYER) == 0, "Late focus overwrote route reset");
            } finally {
                resume.countDown();
                workers.shutdownNow();
            }
        }
    }

    private static void reconcilesExistingPassengerPlayback() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.displays.put(PLAYER, 1);
            fixture.request(PLAYER, 1, 3);
            fixture.enable();
            require(fixture.runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Bootstrap incomplete");
            require(fixture.focus.a() != null && PLAYER.equals(fixture.focus.a().packageName),
                    "Already playing passenger media stayed in cabin");
            require(fixture.collector.routes.get(PLAYER) == 1, "Initial route missing");
        }
    }

    private static void preservesNewerDisplayEvent() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.displays.put(PLAYER, 1);
            fixture.request(PLAYER, 1, 3);
            fixture.duringSnapshot = () -> {
                try {
                    fixture.display(PLAYER, 0);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            };
            fixture.enable();
            require(!fixture.collector.c(PLAYER), "Snapshot overwrote newer driver event");
            require(fixture.focus.a() == null, "Stale snapshot stole cabin focus");
            require(fixture.collector.routes.get(PLAYER) == 0, "Stale snapshot routed to headphones");
        }
    }

    private static void waitsForHeadphoneConnection() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.collector.connected = false;
            fixture.displays.put(PLAYER, 1);
            fixture.request(PLAYER, 1, 1);
            fixture.enable();
            require(fixture.focus.a() == null, "Disconnected bootstrap moved focus");
            fixture.connect(true);
            require(fixture.focus.a() != null, "Connection ignored initial passenger placement");
            require(fixture.collector.routes.get(PLAYER) == 1, "Connection did not route speech media");
        }
    }

    private static void waitsForEngine() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.engineAvailable = false;
            fixture.enable();
            require(fixture.runtime.snapshot().phase == ModuleRuntime.Phase.STARTING, "Ready without engine");
            fixture.displays.put(PLAYER, 1);
            fixture.engineAvailable = true;
            fixture.scheduler.advance(100);
            require(fixture.runtime.snapshot().phase == ModuleRuntime.Phase.READY, "Engine retry failed");
            require(fixture.matches(PLAYER, 1, 3, 1), "Late engine cache missing");
        }
    }

    private static void cleansUpAfterServiceRecreation() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            fixture.display(PLAYER, 1);
            fixture.request(PLAYER, 1, 2);
            fixture.displays.put(OTHER, 1);
            Engine replacement = fixture.restartService();
            fixture.scheduler.advance(0);
            require(fixture.runtime.snapshot().phase == ModuleRuntime.Phase.READY, "New engine did not initialize");
            require(replacement.b(3, OTHER) == 4, "Volume still uses retired collector");
            fixture.disable();
            require(fixture.runtime.snapshot().phase == ModuleRuntime.Phase.READY,
                    "Cleanup waited on retired collector looper");
            require(fixture.collector.routes.get(PLAYER) == 0, "Retired engine route survived");
            require(fixture.backend.installed.isEmpty(), "Service recreation left hooks");
        }
    }

    private static void keepsCommunicationOutOfHeadphones() throws Exception {
        for (int usage : new int[] {2, 3, 6}) {
            try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
                fixture.enable();
                fixture.display(PLAYER, 1);
                fixture.request("media", PLAYER, 1, 2);
                Client media = fixture.focus.a();
                fixture.request("call", PLAYER, usage, 1);
                require(fixture.focus.a() == null, "Media source survived communication");
                require(media.state == 301, "Old media did not lose focus");
                require(fixture.collector.routes.get(PLAYER) == 0, "Call inherited headphone route");
                fixture.display(PLAYER, 1);
                require(fixture.collector.routes.get(PLAYER) == 0, "Display event routed call to headphones");
                require(fixture.volume(PLAYER) == 8, "Call controls headphone volume");
                fixture.request("media", PLAYER, 1, 3);
                require(fixture.focus.a() == null, "Parallel media rerouted package during call");
                fixture.abandon("call");
                fixture.request("media", PLAYER, 1, 3);
                require(fixture.focus.a() != null, "Media did not recover after call abandonment");
                require(fixture.collector.routes.get(PLAYER) == 1, "Media route not restored");
            }
        }
    }

    private static void rejectsCommunicationInSourceEntryPoint() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            fixture.call(fixture.focus, "a", Focus.REQUEST, "call", null, null, 2, 1, PLAYER, 1, 0, 0);
            require(fixture.focus.a() == null, "Direct source request admitted communication");
            require(fixture.collector.routes.get(PLAYER) == 0, "Direct source request routed communication");
        }
    }

    private static void serializesCallWithRouteWrite() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch writingRoute = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            fixture.beforeNativeRoute = (name, channel) -> {
                if (channel == 1) {
                    writingRoute.countDown();
                    await(resume);
                }
            };
            Future<?> display = workers.submit(() -> {
                fixture.display(PLAYER, 1);
                return null;
            });
            require(writingRoute.await(2, TimeUnit.SECONDS), "Route write did not start");
            Future<?> call = workers.submit(() -> fixture.request("call", PLAYER, 2, 1));
            try {
                call.get(150, TimeUnit.MILLISECONDS);
                throw new AssertionError("Call started before pending headphone route completed");
            } catch (TimeoutException expected) {
                // A package route write must finish before communication can start.
            } finally {
                resume.countDown();
                display.get(2, TimeUnit.SECONDS);
                call.get(2, TimeUnit.SECONDS);
            }
            require(fixture.collector.routes.get(PLAYER) == 0, "Concurrent route sent call to headphones");
        } finally {
            resume.countDown();
            workers.shutdownNow();
        }
    }

    private static void protectsCallsWhileDisabling() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch lookedUp = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            fixture.display(PLAYER, 1);
            fixture.request(PLAYER, 1, 2);
            fixture.afterGroup = () -> {
                lookedUp.countDown();
                await(resume);
            };
            Future<?> display = workers.submit(() -> {
                fixture.display(OTHER, 1);
                return null;
            });
            require(lookedUp.await(2, TimeUnit.SECONDS), "Display did not start");
            Future<?> disable = workers.submit(fixture::disable);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (fixture.group(PLAYER) != null && System.nanoTime() < deadline) Thread.yield();
                require(fixture.group(PLAYER) == null, "Module did not begin disabling");
                fixture.request("call", PLAYER, 2, 1);
                require(fixture.collector.routes.get(PLAYER) == 0,
                        "Call inherited headphones while disable waited for another OEM caller");
            } finally {
                resume.countDown();
                display.get(2, TimeUnit.SECONDS);
                disable.get(2, TimeUnit.SECONDS);
            }
            require(fixture.backend.installed.isEmpty(), "Safety hooks survived cleanup");
        } finally {
            resume.countDown();
            workers.shutdownNow();
        }
    }

    private static void rejectsCallWhenRouteCannotBeCleared() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures()) {
            fixture.enable();
            fixture.display(PLAYER, 1);
            fixture.request(PLAYER, 1, 2);
            RuntimeException failure = new IllegalStateException("Route reset rejected");
            fixture.switchFailure = failure;
            try {
                fixture.request("call", PLAYER, 2, 1);
                throw new AssertionError("Call proceeded with uncertain headphone route");
            } catch (IllegalStateException expected) {
                require(expected == failure, "Route failure replaced");
                require(fixture.runtime.snapshot().phase == ModuleRuntime.Phase.RESTART_REQUIRED,
                        "Unsafe route still reported ready");
            } finally {
                fixture.switchFailure = null;
            }
        }
    }

    private static void reportsUncertainCleanup() throws Exception {
        PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures();
        try {
            fixture.enable();
            fixture.display(PLAYER, 1);
            fixture.switchFailure = new IllegalStateException("Native route unavailable");
            fixture.disable();
            require(fixture.runtime.snapshot().phase == ModuleRuntime.Phase.RESTART_REQUIRED,
                    "Failed cleanup reported ready");
            require(fixture.backend.installed.isEmpty(), "Failed cleanup left hooks installed");
        } finally {
            try {
                fixture.close();
            } catch (IllegalStateException expected) {
                require(fixture.runtime.snapshot().phase == ModuleRuntime.Phase.RESTART_REQUIRED,
                        "Unexpected close failure");
            }
        }
    }

    private static void rejectsUnknownFirmware() throws Exception {
        try (PassengerHeadphonesFixtures fixture = new PassengerHeadphonesFixtures(IncompatibleService.class)) {
            fixture.enable();
            require(fixture.runtime.snapshot().phase != ModuleRuntime.Phase.READY, "Unknown ABI accepted");
            require(fixture.backend.installed.isEmpty(), "Partial hooks installed");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            require(latch.await(2, TimeUnit.SECONDS), "Timed out waiting for OEM operation");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
