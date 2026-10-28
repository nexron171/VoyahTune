package ru.voyahtune.agent.modules;

import java.lang.reflect.Method;
import java.util.Map;
import ru.voyahtune.agent.HookRegistry;
import ru.voyahtune.agent.policy.WifiScanLease;
import ru.voyahtune.agent.runtime.ModuleRuntime;
import ru.voyahtune.agent.runtime.ModuleScope;
import ru.voyahtune.agent.runtime.ResourceGroup;

public final class WindowWifiModule implements ModuleRuntime.Module {
    public interface Endpoint {
        Object manager();
        Method setter();
        boolean enabled() throws Exception;
        void setEnabled(boolean enabled) throws Exception;
        boolean post(Runnable action, long delay);
        void remove(Runnable action);
        void drain(AutoCloseable cleanup) throws Exception;
    }

    public interface Platform {
        long bootCount() throws Exception;
        long now();
        String lease() throws Exception;
        Endpoint discover() throws Exception;
        void error(Exception failure);
    }

    public static final class Pending extends Exception {
        public Pending(String message) { super(message); }
    }

    private final HookRegistry hooks;
    private final Platform platform;
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private long boot = -1;
    private int attempts;
    private Endpoint endpoint;
    private boolean unsupported;

    public WindowWifiModule(HookRegistry hooks, Platform platform) {
        this.hooks = hooks;
        this.platform = platform;
    }

    @Override public void prepare(Map<String, String> configuration) {
        if (boot >= 0 || unsupported) return;
        try {
            boot = platform.bootCount();
            if (boot < 0) throw new IllegalStateException("Wi-Fi lease boot identity unavailable");
        } catch (Exception unavailable) {
            unsupported = true;
            error(unavailable);
        }
    }

    @Override public void install(ModuleScope scope, Map<String, String> configuration) {}

    @Override public void configure(ModuleScope scope, Map<String, String> configuration) throws Exception {
        if (!unsupported && endpoint == null) scope.schedule(0, () -> discover(scope));
    }

    private void discover(ModuleScope scope) {
        if (endpoint != null || unsupported) return;
        ResourceGroup resources = new ResourceGroup();
        try {
            Endpoint found = platform.discover();
            if (found == null) throw new Pending("Wi-Fi manager is pending");
            WifiScanLease lease = new WifiScanLease(boot, new WifiScanLease.IO() {
                @Override public long now() { return platform.now(); }
                @Override public String lease() throws Exception { return platform.lease(); }
                @Override public boolean enabled() throws Exception { return found.enabled(); }
                @Override public void setEnabled(boolean enabled) throws Exception {
                    applying.set(true);
                    try { found.setEnabled(enabled); }
                    finally { applying.remove(); }
                }
                @Override public void error(Exception failure) { WindowWifiModule.this.error(failure); }
                @Override public void uncertain(Exception failure) { scope.requireRestart(failure); }
            });
            Runnable[] action = new Runnable[1];
            Object polling = new Object();
            boolean[] stopped = {false};
            action[0] = () -> {
                synchronized (polling) { if (stopped[0]) return; }
                boolean[] again = {true};
                if (scope.stoppingOrFailed()) {
                    lease.stop();
                    again[0] = lease.poll();
                } else scope.listener(() -> again[0] = lease.poll()).run();
                synchronized (polling) {
                    if (!stopped[0] && again[0] && !found.post(action[0], 1000)) {
                        stopped[0] = true;
                        try { lease.close(); }
                        catch (Exception failure) { scope.requireRestart(failure); }
                        error(new IllegalStateException("Wi-Fi looper stopped"));
                    }
                }
            };
            resources.own(() -> {
                synchronized (polling) { stopped[0] = true; found.remove(action[0]); }
                found.drain(lease);
            });
            resources.own(hooks.install("windows-wifi", found.setter(), scope.callback(invocation -> {
                if (invocation.receiver() != found.manager() || applying.get()) return invocation.proceed();
                synchronized (lease) {
                    invocation.arguments()[0] = lease.external((Boolean) invocation.arguments()[0]);
                    return invocation.proceed();
                }
            })));
            scope.own(resources);
            endpoint = found;
            if (!found.post(action[0], 0)) throw new IllegalStateException("Wi-Fi looper unavailable");
        } catch (Pending pending) {
            if (++attempts <= 30) {
                try { scope.schedule(1000, () -> discover(scope)); }
                catch (Exception failure) { scope.requireRestart(failure); }
            } else {
                unsupported = true;
                error(pending);
            }
        } catch (Exception failure) {
            unsupported = true;
            try { resources.close(); }
            catch (Exception cleanup) { failure.addSuppressed(cleanup); scope.requireRestart(failure); }
            if (hooks.requiresRestart()) scope.requireRestart(failure);
            error(failure);
        }
    }

    private void error(Exception failure) {
        try { platform.error(failure); }
        catch (RuntimeException ignored) { /* Optional diagnostics must not affect system_server. */ }
    }
}
