package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarPlayNavigationPolicyTest {
    private static final String HOST = "ru.big.town.anative.CarPlayFullscreenActivity";

    @Test
    public void eitherHiddenPanelRequiresNavigationOnEachPhysicalDisplay() {
        for (int displayId : new int[] {0, 1}) {
            assertTrue(CarPlayNavigationPolicy.shouldForceNavigation(displayId, HOST, 1, 0));
            assertTrue(CarPlayNavigationPolicy.shouldForceNavigation(displayId, HOST, 0, 1));
            assertTrue(CarPlayNavigationPolicy.shouldForceNavigation(displayId, HOST, 1, 1));
            assertFalse(CarPlayNavigationPolicy.shouldForceNavigation(displayId, HOST, 0, 0));
        }
    }

    @Test
    public void missingHookReportsStillLeaveFullscreenCarPlayWithNavigation() {
        assertTrue(CarPlayNavigationPolicy.shouldForceNavigation(0, HOST, -1, -1));
        assertTrue(CarPlayNavigationPolicy.shouldForceNavigation(1, HOST, 0, -1));
        assertTrue(CarPlayNavigationPolicy.shouldForceNavigation(1, HOST, -1, 0));
    }

    @Test
    public void staleHiddenReportsCannotForceButtonsOverAnotherActivity() {
        for (String activity :
                new String[] {
                    "", "ru.big.town.anative.CarPlayWindowedActivity",
                    "ru.big.town.anative.SplitHostActivity",
                            "com.shilapi.xcertplay.CarPlayHostActivity"
                }) {
            assertFalse(CarPlayNavigationPolicy.shouldForceNavigation(0, activity, 1, 1));
        }
    }

    @Test
    public void piAndVirtualDisplaysDoNotReceiveTheOdOverride() {
        assertFalse(CarPlayNavigationPolicy.shouldForceNavigation(7, HOST, 1, 1));
    }
}
