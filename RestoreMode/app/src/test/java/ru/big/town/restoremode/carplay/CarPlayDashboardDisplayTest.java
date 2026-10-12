package ru.big.town.restoremode.carplay;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class CarPlayDashboardDisplayTest {
    @After
    public void resetDisplay() {
        CarPlayDashboardLauncher.rememberDisplay(0);
        CarPlayDashboardLauncher.rememberHostDisplay(0, 0);
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
    public void invalidPhysicalHostCannotRedirectDashboardOntoAVirtualDisplay() {
        CarPlayDashboardLauncher.rememberDisplay(1);
        CarPlayDashboardLauncher.rememberHostDisplay(42, 20);

        assertEquals(1, CarPlayDashboardLauncher.targetDisplayId(42));
    }
}
