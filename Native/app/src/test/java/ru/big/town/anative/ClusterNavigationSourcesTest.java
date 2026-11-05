package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ClusterNavigationSourcesTest {
    private final ClusterNavigationSources sources = new ClusterNavigationSources();

    private void update(ClusterNavigationSources.Source source, int distance, long now) {
        sources.update(source, true, 7, distance, "Road", now, now);
    }

    @Test
    public void latestOrdinaryRouteWinsWithoutHeartbeatFlicker() {
        update(ClusterNavigationSources.Source.YANDEX_NAVIGATOR, 100, 1000);
        update(ClusterNavigationSources.Source.DGIS, 200, 1100);
        update(ClusterNavigationSources.Source.YANDEX_NAVIGATOR, 90, 1200);
        assertEquals(200, sources.current(1200).distance);
        sources.clear(ClusterNavigationSources.Source.YANDEX_NAVIGATOR);
        assertEquals(200, sources.current(1200).distance);
    }

    @Test
    public void carPlayHasPriorityAndOnlyClearsItsOwnRoute() {
        update(ClusterNavigationSources.Source.CARPLAY, 10, 1000);
        update(ClusterNavigationSources.Source.YANDEX_NAVIGATOR, 100, 1100);
        update(ClusterNavigationSources.Source.DGIS, 200, 1200);
        assertEquals(10, sources.current(1200).distance);
        sources.clear(ClusterNavigationSources.Source.CARPLAY);
        assertEquals(200, sources.current(1200).distance);
        sources.clear(ClusterNavigationSources.Source.DGIS);
        assertEquals(100, sources.current(1200).distance);
    }

    @Test
    public void processDeathExpiresTheRouteAndFallsBackOnlyToLiveSources() {
        update(ClusterNavigationSources.Source.CARPLAY, 10, 1000);
        update(ClusterNavigationSources.Source.YANDEX_NAVIGATOR, 100, 5000);
        assertEquals(10, sources.current(5999).distance);
        assertEquals(100, sources.current(6000).distance);
        assertNull(sources.current(10000));
    }

    @Test
    public void disabledSnapshotsAndQueuedOldMessagesCannotRestoreAnOldTurn() {
        update(ClusterNavigationSources.Source.YANDEX_NAVIGATOR, 100, 1000);
        sources.setEnabled(false, 1200);
        update(ClusterNavigationSources.Source.DGIS, 200, 1300);
        assertNull(sources.current(1300));
        sources.setEnabled(true, 1400);
        assertNull(sources.current(1400));
        sources.update(ClusterNavigationSources.Source.YANDEX_NAVIGATOR, true, 7, 100, "", 1100, 1500);
        assertNull(sources.current(1500));
        update(ClusterNavigationSources.Source.YANDEX_NAVIGATOR, 50, 1600);
        assertEquals(50, sources.current(1600).distance);
    }

    @Test
    public void delayedStopCannotClearANewerSnapshot() {
        update(ClusterNavigationSources.Source.DGIS, 10, 2000);
        sources.update(ClusterNavigationSources.Source.DGIS, false, 0, 0, "", 1000, 2100);
        assertEquals(10, sources.current(2100).distance);
        sources.update(ClusterNavigationSources.Source.DGIS, false, 0, 0, "", 2200, 2200);
        assertNull(sources.current(2200));
    }

    @Test
    public void onlyExactNavigatorPackagesMayUseThePublicInput() {
        assertEquals(
                ClusterNavigationSources.Source.YANDEX_NAVIGATOR,
                ClusterNavigationSources.sourceForPackage("ru.yandex.yandexnavi"));
        assertEquals(
                ClusterNavigationSources.Source.YANDEX_MAPS,
                ClusterNavigationSources.sourceForPackage("ru.yandex.yandexmaps"));
        assertEquals(
                ClusterNavigationSources.Source.YANGO_MAPS,
                ClusterNavigationSources.sourceForPackage("com.yango.maps.android"));
        assertEquals(
                ClusterNavigationSources.Source.DGIS,
                ClusterNavigationSources.sourceForPackage("ru.dublgis.dgismobile"));
        for (String rejected :
                new String[] {
                    "ru.yandex.yandexnavi.fake",
                    "ru.yandex.yandexmaps.extra",
                    "com.yango.maps",
                    "com.yango.maps.android.extra",
                    "ru.big.town.restoremode",
                    "com.qinggan.cluster",
                    ""
                }) {
            assertNull(rejected, ClusterNavigationSources.sourceForPackage(rejected));
        }
        assertNull(ClusterNavigationSources.sourceForPackage(null));
    }

    @Test
    public void eachMapsSourceKeepsItsOwnTurn() {
        update(ClusterNavigationSources.Source.YANDEX_MAPS, 300, 1000);
        update(ClusterNavigationSources.Source.YANGO_MAPS, 400, 1100);
        assertEquals(400, sources.current(1100).distance);
        sources.clear(ClusterNavigationSources.Source.YANGO_MAPS);
        assertEquals(300, sources.current(1100).distance);
        sources.update(ClusterNavigationSources.Source.YANDEX_MAPS, false, 0, 0, "", 1200, 1200);
        assertNull(sources.current(1200));
    }

    @Test
    public void periodicUpdatesDoNotReorderSourcesButARestartedRouteDoes() {
        update(ClusterNavigationSources.Source.DGIS, 100, 1000);
        update(ClusterNavigationSources.Source.YANDEX_MAPS, 200, 1500);
        update(ClusterNavigationSources.Source.DGIS, 110, 2000);
        assertEquals(200, sources.current(2000).distance);
        sources.clear(ClusterNavigationSources.Source.DGIS);
        update(ClusterNavigationSources.Source.DGIS, 120, 2500);
        assertEquals(120, sources.current(2500).distance);
        update(ClusterNavigationSources.Source.CARPLAY, 10, 2600);
        assertEquals(10, sources.current(2600).distance);
    }
}
