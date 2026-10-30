package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Map;

public class RearFogControllerTest {
    @Test
    public void explicitTargetsDoNotToggleAnAlreadyMatchingLamp() {
        for (int state : new int[] {0, 1}) {
            FakeSession session = new FakeSession(state);
            assertEquals(
                    "",
                    RearFogController.execute(
                            session, state == 1 ? "rear_fog:on" : "rear_fog:off"));
            assertEquals(0, session.writes);
        }
    }

    @Test
    public void oppositeTargetsAndToggleSendExactlyOneOemPulse() {
        for (String action : new String[] {"rear_fog:on", "rear_fog:off", "rear_fog:toggle"}) {
            FakeSession session = new FakeSession(action.endsWith(":off") ? 1 : 0);
            assertEquals("", RearFogController.execute(session, action));
            assertEquals(1, session.writes);
            assertEquals("REAR_FOG_LIGHT", session.sent.key.name);
            assertEquals(218, session.sent.key.stableId);
            assertEquals(1, session.sent.value);
        }
    }

    @Test
    public void unavailableOrInvalidStateNeverSendsAPulse() {
        for (Integer state : new Integer[] {null, -1, 2}) {
            FakeSession session = new FakeSession(state);
            assertFalse(RearFogController.execute(session, "rear_fog:on").isEmpty());
            assertEquals(0, session.writes);
        }
    }

    @Test
    public void transportFailureIsReportedWithoutRetry() {
        FakeSession session = new FakeSession(0);
        session.accepted = false;
        assertFalse(RearFogController.execute(session, "rear_fog:on").isEmpty());
        assertEquals(1, session.writes);
    }

    private static final class FakeSession implements OemVehicleStateTransport.Session {
        final Integer state;
        int writes;
        boolean accepted = true;
        OemVehicleStateTransport.StateValue sent;

        FakeSession(Integer state) {
            this.state = state;
        }

        public Integer readRearFog() {
            return state;
        }

        public OemVehicleStateTransport.GearStatus readGearStatus() {
            throw new AssertionError();
        }

        public Integer readVehicleState(OemVehicleStateTransport.StateKey key) {
            throw new AssertionError();
        }

        public OemVehicleStateTransport.Result sendBundle(
                Map<OemVehicleStateTransport.StateKey, Integer> values, String label) {
            throw new AssertionError();
        }

        public OemVehicleStateTransport.Result sendVehicleState(
                OemVehicleStateTransport.StateValue value, String label) {
            writes++;
            sent = value;
            return accepted
                    ? OemVehicleStateTransport.Result.ACCEPTED_UNCONFIRMED
                    : OemVehicleStateTransport.Result.TRANSIENT_FAILURE;
        }
    }
}
