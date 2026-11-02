package ru.big.town.restoremode.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class PopupMenuPlacementTest {
    private final Rect available = new Rect(8, 32, 792, 472);

    @Test
    public void opensBelowWhenSpaceIsAvailable() {
        assertEquals(new Rect(100, 116, 420, 356),
                PopupMenuPlacement.fit(available, new Rect(100, 50, 164, 110), 320, 240, 6));
    }

    @Test
    public void opensAboveAndShiftsLeftAtBottomRightCorner() {
        assertEquals(new Rect(472, 154, 792, 394),
                PopupMenuPlacement.fit(available, new Rect(740, 400, 790, 460), 320, 240, 6));
    }

    @Test
    public void longMenuUsesVisibleHeightWithoutCrossingInsets() {
        assertEquals(available,
                PopupMenuPlacement.fit(available, new Rect(740, 400, 790, 460), 1000, 2000, 6));
    }

    @Test
    public void boundsHoldForAnchorsAcrossSmallAndLargeScreens() {
        for (Rect frame : new Rect[] {available, new Rect(25, 50, 225, 200)}) {
            for (int left = frame.left - 50; left <= frame.right + 50; left += 50) {
                for (int top = frame.top - 50; top <= frame.bottom + 50; top += 50) {
                    Rect result = PopupMenuPlacement.fit(
                            frame, new Rect(left, top, left + 64, top + 80), 320, 240, 6);
                    assertTrue(frame.contains(result));
                }
            }
        }
    }
}
