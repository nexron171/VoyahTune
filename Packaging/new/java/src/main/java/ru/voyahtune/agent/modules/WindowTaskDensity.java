package ru.voyahtune.agent.modules;

import ru.voyahtune.agent.policy.WindowPolicy;
import ru.voyahtune.agent.runtime.OemAccess;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

final class WindowTaskDensity {
    private final Class<?> configuration;
    private final WeakIdentityMap<Object, Density> originals = new WeakIdentityMap<>();
    private final WeakIdentityMap<Object, Boolean> applying = new WeakIdentityMap<>();

    WindowTaskDensity(Class<?> configuration) { this.configuration = configuration; }

    synchronized void apply(Object task, String name, WindowPolicy policy) throws Exception {
        if (task == null || applying.get(task) != null) return;
        int desired = policy.density(name);
        if (desired <= 0) { restore(task); return; }
        Object current = OemAccess.call(task, "getRequestedOverrideConfiguration");
        int density = (Integer) OemAccess.read(current, "densityDpi");
        Density record = originals.get(task);
        if (record == null) {
            if (originals.size() >= 128) return;
            record = new Density(density, name);
            originals.put(task, record);
        } else if (density != record.applied) record.original = density;
        record.applied = desired;
        if (density != desired) change(task, current, desired);
    }

    private void change(Object task, Object current, int density) throws Exception {
        applying.put(task, true);
        try {
            Object next = OemAccess.construct(configuration, current);
            OemAccess.write(next, "densityDpi", density);
            OemAccess.call(task, "onRequestedOverrideConfigurationChanged", next);
        } finally { applying.remove(task); }
    }

    private void restore(Object task) throws Exception {
        Density record = originals.get(task);
        if (record == null) return;
        Object current = OemAccess.call(task, "getRequestedOverrideConfiguration");
        if (Integer.valueOf(record.applied).equals(OemAccess.read(current, "densityDpi"))) change(task, current, record.original);
        originals.remove(task);
    }

    synchronized void restoreAll(WindowPolicy policy, boolean all) throws Exception {
        Exception failure = null;
        for (Object task : originals.keys()) {
            Density record = originals.get(task);
            if (all || !policy.enabled || policy.density(record.name) <= 0) {
                try { restore(task); }
                catch (Exception caught) {
                    if (failure == null) failure = caught;
                    else failure.addSuppressed(caught);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private static final class Density {
        int original;
        int applied;
        final String name;
        Density(int density, String name) { original = density; applied = density; this.name = name; }
    }
}
