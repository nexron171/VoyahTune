package ru.big.town.restoremode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SteeringCanCommandPolicyTest {
    @Test
    public void formatsLikeCustomCommandProfile() {
        assertEquals("64 08 80 00 00 00 00 00 00 03",
                SteeringCanCommandPolicy.format("64,08-80 00 00 00 00 00 00 03"));
        assertTrue(SteeringCanCommandPolicy.isValid("64 08 80 00 00 00 00 00 00 03"));
        assertEquals("can:64088000000000000003",
                SteeringCanCommandPolicy.actionId("64 08 80 00 00 00 00 00 00 03"));
    }

    @Test
    public void rejectsIncompleteOrMultipleFrames() {
        assertFalse(SteeringCanCommandPolicy.isValid("64 08"));
        assertFalse(SteeringCanCommandPolicy.isValid(
                "64 08 80 00 00 00 00 00 00 03 65 08 00 00 c1 c0 10 00 00 00"));
    }
}
