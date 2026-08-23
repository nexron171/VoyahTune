package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SplitHostPendingSlotsTest {
    @Test
    public void stormKeepsOneLatestValueAndOneDrain() {
        SplitHostPendingSlots<Integer> slots = new SplitHostPendingSlots<>(1);
        assertTrue(slots.offer(0, 0));
        assertEquals(Integer.valueOf(0), slots.take(0));

        for (int i = 1; i <= 100_000; i++) {
            assertFalse(slots.offer(0, i));
        }

        assertEquals(1, slots.pendingCount());
        assertTrue(slots.finishDrain());
        assertEquals(Integer.valueOf(100_000), slots.take(0));
        assertFalse(slots.finishDrain());
        assertTrue(slots.offer(0, 100_001));
    }

    @Test
    public void twoPaneSlotsCoalesceIndependently() {
        SplitHostPendingSlots<String> slots = new SplitHostPendingSlots<>(2);
        assertTrue(slots.offer(0, "left-old"));
        assertFalse(slots.offer(1, "right"));
        assertFalse(slots.offer(0, "left-new"));

        assertEquals(2, slots.pendingCount());
        assertEquals("left-new", slots.take(0));
        assertEquals("right", slots.take(1));
        assertFalse(slots.finishDrain());
        assertNull(slots.peek(0));
    }

    @Test
    public void clearingCancelledPendingWorkReleasesDrain() {
        SplitHostPendingSlots<String> slots = new SplitHostPendingSlots<>(1);
        assertTrue(slots.offer(0, "stale-host"));

        slots.clear(0);

        assertFalse(slots.finishDrain());
        assertTrue(slots.offer(0, "new-host"));
    }

    @Test
    public void rejectedHandlerPostDoesNotWedgeActiveFlag() {
        SplitHostPendingSlots<String> slots = new SplitHostPendingSlots<>(1);
        assertTrue(slots.offer(0, "first"));

        slots.rejectDrainPost();

        assertTrue(slots.offer(0, "latest"));
        assertEquals("latest", slots.take(0));
    }
}
