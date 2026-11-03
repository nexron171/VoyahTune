package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class SteeringComfortControllerTest {
    private static final String[][] CONTROLS = {
        {"wheel_heat", "STEER_WHEEL_HEAT_SWITCH", "1098"},
        {"seat:driver:heat", "FRONT_SEAT_HEATING_SWITCH_LEFT", "698"},
        {"seat:driver:vent", "FRONT_SEAT_VENTILATION_SWITCH_LEFT", "700"},
        {"seat:driver:massage", "FRONT_SEAT_MASS_SWITCH_LEFT", "702"}
    };

    @Test
    public void explicitOnAndOffWriteOnlyTheRequestedSwitchWithoutReadingState() {
        for (String[] control : CONTROLS) {
            for (String operation : new String[] {"on", "off"}) {
                FakeSession session = new FakeSession(null);
                String action = control[0] + ":" + operation;
                assertTrue(action, SteeringComfortController.handles(action));
                assertEquals("", SteeringComfortController.execute(session, action));
                assertEquals(0, session.reads);
                assertCommand(session, control, operation.equals("on") ? 2 : 1);
            }
        }
    }

    @Test
    public void toggleUsesCurrentVehicleStateForEachPress() {
        for (String[] control : CONTROLS) {
            FakeSession session = new FakeSession(1);
            String action = control[0] + ":toggle";
            assertTrue(action, SteeringComfortController.handles(action));
            for (int current : new int[] {1, 2, 2, 1}) {
                session.state = current;
                session.writes = 0;
                assertEquals("", SteeringComfortController.execute(session, action));
                assertCommand(session, control, current == 1 ? 2 : 1);
                assertEquals(session.sent.key, session.readKey);
            }
            assertEquals(4, session.reads);
        }
    }

    @Test
    public void toggleDoesNotWriteWhenSwitchStateIsUnknownOrFaulted() {
        for (String[] control : CONTROLS) {
            for (Integer state : new Integer[] {null, -1, 0, 3, 10}) {
                FakeSession session = new FakeSession(state);
                assertFalse(
                        SteeringComfortController.execute(session, control[0] + ":toggle").isEmpty());
                assertEquals(1, session.reads);
                assertEquals(0, session.writes);
            }
        }
    }

    @Test
    public void transportFailureIsReportedWithoutRetryingAnyOperation() {
        for (String[] control : CONTROLS) {
            for (String operation : new String[] {"on", "off", "toggle"}) {
                FakeSession session = new FakeSession(1);
                session.accepted = false;
                assertFalse(
                        SteeringComfortController.execute(session, control[0] + ":" + operation)
                                .isEmpty());
                assertEquals(1, session.writes);
            }
        }
    }

    @Test
    public void passengerAndMalformedActionsNeverTouchTheTransport() {
        for (String action : new String[] {
            null, "", "wheel_heat", "wheel_heat:3", "wheel_heat:toggle:extra",
            "seat:passenger:heat:on", "seat:passenger:vent:off", "seat:passenger:massage:toggle",
            "seat:driver:heat:unknown", "seat:driver:massage:on:", "seat:rear:heat:toggle"
        }) {
            FakeSession session = new FakeSession(1);
            assertFalse(SteeringComfortController.handles(action));
            assertFalse(SteeringComfortController.execute(session, action).isEmpty());
            assertEquals(0, session.reads);
            assertEquals(0, session.writes);
        }
    }

    private static void assertCommand(FakeSession session, String[] control, int target) {
        assertEquals(1, session.writes);
        assertEquals(control[1], session.sent.key.name);
        assertEquals(Integer.parseInt(control[2]), session.sent.key.stableId);
        assertEquals(target, session.sent.value);
    }

    private static final class FakeSession implements OemVehicleStateTransport.Session {
        Integer state;
        int reads;
        int writes;
        boolean accepted = true;
        OemVehicleStateTransport.StateKey readKey;
        OemVehicleStateTransport.StateValue sent;

        FakeSession(Integer state) {
            this.state = state;
        }

        @Override
        public Integer readVehicleState(OemVehicleStateTransport.StateKey key) {
            reads++;
            readKey = key;
            return state;
        }

        @Override
        public OemVehicleStateTransport.Result sendVehicleState(
                OemVehicleStateTransport.StateValue value, String label) {
            writes++;
            sent = value;
            return accepted
                    ? OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED
                    : OemVehicleStateTransport.Result.TRANSIENT_FAILURE;
        }

        @Override
        public OemVehicleStateTransport.GearStatus readGearStatus() {
            throw new AssertionError();
        }

        @Override
        public OemVehicleStateTransport.Result sendBundle(
                Map<OemVehicleStateTransport.StateKey, Integer> values, String label) {
            throw new AssertionError();
        }
    }
}
