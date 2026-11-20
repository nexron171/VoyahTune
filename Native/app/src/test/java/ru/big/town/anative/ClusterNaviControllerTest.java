package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = Application.class)
public class ClusterNaviControllerTest {
    private static final String NAVI_PACKAGE = "com.example.nav";

    private static final class FakeBackend implements ClusterNaviController.Backend {
        final List<String> calls = new ArrayList<>();
        String component = NAVI_PACKAGE + "/" + NAVI_PACKAGE + ".MainActivity";
        boolean stoppable = true;
        RuntimeException failure;

        @Override
        public String configuredComponent() {
            return component;
        }

        @Override
        public boolean canStopApplication(String packageName) {
            calls.add("canStop:" + packageName);
            return stoppable;
        }

        @Override
        public void forceStopApplication(String packageName) {
            if (failure != null) throw failure;
            calls.add("stop:" + packageName);
        }

        @Override
        public void sendStateBroadcast(int enabled) {
            calls.add("broadcast:" + enabled);
        }

        @Override
        public void launchApplication(String packageName) {
            calls.add("launch:" + packageName);
        }
    }

    @Test
    public void startStopsTheConfiguredPackageBeforeEnablingTheClusterCard() {
        FakeBackend backend = new FakeBackend();
        assertEquals("", ClusterNaviController.execute(backend, "cluster_navi:start"));
        assertEquals(
                List.of(
                        "canStop:" + NAVI_PACKAGE,
                        "stop:" + NAVI_PACKAGE,
                        "broadcast:" + ClusterNaviController.STATE_ENABLED),
                backend.calls);
    }

    @Test
    public void stopStopsThePackageAndDisablesTheClusterCard() {
        FakeBackend backend = new FakeBackend();
        assertEquals("", ClusterNaviController.execute(backend, "cluster_navi:stop"));
        assertEquals(
                List.of(
                        "canStop:" + NAVI_PACKAGE,
                        "stop:" + NAVI_PACKAGE,
                        "broadcast:" + ClusterNaviController.STATE_DISABLED),
                backend.calls);
    }

    @Test
    public void mainScreenFreesTheClusterCardBeforeLaunchingTheApplication() {
        FakeBackend backend = new FakeBackend();
        assertEquals("", ClusterNaviController.execute(backend, "cluster_navi:main_screen"));
        assertEquals(
                List.of(
                        "canStop:" + NAVI_PACKAGE,
                        "stop:" + NAVI_PACKAGE,
                        "broadcast:" + ClusterNaviController.STATE_DISABLED,
                        "launch:" + NAVI_PACKAGE),
                backend.calls);
    }

    @Test
    public void missingOrUnreadableSettingFallsBackToTheBridgeDefaultNavigator() {
        for (String configured : new String[] {null, "", "not a component"}) {
            assertEquals(
                    ClusterNaviController.FALLBACK_PACKAGE,
                    ClusterNaviController.configuredPackage(configured));
        }
        assertEquals(
                NAVI_PACKAGE,
                ClusterNaviController.configuredPackage(NAVI_PACKAGE + "/.MainActivity"));
    }

    @Test
    public void fallbackIsUsedForTheStopAndNotOnlyForTheBroadcast() {
        FakeBackend backend = new FakeBackend();
        backend.component = null;
        assertEquals("", ClusterNaviController.execute(backend, "cluster_navi:stop"));
        assertTrue(
                backend.calls.toString(),
                backend.calls.contains("stop:" + ClusterNaviController.FALLBACK_PACKAGE));
    }

    @Test
    public void unstoppablePackageIsReportedAndNeverBroadcasts() {
        FakeBackend backend = new FakeBackend();
        backend.stoppable = false;
        assertFalse(ClusterNaviController.execute(backend, "cluster_navi:start").isEmpty());
        assertEquals(List.of("canStop:" + NAVI_PACKAGE), backend.calls);
    }

    /** Launching while the card is still owned by the cluster would move the app right back. */
    @Test
    public void unstoppablePackageIsNeverLaunchedOnItsOwnScreen() {
        FakeBackend backend = new FakeBackend();
        backend.stoppable = false;
        assertFalse(ClusterNaviController.execute(backend, "cluster_navi:main_screen").isEmpty());
        assertEquals(List.of("canStop:" + NAVI_PACKAGE), backend.calls);
    }

    @Test
    public void unknownActionHasNoSideEffects() {
        FakeBackend backend = new FakeBackend();
        assertEquals(
                "Неизвестное действие",
                ClusterNaviController.execute(backend, "cluster_navi:toggle"));
        assertEquals(List.of(), backend.calls);
    }

    @Test
    public void stopFailureIsReportedInsteadOfAClaimedSuccess() {
        FakeBackend backend = new FakeBackend();
        backend.failure = new IllegalStateException("force-stop rejected");
        assertFalse(ClusterNaviController.execute(backend, "cluster_navi:start").isEmpty());
        assertEquals(List.of("canStop:" + NAVI_PACKAGE), backend.calls);
    }
}
