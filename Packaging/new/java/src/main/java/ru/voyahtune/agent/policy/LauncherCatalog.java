package ru.voyahtune.agent.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.voyahtune.agent.runtime.WeakIdentityMap;

public final class LauncherCatalog<T> implements AutoCloseable {
    public static final String RESTORE = "ru.big.town.restoremode";
    public static final String CARPLAY = RESTORE + "/ru.big.town.restoremode.carplay.CarPlayLauncherActivity";
    public static final String PASSENGER = RESTORE + "/ru.big.town.restoremode.passenger.PassengerDashboardActivity";
    public static final String PREFIX = "__voyahtune_allapps__:";

    public interface Entries<T> {
        String packageName(T bean) throws Exception;
        String subtype(T bean) throws Exception;
        void subtype(T bean, String value) throws Exception;
        Template template(T bean) throws Exception;
        T create(Template template, String packageName) throws Exception;
    }
    public interface Source {
        List<String> installed() throws Exception;
        boolean carPlayAvailable();
        Template fallback() throws Exception;
    }
    public static final class Template {
        public final int icon;
        public final int label;
        public Template(int icon, int label) {
            if (icon <= 0 || label <= 0) throw new IllegalArgumentException("Valid OEM resource template required");
            this.icon = icon;
            this.label = label;
        }
    }
    public static final class TemplatePending extends Exception {
        public TemplatePending() { super("OEM All Apps resource template pending"); }
    }

    private final Entries<T> entries;
    private final Source source;
    private final WeakIdentityMap<List<T>, Owned<T>> owned = new WeakIdentityMap<>();
    private final ThreadLocal<Boolean> adding = ThreadLocal.withInitial(() -> false);
    private Template template;
    private boolean closed;

    public LauncherCatalog(Entries<T> entries, Source source) {
        this.entries = entries;
        this.source = source;
    }

    public String target(T bean) throws Exception {
        if (bean == null) return "";
        String subtype = entries.subtype(bean);
        return subtype != null && subtype.startsWith(PREFIX) && subtype.length() > PREFIX.length()
                ? subtype.substring(PREFIX.length()) : entries.packageName(bean);
    }

    public boolean synthetic(T bean) throws Exception {
        if (bean == null) return false;
        String subtype = entries.subtype(bean);
        return subtype != null && subtype.startsWith(PREFIX);
    }

    public synchronized boolean reconcile(List<T> list, int display) throws Exception {
        if (closed) throw new IllegalStateException("Launcher catalog is closed");
        if (list == null || display != 0 && display != 1 || adding.get()) return false;
        adding.set(true);
        try { return reconcileList(list, display); }
        finally { adding.remove(); }
    }

    private boolean reconcileList(List<T> list, int display) throws Exception {
        Owned<T> state = owned.get(list);
        if (state == null) { state = new Owned<>(); owned.put(list, state); }
        Set<String> existing = new LinkedHashSet<>();
        boolean changed = false;
        boolean carplay = source.carPlayAvailable();
        for (int index = list.size() - 1; index >= 0; index--) {
            T bean = list.get(index);
            if (bean == null) continue;
            String target = target(bean);
            if (CARPLAY.equals(target) && (!carplay || existing.contains(target)) || PASSENGER.equals(target) && display == 0
                    || RESTORE.equals(target) && display == 1) {
                if (!synthetic(bean)) state.removed.add(new Removed<>(index, bean));
                list.remove(index);
                changed = true;
                continue;
            }
            existing.add(target);
            if (CARPLAY.equals(target) || PASSENGER.equals(target)) state.created.add(bean);
            if (RESTORE.equals(target)) markExisting(state, bean);
        }
        List<String> targets = new ArrayList<>();
        for (String name : source.installed()) {
            targets.add(name);
            if (RESTORE.equals(name) && carplay) targets.add(CARPLAY);
        }
        if (display == 1) targets.add(PASSENGER);
        for (String target : targets) {
            if (display == 1 && RESTORE.equals(target) || existing.contains(target)) continue;
            T bean = entries.create(template(list), packageName(target));
            entries.subtype(bean, PREFIX + target);
            state.created.add(bean);
            list.add(bean);
            existing.add(target);
            changed = true;
        }
        return changed;
    }

    private void markExisting(Owned<T> state, T bean) throws Exception {
        if (!state.subtypes.containsKey(bean)) {
            String previous = entries.subtype(bean);
            state.subtypes.put(bean, previous != null && previous.startsWith(PREFIX) ? "" : previous);
        }
        entries.subtype(bean, PREFIX + RESTORE);
    }

    private Template template(List<T> list) throws Exception {
        for (T bean : list) {
            if (bean == null) continue;
            Template candidate = entries.template(bean);
            if (candidate != null) { template = candidate; return candidate; }
        }
        if (template == null) template = source.fallback();
        if (template == null) throw new TemplatePending();
        return template;
    }

    public static String packageName(String target) {
        int separator = target.indexOf('/');
        return separator < 0 ? target : target.substring(0, separator);
    }

    @Override public synchronized void close() throws Exception {
        closed = true;
        Exception failure = null;
        for (List<T> list : owned.keys()) {
            try { restore(list, owned.get(list)); owned.remove(list); }
            catch (Exception caught) { if (failure == null) failure = caught; else failure.addSuppressed(caught); }
        }
        if (failure != null) throw failure;
    }

    public synchronized void restore(List<T> list) throws Exception {
        Owned<T> state = owned.get(list);
        if (state == null) return;
        restore(list, state);
        owned.remove(list);
    }

    private void restore(List<T> list, Owned<T> state) throws Exception {
        list.removeIf(state.created::contains);
        for (Map.Entry<T, String> previous : state.subtypes.entrySet()) {
            if ((PREFIX + RESTORE).equals(entries.subtype(previous.getKey()))) entries.subtype(previous.getKey(), previous.getValue());
        }
        for (int index = state.removed.size() - 1; index >= 0; index--) {
            Removed<T> removed = state.removed.get(index);
            boolean present = false;
            for (T bean : list) if (bean == removed.bean || target(bean).equals(entries.packageName(removed.bean))) { present = true; break; }
            if (!present) list.add(Math.min(removed.index, list.size()), removed.bean);
        }
    }

    private static final class Owned<T> {
        final Set<T> created = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<T, String> subtypes = new IdentityHashMap<>();
        final List<Removed<T>> removed = new ArrayList<>();
    }
    private static final class Removed<T> {
        final int index;
        final T bean;
        Removed(int index, T bean) { this.index = index; this.bean = bean; }
    }
}
