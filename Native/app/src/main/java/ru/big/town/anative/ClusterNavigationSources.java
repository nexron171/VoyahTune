package ru.big.town.anative;

import java.util.EnumMap;

/** CarPlay wins; ordinary routes keep their priority across periodic delivery heartbeats. */
final class ClusterNavigationSources {
    enum Source {
        CARPLAY,
        YANDEX_NAVIGATOR,
        YANDEX_MAPS,
        YANGO_MAPS,
        DGIS
    }

    /** Ordinary sources in arbitration order; CarPlay is handled separately. */
    private static final Source[] ROUTE_SOURCES = {
        Source.YANDEX_NAVIGATOR, Source.YANDEX_MAPS, Source.YANGO_MAPS, Source.DGIS
    };

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
        if (packageName == null) {
            return null;
        }
        switch (packageName) {
            case "ru.yandex.yandexnavi":
                return Source.YANDEX_NAVIGATOR;
            case "ru.yandex.yandexmaps":
                return Source.YANDEX_MAPS;
            case "com.yango.maps.android":
                return Source.YANGO_MAPS;
            case "ru.dublgis.dgismobile":
                return Source.DGIS;
            default:
                return null;
        }
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
        for (Source source : ROUTE_SOURCES) {
            Entry entry = entries.get(source);
            if (entry.lease.current(now) != null
                    && (selected == null || entry.started > selected.started)) {
                selected = entry;
            }
        }
        return selected == null ? null : selected.lease.current(now);
    }
}
