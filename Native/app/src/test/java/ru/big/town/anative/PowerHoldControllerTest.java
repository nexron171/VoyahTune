package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PowerHoldControllerTest {
    @Test
    public void activationUsesExactGearSocBundleOrder() {
        FakeSession session = new FakeSession();
        PowerHoldController controller = controllerFor(session);

        assertEquals(PowerHoldPolicy.Outcome.ACCEPTED, controller.activate());
        assertEquals(java.util.Arrays.asList("gear", "soc", "bundle"), session.calls);
        assertEquals(PowerHoldPolicy.activationValues(), session.sentValues);
        assertEquals("power hold activate", session.label);
    }

    @Test
    public void nonParkingStopsBeforeSocAndBundle() {
        FakeSession session = new FakeSession();
        session.gear = new PowerHoldController.Gear(3, 3);

        assertEquals(PowerHoldPolicy.Outcome.NOT_IN_PARK,
                controllerFor(session).activate());
        assertEquals(java.util.Collections.singletonList("gear"), session.calls);
    }

    @Test
    public void missingAndLowSocStopBeforeBundle() {
        FakeSession missing = new FakeSession();
        missing.soc = null;
        assertEquals(PowerHoldPolicy.Outcome.STATE_UNAVAILABLE,
                controllerFor(missing).activate());
        assertFalse(missing.calls.contains("bundle"));

        FakeSession low = new FakeSession();
        low.soc = 14;
        assertEquals(PowerHoldPolicy.Outcome.LOW_BATTERY,
                controllerFor(low).activate());
        assertFalse(low.calls.contains("bundle"));
    }

    @Test
    public void transportFailuresAreFailClosed() {
        FakeSession rejected = new FakeSession();
        rejected.sendAccepted = false;
        assertEquals(PowerHoldPolicy.Outcome.TRANSPORT_FAILURE,
                controllerFor(rejected).activate());

        PowerHoldController unavailable = new PowerHoldController(action -> null);
        assertEquals(PowerHoldPolicy.Outcome.TRANSPORT_FAILURE, unavailable.activate());

        PowerHoldController throwing = new PowerHoldController(action -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(PowerHoldPolicy.Outcome.TRANSPORT_FAILURE, throwing.activate());
    }

    @Test
    public void acceptedRequestHasNoRetryOrCleanup() {
        FakeSession session = new FakeSession();
        assertEquals(PowerHoldPolicy.Outcome.ACCEPTED,
                controllerFor(session).activate());
        assertEquals(1, session.bundleCalls);
        assertTrue(session.sentValues.containsKey("POWER_HOLD_MODE_SWITCH"));
        assertFalse(session.sentValues.containsValue(0));
    }

    private static PowerHoldController controllerFor(FakeSession session) {
        return new PowerHoldController(action -> action.run(session));
    }

    private static final class FakeSession implements PowerHoldController.Session {
        final List<String> calls = new ArrayList<>();
        PowerHoldController.Gear gear = new PowerHoldController.Gear(0, 0);
        Integer soc = 80;
        boolean sendAccepted = true;
        int bundleCalls;
        Map<String, Integer> sentValues;
        String label;

        @Override
        public PowerHoldController.Gear readGear() {
            calls.add("gear");
            return gear;
        }

        @Override
        public Integer readSoc() {
            calls.add("soc");
            return soc;
        }

        @Override
        public boolean sendActivation(Map<String, Integer> values, String label) {
            calls.add("bundle");
            bundleCalls++;
            sentValues = values;
            this.label = label;
            return sendAccepted;
        }
    }
}
