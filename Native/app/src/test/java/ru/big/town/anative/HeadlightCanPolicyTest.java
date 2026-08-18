package ru.big.town.anative;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeadlightCanPolicyTest {
    @Test
    public void darkTargetMapsToLowBeam() {
        HeadlightCanPolicy.Command command = HeadlightCanPolicy.commandFor(true);

        assertEquals("LOW_BEAM", command.vehicleStateName);
        assertEquals(215, command.stableId);
        assertEquals(1, HeadlightCanPolicy.ACTIVATE);
    }

    @Test
    public void daylightTargetMapsToExplicitOff() {
        HeadlightCanPolicy.Command command = HeadlightCanPolicy.commandFor(false);

        assertEquals("OUT_LAMP_OFF", command.vehicleStateName);
        assertEquals(1096, command.stableId);
        assertEquals(1, HeadlightCanPolicy.ACTIVATE);
    }

    @Test
    public void autoPairLowTargetMapsToLowBeam() {
        HeadlightCanPolicy.Command command = HeadlightCanPolicy.commandForAutoPair(true);

        assertEquals("LOW_BEAM", command.vehicleStateName);
        assertEquals(215, command.stableId);
        assertEquals(1, HeadlightCanPolicy.ACTIVATE);
    }

    @Test
    public void autoPairAutoTargetMapsToOemAutoLampSwitch() {
        HeadlightCanPolicy.Command command = HeadlightCanPolicy.commandForAutoPair(false);

        assertEquals("AUTO_LAMP_SWITCH", command.vehicleStateName);
        assertEquals(1097, command.stableId);
        assertEquals(1, HeadlightCanPolicy.ACTIVATE);
    }
}
