package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DockLongPressActionTest {
    @Test public void upgradesExistingSplitAndEmptySlot() {
        assertEquals("split", DockLongPressAction.normalize(null, true));
        assertEquals("none", DockLongPressAction.normalize(null, false));
    }

    @Test public void explicitChoiceOverridesRememberedSplit() {
        assertEquals("cluster", DockLongPressAction.normalize("cluster", true));
        assertEquals("none", DockLongPressAction.normalize("none", true));
        assertEquals("split", DockLongPressAction.normalize("split", false));
    }

    @Test public void unknownActionDoesNotLaunchLegacySplit() {
        assertEquals("none", DockLongPressAction.normalize("invalid", true));
    }
}
