package ru.big.town.restoremode.carplay;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class CarPlaySafeAreaTest {
    @Test
    public void physicalDisplaysReserveUnreportedDockAndStatusBar() {
        for (int display : new int[] {0, 1}) {
            assertArrayEquals(new int[] {145, 45, 0, 0},
                    CarPlaySafeArea.resolve(display, 0, 0, 145, 45, 0, 0, 0, 0));
        }
    }

    @Test
    public void alreadyConstrainedWindowsDoNotGetTheSameReservationTwice() {
        assertArrayEquals(new int[] {0, 0, 0, 0},
                CarPlaySafeArea.resolve(0, 145, 45, 145, 45, 0, 0, 0, 0));
        assertArrayEquals(new int[] {45, 25, 0, 0},
                CarPlaySafeArea.resolve(1, 100, 20, 145, 45, 0, 0, 0, 0));
        assertArrayEquals(new int[] {0, 0, 0, 0},
                CarPlaySafeArea.resolve(0, 960, 60, 145, 45, 0, 0, 0, 0));
    }

    @Test
    public void systemCutoutAndKeyboardInsetsArePreservedWithoutAddition() {
        assertArrayEquals(new int[] {180, 60, 12, 300},
                CarPlaySafeArea.resolve(0, 0, 0, 145, 45, 180, 60, 12, 300));
    }

    @Test
    public void embeddedDisplaysUseTheirWholeViewport() {
        assertArrayEquals(new int[] {0, 0, 0, 0},
                CarPlaySafeArea.resolve(7, 0, 0, 145, 45, 0, 0, 0, 0));
        assertArrayEquals(new int[] {0, 0, 0, 200},
                CarPlaySafeArea.resolve(7, 0, 0, 145, 45, 0, 0, 0, 200));
    }
}
