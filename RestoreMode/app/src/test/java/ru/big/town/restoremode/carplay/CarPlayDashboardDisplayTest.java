package ru.big.town.restoremode.carplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityOptions;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class, manifest = Config.NONE)
public class CarPlayDashboardDisplayTest {
    @After
    public void resetDisplay() {
        CarPlayDashboardLauncher.rememberDisplay(0);
        CarPlayDashboardLauncher.rememberHostDisplay(0, 0);
    }

    @Test
    public void carIconOpensDiPlayHomeOnThePhysicalHostDisplay() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        for (int physicalDisplay = 0; physicalDisplay <= 1; physicalDisplay++) {
            CarPlayDashboardLauncher.rememberHostDisplay(42, physicalDisplay);
            for (int sourceDisplay : new int[] {physicalDisplay, 42}) {
                assertTrue(CarPlayDashboardLauncher.open(context, sourceDisplay));
                org.robolectric.shadows.ShadowActivity.IntentForResult started =
                        shadowOf(RuntimeEnvironment.getApplication())
                                .getNextStartedActivityForResult();
                Intent intent = started.intent;
                assertEquals(
                        "com.shilapi.xcertplay.DiPlayActivity",
                        intent.getComponent().getClassName());
                assertEquals("home", intent.getStringExtra("page"));
                assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
                ActivityOptions options =
                        (ActivityOptions)
                                ActivityOptions.class
                                        .getDeclaredMethod("fromBundle", Bundle.class)
                                        .invoke(null, started.options);
                assertEquals(physicalDisplay, options.getLaunchDisplayId());
            }
        }
    }

    @Test
    public void fullscreenReturnsToItsPhysicalDisplay() {
        CarPlayDashboardLauncher.rememberDisplay(1);
        assertEquals(0, CarPlayDashboardLauncher.targetDisplayId(0));
        CarPlayDashboardLauncher.rememberDisplay(0);
        assertEquals(1, CarPlayDashboardLauncher.targetDisplayId(1));
    }

    @Test
    public void embeddedReturnsToDashboardWithoutLaunchingOnItsVirtualDisplay() {
        CarPlayDashboardLauncher.rememberDisplay(1);
        assertEquals(1, CarPlayDashboardLauncher.targetDisplayId(20));
        CarPlayDashboardLauncher.rememberDisplay(0);
        assertEquals(0, CarPlayDashboardLauncher.targetDisplayId(20));
    }

    @Test
    public void virtualWindowsCannotReplaceTheDashboardDisplay() {
        CarPlayDashboardLauncher.rememberDisplay(1);
        CarPlayDashboardLauncher.rememberDisplay(20);
        CarPlayDashboardLauncher.rememberDisplay(-1);
        assertEquals(1, CarPlayDashboardLauncher.targetDisplayId(21));
    }

    @Test
    public void fullscreenVirtualDisplayReturnsToItsPhysicalHost() {
        CarPlayDashboardLauncher.rememberDisplay(0);
        CarPlayDashboardLauncher.rememberHostDisplay(42, 1);

        assertEquals(1, CarPlayDashboardLauncher.targetDisplayId(42));
        assertEquals(0, CarPlayDashboardLauncher.targetDisplayId(43));
    }

    @Test
    public void fullscreenHostSurvivesDashboardOpeningOnAnotherDisplay() {
        CarPlayDashboardLauncher.rememberHostDisplay(42, 0);
        CarPlayDashboardLauncher.rememberDisplay(1);

        assertEquals(0, CarPlayDashboardLauncher.targetDisplayId(42));
        assertEquals(1, CarPlayDashboardLauncher.targetDisplayId(43));
    }

    @Test
    public void movingFullscreenHostOverridesLaunchIntentInBothDirections() {
        for (int source = 0; source <= 1; source++) {
            int destination = 1 - source;
            CarPlayDashboardLauncher.rememberHostDisplay(42, source);
            CarPlayDashboardLauncher.rememberHostDisplay(42, destination);
            CarPlayDashboardLauncher.rememberInitialHostDisplay(42, source);

            assertEquals(destination, CarPlayDashboardLauncher.targetDisplayId(42));
        }
    }

    @Test
    public void newProjectionCanInitializeItsOwnPhysicalDisplay() {
        CarPlayDashboardLauncher.rememberHostDisplay(42, 0);
        CarPlayDashboardLauncher.rememberInitialHostDisplay(43, 1);

        assertEquals(1, CarPlayDashboardLauncher.targetDisplayId(43));
    }

    @Test
    public void invalidPhysicalHostCannotRedirectDashboardOntoAVirtualDisplay() {
        CarPlayDashboardLauncher.rememberDisplay(1);
        CarPlayDashboardLauncher.rememberHostDisplay(42, 20);

        assertEquals(1, CarPlayDashboardLauncher.targetDisplayId(42));
    }
}
