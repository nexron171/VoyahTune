package ru.big.town.anative;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SteeringActionPolicyTest {
    @Test
    public void pairAdvancesRelativeToCurrentValueAndWraps() {
        assertEquals("HIGH", SteeringActionPolicy.nextMode("LOW,HIGH", "LOW"));
        assertEquals("LOW", SteeringActionPolicy.nextMode("LOW,HIGH", "HIGH"));
    }

    @Test
    public void unknownCurrentStartsWithFirstConfiguredValue() {
        assertEquals("MEDIUM", SteeringActionPolicy.nextMode("MEDIUM,HIGH", "LOW"));
    }

    @Test
    public void singleValueAlwaysSelectsThatValue() {
        assertEquals("HIGH", SteeringActionPolicy.nextMode("HIGH", "LOW"));
        assertEquals("HIGH", SteeringActionPolicy.nextMode("HIGH", "HIGH"));
    }

    @Test
    public void emptyConfigurationIsIgnored() {
        assertNull(SteeringActionPolicy.nextMode(" , ", "LOW"));
    }
}
