package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Looper;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

import ru.big.town.common.CarPlayTarget;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CarPlayAppDisplayLaunchTest {
    private Context context;
    private CarPlayWindowedActivity host;
    private VirtualDisplay projection;
    private Object pane;
    private final AtomicInteger closed = new AtomicInteger();
    private final AtomicInteger started = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ActivityInfo launcher = new ActivityInfo();
        launcher.packageName = CarPlayTarget.PACKAGE;
        launcher.name = CarPlayTarget.LAUNCHER;
        launcher.enabled = true;
        shadowOf(context.getPackageManager()).addOrUpdateActivity(launcher);

        host = Robolectric.buildActivity(CarPlayWindowedActivity.class).get();
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        displays.createVirtualDisplay("passenger", 1920, 720, 213, null, 0);
        projection = displays.createVirtualDisplay("carplay", 1920, 720, 213, null, 0);
        assertTrue(projection.getDisplay().getDisplayId() > 1);
        pane = ReflectionHelpers.getField(host, "left");
        ReflectionHelpers.setField(pane, "pkg", CarPlayTarget.ID);
        ReflectionHelpers.setField(pane, "vd", projection);
        ReflectionHelpers.setField(host, "singleCarPlay", true);
        ReflectionHelpers.setStaticField(
                SplitHostActivity.class, "activeHost", new WeakReference<>(host));
        EmbeddedAppWindows.register(
                context, pane, CarPlayTarget.ID, projection.getDisplay().getDisplayId(),
                completion -> closed.incrementAndGet());
    }

    @After
    public void tearDown() {
        EmbeddedAppWindows.remove(pane);
        ReflectionHelpers.setStaticField(
                SplitHostActivity.class, "activeHost", new WeakReference<>(null));
        projection.release();
    }

    @Test
    public void launcherAndShortcutPreserveProjectionAcrossDockReturns() {
        int projectionId = projection.getDisplay().getDisplayId();
        for (int iteration = 0; iteration < 3; iteration++) {
            SetModesReceiverDynamic.openFreeformApp(context, CarPlayTarget.ID, 0);
            launch(0, () -> true);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, closed.get());
            assertEquals(iteration + 1, started.get());
            assertTrue(SplitHostActivity.ownsCarPlayDisplay(projectionId));
            assertTrue(projection.getDisplay().isValid());
        }
        assertEquals(0, failed.get());
    }

    @Test
    public void differentPhysicalDisplayStillClosesOldHostBeforeLaunching() {
        launch(1, () -> true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, closed.get());
        assertEquals(0, started.get());
    }

    @Test
    public void changedDpiStillRequiresNewHost() {
        ReflectionHelpers.setField(pane, "dpi", 240);
        launch(0, () -> true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, closed.get());
        assertEquals(0, started.get());
    }

    @Test
    public void changedWindowModeStillRequiresNewHost() {
        Settings.Global.putInt(context.getContentResolver(), CarPlayTarget.FULLSCREEN_SETTING, 1);
        launch(0, () -> true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, closed.get());
        assertEquals(0, started.get());
    }

    @Test
    public void onlyLatestQueuedRequestResumesHost() {
        launch(1, () -> true);
        launch(0, () -> true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, closed.get());
        assertEquals(1, started.get());
    }

    @Test
    public void cancelledAndInvalidRequestsLeaveHostUntouched() {
        launch(0, () -> true);
        AppDisplayLauncher.cancel(CarPlayTarget.ID);
        launch(0, () -> false);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, closed.get());
        assertEquals(0, started.get());
    }

    @Test
    public void disabledCarPlayDoesNotResumeOrCloseHost() {
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(CarPlayTarget.PACKAGE, CarPlayTarget.LAUNCHER),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        launch(0, () -> true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, failed.get());
        assertEquals(0, closed.get());
        assertEquals(0, started.get());
    }

    private void launch(int displayId, java.util.function.BooleanSupplier valid) {
        AppDisplayLauncher.launch(
                context, CarPlayTarget.ID, displayId, true, valid,
                failed::incrementAndGet, started::incrementAndGet);
    }
}
