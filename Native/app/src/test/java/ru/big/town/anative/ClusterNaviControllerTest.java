package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowPackageManager;

import ru.big.town.common.VehiclePlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = Application.class)
public class ClusterNaviControllerTest {
    private static final String NAVI_PACKAGE = "com.example.nav";

    private static final class FakeBackend implements ClusterNaviController.Backend {
        final List<String> calls = new ArrayList<>();
        String component = NAVI_PACKAGE + "/" + NAVI_PACKAGE + ".MainActivity";
        boolean stoppable = true;
        VehiclePlatform platform = VehiclePlatform.H97C;
        boolean mediaCardAvailable = true;
        boolean launcherAvailable = true;
        RuntimeException failure;

        String execute(String action) {
            return ClusterNaviController.execute(this, platform, action);
        }

        @Override
        public void releaseCentralCard(boolean selectMediaCard) {
            if (selectMediaCard) calls.add("selectMedia");
        }

        @Override
        public String launcherComponent(String packageName) {
            return launcherAvailable ? packageName + "/.Launcher" : null;
        }

        @Override
        public void openCentralApp(String component) {
            calls.add("openCenter:" + component);
        }

        @Override
        public void closeMediaCard(String packageName) {
            calls.add("closeMedia:" + packageName);
        }

        @Override
        public boolean openMediaCard(String packageName) {
            calls.add("openMedia:" + packageName);
            return mediaCardAvailable;
        }

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
    public void assignmentsLaunchTheirOwnPackagesInTheRequestedCards() {
        FakeBackend backend = new FakeBackend();
        assertEquals("", backend.execute("cluster_app:media:com.example.maps"));
        assertEquals(
                List.of(
                        "canStop:com.example.maps",
                        "selectMedia",
                        "closeMedia:com.example.maps",
                        "stop:com.example.maps",
                        "openMedia:com.example.maps"),
                backend.calls);
        backend.calls.clear();
        assertEquals("", backend.execute("cluster_app:center:com.example.music"));
        assertEquals(
                List.of(
                        "canStop:com.example.music",
                        "closeMedia:com.example.music",
                        "stop:com.example.music",
                        "openCenter:com.example.music/.Launcher"),
                backend.calls);
    }

    @Test
    public void bothH97MediaOnlyModelsRunAnImportedCenterAssignmentOnTheLeft() {
        for (VehiclePlatform model : List.of(VehiclePlatform.H97X, VehiclePlatform.H97Y)) {
            FakeBackend backend = new FakeBackend();
            backend.platform = model;
            assertEquals("", backend.execute("cluster_app:center:com.example.maps"));
            assertTrue(backend.calls.contains("openMedia:com.example.maps"));
            assertFalse(backend.calls.contains("openCenter:com.example.maps/.Launcher"));
        }
    }

    @Test
    public void missingAssignedLauncherDoesNotStopOrOpenTheDefaultApplication() {
        FakeBackend backend = new FakeBackend();
        backend.launcherAvailable = false;
        assertFalse(backend.execute("cluster_app:center:com.example.missing").isEmpty());
        assertEquals(List.of("canStop:com.example.missing"), backend.calls);
    }

    @Test
    public void startReleasesTheOldHostAndOpensTheConfiguredAppOnBothMediaCardModels() {
        for (VehiclePlatform platform : List.of(VehiclePlatform.H97X, VehiclePlatform.H97Y)) {
            FakeBackend backend = new FakeBackend();
            backend.platform = platform;
            assertEquals("", backend.execute("cluster_navi:start"));
            assertEquals(
                    List.of(
                            "canStop:" + NAVI_PACKAGE,
                            "closeMedia:" + NAVI_PACKAGE,
                            "stop:" + NAVI_PACKAGE,
                            "openMedia:" + NAVI_PACKAGE),
                    backend.calls);
        }
    }

    @Test
    public void stopAndHandbackReleaseTheMediaCardOnH97XY() {
        for (VehiclePlatform platform : List.of(VehiclePlatform.H97X, VehiclePlatform.H97Y)) {
            FakeBackend backend = new FakeBackend();
            backend.platform = platform;
            assertEquals("", backend.execute("cluster_navi:stop"));
            assertEquals(
                    List.of(
                            "canStop:" + NAVI_PACKAGE,
                            "closeMedia:" + NAVI_PACKAGE,
                            "stop:" + NAVI_PACKAGE),
                    backend.calls);

            backend.calls.clear();
            assertEquals("", backend.execute("cluster_navi:main_screen"));
            assertEquals(
                    List.of(
                            "canStop:" + NAVI_PACKAGE,
                            "closeMedia:" + NAVI_PACKAGE,
                            "stop:" + NAVI_PACKAGE,
                            "launch:" + NAVI_PACKAGE),
                    backend.calls);
        }
    }

