package ru.voyahtune.contracts;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.modules.WindowWifiModule;
import ru.voyahtune.agent.runtime.ModuleRuntime;

public final class WindowWifiModuleTest {
    public static void main(String[] arguments) throws Throwable {
        verify();
        System.out.println("WindowWifiModuleTest: passed");
    }

    static void verify() throws Throwable {
        domain();
        try (Harness harness = new Harness()) {
            harness.scheduler.advance(0);
            harness.flush();
            require(!harness.manager.enabled && harness.manager.writes.equals(List.of(false)), "Initial pause missing");
            harness.external(harness.manager, false);
            harness.external(harness.manager, true);
            require(!harness.manager.enabled, "External desired policy restarted scans during lease");
            WifiManager other = new WifiManager(harness);
            harness.external(other, true);
            require(other.enabled, "Unrelated Wi-Fi manager intercepted");
            harness.runtime.apply("code", 2, List.of("windows"), Map.of());
            harness.scheduler.advance(0);
            require(harness.queue.size() == 1, "Configuration duplicated polling");
            harness.lease = "";
            harness.advance(1000);
            require(harness.manager.enabled, "Latest external policy not restored");
            harness.lease = "7:" + harness.now;
            harness.advance(1000);
            require(!harness.manager.enabled, "Renewed lease not acquired");
            harness.runtime.close();
            require(harness.manager.enabled && harness.queue.isEmpty() && harness.backend.installed.isEmpty(), "Cleanup left Wi-Fi effects");
            int writes = harness.manager.writes.size();
            harness.advance(20000);
            require(harness.manager.writes.size() == writes, "Closed poll mutated Wi-Fi");
        }
        try (Harness harness = new Harness()) {
            harness.pending = true;
            harness.scheduler.advance(0);
            require(harness.backend.installed.isEmpty(), "Pending manager installed a hook");
            harness.pending = false;
            harness.scheduler.advance(1000);
            harness.flush();
            require(!harness.manager.enabled, "Late Wi-Fi manager not discovered");
        }
        try (Harness harness = new Harness()) {
            harness.scheduler.advance(0);
            harness.flush();
            harness.manager.ignoreRestore = true;
            harness.lease = "";
            harness.advance(1000);
            require(harness.runtime.snapshot().phase == ModuleRuntime.Phase.RESTART_REQUIRED, "Unconfirmed restoration reported ready");
            harness.manager.ignoreRestore = false;
            harness.runtime.close();
            require(harness.manager.enabled && harness.runtime.snapshot().phase == ModuleRuntime.Phase.CLOSED, "Cleanup did not retry uncertain restoration");
        }
    }

    private static void domain() throws Exception {
        for (String lease : List.of("7:0", "7:1001", "7:-1", "7:1000\n", "6:1000", "9999999999999999999999:1000")) {
            JsonObject input = new JsonObject();
            input.addProperty("lease", lease);
            WindowWifiContractDriver.Harness harness = new WindowWifiContractDriver.Harness(input);
            harness.advance(0);
            require(harness.writes.isEmpty(), "Invalid or future lease accepted: " + lease);
        }
        WindowWifiContractDriver.Harness harness = new WindowWifiContractDriver.Harness(new JsonObject());
        harness.advance(0);
        harness.now = 10999;
        harness.controller.poll();
        require(!harness.enabled, "Lease expired before ten seconds");
        harness.now = 11000;
        harness.controller.poll();
        require(harness.enabled, "Lease did not expire at ten seconds");
    }

    static final class Harness implements AutoCloseable, WindowWifiModule.Platform {
        final ManualScheduler scheduler = new ManualScheduler();
        final FixtureHookBackend backend = new FixtureHookBackend(PhoneContractDriver.nativeHooks);
        final HookRegistry hooks = new HookRegistry(backend);
        final List<Queued> queue = new ArrayList<>();
        final WifiManager manager = new WifiManager(this);
        final Method setter = WifiManager.class.getDeclaredMethod("setAutoJoinEnabledExternal", boolean.class);
        final ModuleRuntime runtime;
        long now = 1000;
        String lease = "7:1000";
        boolean inWifi;
        boolean pending;

        Harness() throws Exception {
            runtime = new ModuleRuntime("code", scheduler, id -> new WindowWifiModule(hooks, this));
            runtime.apply("code", 1, List.of("windows"), Map.of());
        }
        @Override public long bootCount() { return 7; }
        @Override public long now() { return now; }
        @Override public String lease() { return lease; }
        @Override public void error(Exception failure) {}
        @Override public WindowWifiModule.Endpoint discover() throws Exception {
            if (pending) throw new WindowWifiModule.Pending("Wi-Fi pending");
            return new WindowWifiModule.Endpoint() {
                @Override public Object manager() { return manager; }
                @Override public Method setter() { return setter; }
                @Override public boolean enabled() { return manager.enabled; }
                @Override public void setEnabled(boolean enabled) throws Exception {
                    try { backend.invoke(setter, manager, enabled); }
                    catch (Exception failure) { throw failure; }
                    catch (Throwable failure) { throw new AssertionError(failure); }
                }
                @Override public boolean post(Runnable action, long delay) { queue.add(new Queued(now + delay, action)); return true; }
                @Override public void remove(Runnable action) { queue.removeIf(item -> item.action == action); }
                @Override public void drain(AutoCloseable cleanup) throws Exception {
                    inWifi = true;
                    try { cleanup.close(); }
                    finally { inWifi = false; }
                }
            };
        }
        void external(WifiManager receiver, boolean enabled) throws Throwable {
            inWifi = true;
            try { backend.invoke(setter, receiver, enabled); }
            finally { inWifi = false; }
        }
        void flush() { advance(0); }
        void advance(long duration) {
            long until = now + duration;
            for (int count = 0; ; count++) {
                if (count > 100) throw new AssertionError("Unbounded Wi-Fi handler polling");
                queue.sort(java.util.Comparator.comparingLong(item -> item.at));
                if (queue.isEmpty() || queue.get(0).at > until) break;
                Queued item = queue.remove(0);
                now = item.at;
                inWifi = true;
                try { item.action.run(); }
                finally { inWifi = false; }
            }
            now = until;
        }
        @Override public void close() throws Exception { runtime.close(); hooks.close(); }
    }

    static final class Queued {
        final long at;
        final Runnable action;
        Queued(long at, Runnable action) { this.at = at; this.action = action; }
    }

    static final class WifiManager {
        final Harness harness;
        final List<Boolean> writes = new ArrayList<>();
        boolean enabled = true;
        boolean ignoreRestore;
        WifiManager(Harness harness) { this.harness = harness; }
        void setAutoJoinEnabledExternal(boolean desired) {
            require(harness.inWifi, "Wi-Fi mutation outside its handler");
            writes.add(desired);
            if (!desired || !ignoreRestore) enabled = desired;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
