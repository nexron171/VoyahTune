package ru.big.town.anative;
import org.junit.Test;
import static org.junit.Assert.*;
public class SuspensionWidgetPolicyTest {
    @Test public void profilesMatchOemCard() {
        assertNull(SuspensionWidgetPolicy.driveMode(0, 1));
        assertEquals("SPORT", SuspensionWidgetPolicy.driveMode(1, 1));
        assertEquals("OUTING", SuspensionWidgetPolicy.driveMode(3, 1));
        assertEquals("SNOW", SuspensionWidgetPolicy.driveMode(2, 6));
        assertEquals("INDIVIDUAL", SuspensionWidgetPolicy.driveMode(2, 5));
        assertEquals("ECO", SuspensionWidgetPolicy.driveMode(2, 3));
    }
    @Test public void rejectUnknownAndUnsafeState() {
        assertNotNull(SuspensionWidgetPolicy.blocked(1, -1, 1, 0, 0, 3));
        assertNotNull(SuspensionWidgetPolicy.blocked(1, 5, 2, 0, 0, 3));
        assertNotNull(SuspensionWidgetPolicy.blocked(0, 5, 1, 1, 0, 3));
        assertNotNull(SuspensionWidgetPolicy.blocked(3, 5, 1, 0, null, 3));
        assertNotNull(SuspensionWidgetPolicy.blocked(3, 5, 1, 0, 40, 3));
        assertNull(SuspensionWidgetPolicy.blocked(3, 5, 1, 0, 39, 3));
    }
    @Test public void outingBlocksEntryEvenBeforeMaximumHeightAndWithoutOemInhibit() {
        for (int height : new int[]{5, 2, 1}) {
            assertEquals("Удобная посадка недоступна в Outing",
                    SuspensionWidgetPolicy.blocked(0, height, 1, 0, 0, 4));
        }
        assertNull(SuspensionWidgetPolicy.blocked(1, 1, 1, 0, 0, 4));
        assertNull(SuspensionWidgetPolicy.blocked(2, 1, 1, 0, 0, 4));
    }
    @Test public void entryUnlocksAfterLeavingOutingAndRequiresKnownMode() {
        assertNull(SuspensionWidgetPolicy.lowestBlockedReason(3, 0));
        assertNull(SuspensionWidgetPolicy.lowestBlockedReason(2, 0));
        assertNotNull(SuspensionWidgetPolicy.lowestBlockedReason(-1, 0));
        assertNotNull(SuspensionWidgetPolicy.lowestBlockedReason(3, -1));
        assertNotNull(SuspensionWidgetPolicy.lowestBlockedReason(3, 1));
        assertNull(SuspensionWidgetPolicy.blocked(0, 7, 1, 0, 0, 3));
    }
    @Test public void intermediateHeightIsNotCompletion() {
        assertFalse(SuspensionWidgetPolicy.reached(1, 6));
        assertFalse(SuspensionWidgetPolicy.reached(3, 3));
        assertTrue(SuspensionWidgetPolicy.reached(0, 9));
        assertTrue(SuspensionWidgetPolicy.reached(2, 5));
    }
}
