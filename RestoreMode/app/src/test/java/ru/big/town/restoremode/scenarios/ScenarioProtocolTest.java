package ru.big.town.restoremode.scenarios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.ScenarioProtocol;

public class ScenarioProtocolTest {

    @Test
    public void pauseSecondsAreClamped() {
        assertEquals(ScenarioProtocol.MIN_PAUSE_SECONDS, ScenarioProtocol.clampPauseSeconds(0));
        assertEquals(ScenarioProtocol.MIN_PAUSE_SECONDS, ScenarioProtocol.clampPauseSeconds(-5));
        assertEquals(30, ScenarioProtocol.clampPauseSeconds(30));
        assertEquals(ScenarioProtocol.MAX_PAUSE_SECONDS, ScenarioProtocol.clampPauseSeconds(9999));
    }

    @Test
    public void gearValueMatchesCanBusEnum() {
        assertEquals(Integer.valueOf(0), ScenarioProtocol.gearValue(ScenarioProtocol.GEAR_P));
        assertEquals(Integer.valueOf(1), ScenarioProtocol.gearValue(ScenarioProtocol.GEAR_R));
        assertEquals(Integer.valueOf(2), ScenarioProtocol.gearValue(ScenarioProtocol.GEAR_N));
        assertEquals(Integer.valueOf(3), ScenarioProtocol.gearValue(ScenarioProtocol.GEAR_D));
        assertNull(ScenarioProtocol.gearValue("S"));
    }

    @Test
    public void compareFollowsOperator() {
        assertTrue(ScenarioProtocol.compare(3, ScenarioProtocol.OP_LE, 3));
        assertFalse(ScenarioProtocol.compare(4, ScenarioProtocol.OP_LE, 3));
        assertTrue(ScenarioProtocol.compare(20, ScenarioProtocol.OP_GE, 15));
        assertFalse(ScenarioProtocol.compare(10, ScenarioProtocol.OP_GE, 15));
        assertTrue(ScenarioProtocol.compare(3, ScenarioProtocol.OP_EQ, 3));
        assertFalse(ScenarioProtocol.compare(3, ScenarioProtocol.OP_EQ, 4));
        assertFalse("неизвестный оператор не проходит", ScenarioProtocol.compare(3, "gt", 2));
    }

    @Test
    public void parseClockAcceptsValidTimeOnly() {
        assertEquals(Integer.valueOf(0), ScenarioProtocol.parseClock("00:00"));
        assertEquals(Integer.valueOf(450), ScenarioProtocol.parseClock("07:30"));
        assertEquals(Integer.valueOf(1439), ScenarioProtocol.parseClock("23:59"));
        assertNull(ScenarioProtocol.parseClock("24:00"));
        assertNull(ScenarioProtocol.parseClock("12:60"));
        assertNull(ScenarioProtocol.parseClock("7"));
        assertNull(ScenarioProtocol.parseClock(null));
    }

    @Test
    public void enumValidatorsRejectUnknownValues() {
        assertTrue(ScenarioProtocol.validGear("D"));
        assertFalse(ScenarioProtocol.validGear("S"));
        assertTrue(ScenarioProtocol.validDoor(ScenarioProtocol.DOOR_BOOT));
        assertFalse(ScenarioProtocol.validDoor("sunroof"));
        assertTrue(ScenarioProtocol.validLightTrigger(ScenarioProtocol.LIGHT_DARK));
        assertFalse(ScenarioProtocol.validLightTrigger("dim"));
        assertTrue(ScenarioProtocol.validEdge(ScenarioProtocol.EDGE_OPEN));
        assertFalse(ScenarioProtocol.validEdge("toggle"));
        assertTrue(ScenarioProtocol.validDriveMode("INDIVIDUAL"));
        assertFalse(ScenarioProtocol.validDriveMode("RACE"));
    }
}
