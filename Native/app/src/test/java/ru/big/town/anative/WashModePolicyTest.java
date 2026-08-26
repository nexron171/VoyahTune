package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WashModePolicyTest {
    @Test
    public void stableContractMatchesAndroid11H97c() {
        assertEquals("CAR_CLEANING_MODE_SWITCH", WashModePolicy.CLEANING_MODE);
        assertEquals(1133, WashModePolicy.CLEANING_MODE_ID);
        assertEquals(1, WashModePolicy.CLEANING_ON);
        assertEquals(0, WashModePolicy.CLEANING_OFF);
    }

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

    @Test
    public void outcomesRemainInternalAndMinimal() {
        assertEquals(3, WashModePolicy.Outcome.values().length);
        assertEquals(WashModePolicy.Outcome.ACCEPTED,
                WashModePolicy.Outcome.valueOf("ACCEPTED"));
        assertEquals(WashModePolicy.Outcome.NOT_IN_PARK,
                WashModePolicy.Outcome.valueOf("NOT_IN_PARK"));
        assertEquals(WashModePolicy.Outcome.TRANSPORT_FAILURE,
                WashModePolicy.Outcome.valueOf("TRANSPORT_FAILURE"));
    }
}
