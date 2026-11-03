package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.CarPlayClimateProtocol;
import ru.big.town.common.RecirculationWidgetProtocol;

public class RecirculationWidgetOperationTest {
    private static final class Session implements OemVehicleStateTransport.ClimateSession {
        int mode;
        int pulses;
        boolean accepted = true;
        boolean feedback = true;
        long now;
        final OemClimateState[] latest = {null};

        Session(int mode) {
            this.mode = mode;
        }

        @Override
        public OemClimateState read() {
            return ClimateCommandTest.state(1, 0, 3, mode);
        }

        @Override
        public boolean send(ClimateCommand command) {
            assertEquals("AC_RECIRC_AIR", command.field);
            assertEquals(1, command.value);
            pulses++;
            if (accepted && feedback) mode = mode == 0 ? 2 : mode == 2 ? 1 : 0;
            return accepted;
        }

        int cycle() {
            return RecirculationWidgetOperation.cycle(
                    this,
                    now + 3000,
                    () -> true,
                    latest,
                    new long[1],
                    () -> now,
                    milliseconds -> now += milliseconds);
        }
    }

    @Test
    public void cyclesOnOffAutoUsingFreshOemReadback() {
        Session session = new Session(OemClimateState.CIRCULATION_INTERNAL);
        for (int target : new int[] {1, 2, 0}) {
            assertEquals(CarPlayClimateProtocol.SUCCESS, session.cycle());
            assertEquals(target, session.mode);
            assertEquals(target, session.latest[0].circulation);
        }
        assertEquals(6, session.pulses);
        session.mode = OemClimateState.CIRCULATION_EXTERNAL;
        assertEquals(CarPlayClimateProtocol.SUCCESS, session.cycle());
        assertEquals(OemClimateState.CIRCULATION_AUTOMATIC, session.mode);
    }

    @Test
    public void unknownStateNeverSendsAndMissingFeedbackIsNotSuccess() {
        Session unknown = new Session(-1);
        assertEquals(CarPlayClimateProtocol.UNAVAILABLE, unknown.cycle());
        assertEquals(0, unknown.pulses);
        Session noFeedback = new Session(0);
        noFeedback.feedback = false;
        assertEquals(CarPlayClimateProtocol.TIMEOUT, noFeedback.cycle());
        assertEquals(1, noFeedback.pulses);
        assertEquals(0, noFeedback.latest[0].circulation);
        Session rejected = new Session(2);
        rejected.accepted = false;
        assertEquals(CarPlayClimateProtocol.UNAVAILABLE, rejected.cycle());
        assertEquals(1, rejected.pulses);
    }

    @Test
    public void cancelledCommandDoesNotSendOrContinuePulses() {
        Session session = new Session(0);
        assertEquals(
                CarPlayClimateProtocol.TIMEOUT,
                RecirculationWidgetOperation.cycle(
                        session,
                        3000,
                        () -> false,
                        session.latest,
                        new long[1],
                        () -> session.now,
                        milliseconds -> session.now += milliseconds));
        assertEquals(0, session.pulses);
        assertEquals(
                CarPlayClimateProtocol.TIMEOUT,
                RecirculationWidgetOperation.cycle(
                        session,
                        3000,
                        () -> session.pulses == 0,
                        session.latest,
                        new long[1],
                        () -> session.now,
                        milliseconds -> session.now += milliseconds));
        assertEquals(1, session.pulses);
    }

    @Test
    public void externalObservationsStaySeparateFromPendingCommands() {
        RecirculationWidgetState state = new RecirculationWidgetState();
        assertFalse(state.begin());
        state.observe(RecirculationWidgetProtocol.ON);
        assertTrue(state.begin());
        assertFalse(state.begin());
        assertEquals(RecirculationWidgetProtocol.ON, state.mode);
        state.observe(RecirculationWidgetProtocol.AUTO);
        assertTrue(state.pending);
        assertFalse(state.available());
        state.complete(CarPlayClimateProtocol.SUCCESS);
        assertTrue(state.available());
        assertEquals(RecirculationWidgetProtocol.AUTO, state.mode);
        state.observe(RecirculationWidgetProtocol.OFF);
        assertEquals(RecirculationWidgetProtocol.OFF, state.mode);
        state.reset("Нет связи с автомобилем");
        assertFalse(state.available());
        assertEquals(RecirculationWidgetProtocol.UNKNOWN, state.mode);
    }

    @Test
    public void circulationAutoIsIndependentOfHvacAuto() {
        assertEquals(
                RecirculationWidgetProtocol.ON,
                RecirculationWidgetOperation.observedMode(ClimateCommandTest.state(1, 1, 3, 0)));
        assertEquals(
                RecirculationWidgetProtocol.OFF,
                RecirculationWidgetOperation.observedMode(ClimateCommandTest.state(1, 1, 3, 1)));
        assertEquals(
                RecirculationWidgetProtocol.AUTO,
                RecirculationWidgetOperation.observedMode(ClimateCommandTest.state(1, 0, 3, 2)));
        assertEquals(
                RecirculationWidgetProtocol.UNKNOWN,
                RecirculationWidgetOperation.observedMode(null));
    }
}