    @Test
    public void unavailableMediaCardReportsFailureWithoutOpeningTheCentralCard() {
        FakeBackend backend = new FakeBackend();
        backend.platform = VehiclePlatform.H97X;
        backend.mediaCardAvailable = false;
        assertFalse(backend.execute("cluster_navi:start").isEmpty());
        assertEquals(
                List.of(
                        "canStop:" + NAVI_PACKAGE,
                        "closeMedia:" + NAVI_PACKAGE,
                        "stop:" + NAVI_PACKAGE,
                        "openMedia:" + NAVI_PACKAGE),
                backend.calls);
    }

    @Test
    public void mediaLaunchReleasesAPreviouslyEnabledCentralCardAndTargetsTheOemDisplay() {
        Application app = RuntimeEnvironment.getApplication();
        ComponentName navigator = registerLauncher(app, NAVI_PACKAGE);
        Settings.Global.putString(
                app.getContentResolver(),
                ClusterNaviController.COMPONENT_SETTING,
                navigator.flattenToShortString());
        String centralCardSetting = "voyahtune_cluster_navi";
        Settings.Global.putInt(app.getContentResolver(), centralCardSetting, 1);
        ShadowDisplayManager.addDisplay("w1920dp-h720dp-land-mdpi", "Cluster-Navi-Display");
        int display =
                ShadowDisplayManager.addDisplay(
                        "w1920dp-h720dp-land-mdpi", "Cluster-Media-Display");

        ClusterNaviController.PlatformBackend backend =
                new ClusterNaviController.PlatformBackend(app);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(
                    "",
                    ClusterNaviController.execute(
                            backend, VehiclePlatform.H97X, "cluster_navi:start"));
            ShadowActivity.IntentForResult launch = shadowOf(app).getNextStartedActivityForResult();
            assertNotNull(launch);
            assertEquals(
                    new ComponentName(app, ClusterMediaHostActivity.class),
                    launch.intent.getComponent());
            assertEquals(display, launch.options.getInt("android.activity.launchDisplayId", -1));
        }
        assertEquals(0, Settings.Global.getInt(app.getContentResolver(), centralCardSetting, -1));
        List<Integer> centralCardRequests = new ArrayList<>();
        for (Intent broadcast : shadowOf(app).getBroadcastIntents()) {
            if (ClusterNaviController.BROADCAST_ACTION.equals(broadcast.getAction())) {
                centralCardRequests.add(broadcast.getIntExtra("enable", -1));
            }
        }
        assertEquals(List.of(ClusterNaviController.STATE_DISABLED), centralCardRequests);
    }

    @Test
    public void steeringAndCustomVoiceDispatchTheirOwnTargetsAndReturnFailuresInOrder()
            throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ComponentName maps = registerLauncher(app, "com.example.maps");
        ComponentName music = registerLauncher(app, "com.example.music");
        Settings.Global.putString(
                app.getContentResolver(),
                ClusterNaviController.COMPONENT_SETTING,
                NAVI_PACKAGE + "/.Default");

        String steering = "cluster_app:center:com.example.maps";
        Intent press = new Intent("ru.big.town.anative.STEER_ACTION").putExtra("action", steering);
        Settings.Global.putString(app.getContentResolver(), "voyahtune_steerStarLong", steering);
        new SetModesReceiverDynamic().onReceive(app, press);
        awaitMain(() -> !centralTargets(app).isEmpty());
        assertEquals(List.of(maps.flattenToShortString()), centralTargets(app));

