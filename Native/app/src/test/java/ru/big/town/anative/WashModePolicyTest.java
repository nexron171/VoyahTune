package ru.big.town.anative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WashModePolicyTest {
    @Test
    public void onlyExactParkingParcelableIsAccepted() {
        assertTrue(WashModePolicy.isParking(0, 0));
        assertFalse(WashModePolicy.isParking(1, 1));
        assertFalse(WashModePolicy.isParking(2, 2));
        assertFalse(WashModePolicy.isParking(3, 3));
        assertFalse(WashModePolicy.isParking(5, -1));
        assertFalse(WashModePolicy.isParking(0, -1));
        assertFalse(WashModePolicy.isParking(5, 0));
    }
}
