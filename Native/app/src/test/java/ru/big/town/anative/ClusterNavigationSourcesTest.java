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
        update(ClusterNavigationSources.Source.YANDEX, 100, 1000);
        update(ClusterNavigationSources.Source.DGIS, 200, 1100);
        update(ClusterNavigationSources.Source.YANDEX, 90, 1200);
        assertEquals(200, sources.current(1200).distance);
        sources.clear(ClusterNavigationSources.Source.YANDEX);
        assertEquals(200, sources.current(1200).distance);
    }

    @Test
    public void carPlayHasPriorityAndOnlyClearsItsOwnRoute() {
        update(ClusterNavigationSources.Source.CARPLAY, 10, 1000);
        update(ClusterNavigationSources.Source.YANDEX, 100, 1100);
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
        update(ClusterNavigationSources.Source.YANDEX, 100, 5000);
        assertEquals(10, sources.current(5999).distance);
        assertEquals(100, sources.current(6000).distance);
        assertNull(sources.current(10000));
    }

    @Test
    public void disabledSnapshotsAndQueuedOldMessagesCannotRestoreAnOldTurn() {
        update(ClusterNavigationSources.Source.YANDEX, 100, 1000);
        sources.setEnabled(false, 1200);
        update(ClusterNavigationSources.Source.DGIS, 200, 1300);
        assertNull(sources.current(1300));
        sources.setEnabled(true, 1400);
        assertNull(sources.current(1400));
        sources.update(ClusterNavigationSources.Source.YANDEX, true, 7, 100, "", 1100, 1500);
        assertNull(sources.current(1500));
        update(ClusterNavigationSources.Source.YANDEX, 50, 1600);
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
                ClusterNavigationSources.Source.YANDEX,
                ClusterNavigationSources.sourceForPackage("ru.yandex.yandexnavi"));
        assertEquals(
                ClusterNavigationSources.Source.DGIS,
                ClusterNavigationSources.sourceForPackage("ru.dublgis.dgismobile"));
        assertNull(ClusterNavigationSources.sourceForPackage("ru.yandex.yandexnavi.fake"));
        assertNull(ClusterNavigationSources.sourceForPackage("ru.big.town.restoremode"));
        assertNull(ClusterNavigationSources.sourceForPackage(null));
    }
}
