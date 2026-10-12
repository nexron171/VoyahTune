package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    @Test
    public void explicitActionsKeepAutoParkingAndLowBeamDistinct() {
        assertEquals(
                HeadlightCanPolicy.Command.LOW_BEAM,
                HeadlightCanPolicy.commandForAction("headlights:on"));
        assertEquals(
                HeadlightCanPolicy.Command.OUT_LAMP_OFF,
                HeadlightCanPolicy.commandForAction("headlights:off"));
        assertEquals(
                HeadlightCanPolicy.Command.AUTO_LAMP_SWITCH,
                HeadlightCanPolicy.commandForAction("headlights:auto"));

        HeadlightCanPolicy.Command parking =
                HeadlightCanPolicy.commandForAction("headlights:parking");
        assertEquals("POSITION_LAMP_SWITCH", parking.vehicleStateName);
        assertEquals(1095, parking.stableId);
    }

    @Test
    public void unsupportedActionsDoNotDefaultToLowBeam() {
        assertNull(HeadlightCanPolicy.commandForAction("headlights:unknown"));
        assertNull(HeadlightCanPolicy.commandForAction(""));
        assertNull(HeadlightCanPolicy.commandForAction(null));
    }
}
