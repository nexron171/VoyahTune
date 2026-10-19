package ru.big.town.anative;

import java.util.EnumMap;

/** CarPlay wins; ordinary routes keep their priority across periodic delivery heartbeats. */
final class ClusterNavigationSources {
    enum Source {
        CARPLAY,
        YANDEX,
        DGIS
    }

    private static final class Entry {
        final ClusterNavigationLease lease = new ClusterNavigationLease();
        long lastSample = -1;
        long started;
    }

    private final EnumMap<Source, Entry> entries = new EnumMap<>(Source.class);
    private long sequence;
    private long settingsChangedAt = -1;
    private boolean enabled = true;

    ClusterNavigationSources() {
        for (Source source : Source.values()) {
            entries.put(source, new Entry());
        }
    }

    static Source sourceForPackage(String packageName) {
        if ("ru.yandex.yandexnavi".equals(packageName)) {
            return Source.YANDEX;
        }
        if ("ru.dublgis.dgismobile".equals(packageName)) {
            return Source.DGIS;
        }
        return null;
    }

    void setEnabled(boolean enabled, long now) {
        if (this.enabled != enabled) {
            settingsChangedAt = now;
        }
        this.enabled = enabled;
        for (Entry entry : entries.values()) {
            entry.lease.setEnabled(enabled);
        }
    }

    void update(
            Source source,
            boolean active,
            int icon,
            int distance,
            String road,
            long sampled,
            long now) {
        Entry entry = entries.get(source);
        if (entry == null
                || !enabled
                || sampled < settingsChangedAt
                || sampled < entry.lastSample
                || sampled < 0
                || sampled > now
                || now - sampled >= ClusterNavigationLease.TIMEOUT_MS) {
            return;
        }
        boolean wasActive = entry.lease.current(now) != null;
        entry.lastSample = sampled;
        entry.lease.update(active, icon, distance, road, sampled, now);
        if (!wasActive && entry.lease.current(now) != null) {
            entry.started = ++sequence;
        }
    }

    void clear(Source source) {
        entries.get(source).lease.clear();
    }

    ClusterNavigationLease.Maneuver current(long now) {
        ClusterNavigationLease.Maneuver carPlay = entries.get(Source.CARPLAY).lease.current(now);
        if (carPlay != null) {
            return carPlay;
        }
        Entry selected = null;
        for (Source source : new Source[] {Source.YANDEX, Source.DGIS}) {
            Entry entry = entries.get(source);
            if (entry.lease.current(now) != null
                    && (selected == null || entry.started > selected.started)) {
                selected = entry;
            }
        }
        return selected == null ? null : selected.lease.current(now);
    }
}