        AtomicReference<List<ActionResult>> completed = new AtomicReference<>();
        SetModesReceiverDynamic.runConfiguredActions(
                app,
                List.of(
                        "cluster_app:center:com.example.uninstalled",
                        "cluster_app:center:com.example.music"),
                completed::set);
        awaitMain(() -> completed.get() != null);
        assertEquals(2, completed.get().size());
        assertFalse(completed.get().get(0).accepted);
        assertTrue(completed.get().get(0).error.contains("com.example.uninstalled"));
        assertTrue(completed.get().get(1).accepted);
        assertEquals(
                List.of(maps.flattenToShortString(), music.flattenToShortString()),
                centralTargets(app));
        assertEquals(
                NAVI_PACKAGE + "/.Default",
                Settings.Global.getString(
                        app.getContentResolver(), ClusterNaviController.COMPONENT_SETTING));
    }

    private static ComponentName registerLauncher(Application app, String packageName) {
        ComponentName component = ComponentName.createRelative(packageName, ".MainActivity");
        ShadowPackageManager packages = shadowOf(app.getPackageManager());
        packages.addActivityIfNotPresent(component);
        IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        packages.addIntentFilterForActivity(component, launcher);
        return component;
    }

    private static List<String> centralTargets(Application app) {
        List<String> targets = new ArrayList<>();
        for (Intent broadcast : shadowOf(app).getBroadcastIntents()) {
            if (ClusterNaviController.OPEN_APP_BROADCAST.equals(broadcast.getAction())) {
                assertEquals("com.qinggan.cluster", broadcast.getPackage());
                targets.add(broadcast.getStringExtra("component"));
            }
        }
        return targets;
    }

    private static void awaitMain(BooleanSupplier finished) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!finished.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        assertTrue("Cluster command did not finish", finished.getAsBoolean());
    }

    @Test
    public void startStopsTheConfiguredPackageBeforeEnablingTheClusterCard() {
        for (VehiclePlatform platform :
                List.of(VehiclePlatform.H97C, VehiclePlatform.H97D, VehiclePlatform.OTHER)) {
            FakeBackend backend = new FakeBackend();
            backend.platform = platform;
            assertEquals("", backend.execute("cluster_navi:start"));
            assertEquals(
                    List.of(
                            "canStop:" + NAVI_PACKAGE,
                            "closeMedia:" + NAVI_PACKAGE,
                            "stop:" + NAVI_PACKAGE,
                            "broadcast:" + ClusterNaviController.STATE_ENABLED),
                    backend.calls);
        }
    }

    @Test
    public void stopStopsThePackageAndDisablesTheClusterCard() {
        FakeBackend backend = new FakeBackend();
        assertEquals("", backend.execute("cluster_navi:stop"));
        assertEquals(
                List.of(
                        "canStop:" + NAVI_PACKAGE,
                        "closeMedia:" + NAVI_PACKAGE,
                        "stop:" + NAVI_PACKAGE,
                        "broadcast:" + ClusterNaviController.STATE_DISABLED),
                backend.calls);
    }

    @Test
    public void mainScreenFreesTheClusterCardBeforeLaunchingTheApplication() {
        FakeBackend backend = new FakeBackend();
        assertEquals("", backend.execute("cluster_navi:main_screen"));
        assertEquals(
                List.of(
                        "canStop:" + NAVI_PACKAGE,
                        "closeMedia:" + NAVI_PACKAGE,
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
        assertEquals("", backend.execute("cluster_navi:stop"));
        assertTrue(
                backend.calls.toString(),
                backend.calls.contains("stop:" + ClusterNaviController.FALLBACK_PACKAGE));
    }

    @Test
    public void unstoppablePackageIsReportedAndNeverBroadcasts() {
        FakeBackend backend = new FakeBackend();
        backend.stoppable = false;
        assertFalse(backend.execute("cluster_navi:start").isEmpty());
        assertEquals(List.of("canStop:" + NAVI_PACKAGE), backend.calls);
    }

    /** Launching while the card is still owned by the cluster would move the app right back. */
    @Test
    public void unstoppablePackageIsNeverLaunchedOnItsOwnScreen() {
        FakeBackend backend = new FakeBackend();
        backend.stoppable = false;
        assertFalse(backend.execute("cluster_navi:main_screen").isEmpty());
        assertEquals(List.of("canStop:" + NAVI_PACKAGE), backend.calls);
    }

    @Test
    public void unknownActionHasNoSideEffects() {
        FakeBackend backend = new FakeBackend();
        assertEquals("Неизвестное действие", backend.execute("cluster_navi:toggle"));
        assertEquals(List.of(), backend.calls);
    }

    @Test
    public void stopFailureIsReportedInsteadOfAClaimedSuccess() {
        FakeBackend backend = new FakeBackend();
        backend.failure = new IllegalStateException("force-stop rejected");
        assertFalse(backend.execute("cluster_navi:start").isEmpty());
        assertEquals(
                List.of("canStop:" + NAVI_PACKAGE, "closeMedia:" + NAVI_PACKAGE), backend.calls);
    }
}
