package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WiperSignalIngressTest {
    @Test
    public void doorDuplicatesAndInvalidValuesNeverEnterQueue() {
        WiperSignalIngress ingress = new WiperSignalIngress(8);

        assertFalse(ingress.offerDoor(-1));
        assertFalse(ingress.offerDoor(2));
        assertTrue(ingress.offerDoor(0));
        assertFalse(ingress.offerDoor(0));
        assertEquals(Integer.valueOf(0), ingress.pollDoor());
        assertFalse(ingress.finishDoorDrainSlice());
        assertNull(ingress.pollDoor());
    }

    @Test
    public void doorEdgesStayOrderedAndQueueIsBounded() {
        WiperSignalIngress ingress = new WiperSignalIngress(4);
        assertTrue(ingress.offerDoor(0));
        assertFalse(ingress.offerDoor(1));
        assertFalse(ingress.offerDoor(0));
        assertFalse(ingress.offerDoor(1));
        assertFalse(ingress.offerDoor(0));

        assertEquals(4, ingress.queuedDoorCount());
        assertEquals(1L, ingress.droppedDoorTransitions());
        assertEquals(Integer.valueOf(1), ingress.pollDoor());
        assertEquals(Integer.valueOf(0), ingress.pollDoor());
        assertEquals(Integer.valueOf(1), ingress.pollDoor());
        assertEquals(Integer.valueOf(0), ingress.pollDoor());
        assertFalse(ingress.finishDoorDrainSlice());
    }

    @Test
    public void gearUsesOneLatestValueSlot() {
        WiperSignalIngress ingress = new WiperSignalIngress(8);

        assertFalse(ingress.offerGear(-1));
        assertTrue(ingress.offerGear(0));
        assertFalse(ingress.offerGear(1));
        assertFalse(ingress.offerGear(3));
        assertEquals(3, ingress.takeLatestGear());
        assertEquals(WiperSignalIngress.UNKNOWN, ingress.takeLatestGear());
        assertFalse(ingress.offerGear(3));
        assertTrue(ingress.offerGear(0));
    }

    @Test
    public void seedUpdatesDedupeOnlyWithoutPendingDoorEdge() {
        WiperSignalIngress ingress = new WiperSignalIngress(8);
        ingress.seedDoor(1);
        assertFalse(ingress.offerDoor(1));
        assertTrue(ingress.offerDoor(0));
        ingress.seedDoor(1); // pending close must remain authoritative for ingress ordering
        assertFalse(ingress.offerDoor(0));
    }
}
