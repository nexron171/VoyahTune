package ru.big.town.anative;

import static org.junit.Assert.*;

import org.junit.Test;

import ru.big.town.common.CarPlayClimateProtocol;

import java.util.ArrayList;
import java.util.List;

public class ClimateControlOperationTest {
    private static final class ClimateSession implements OemVehicleStateTransport.ClimateSession {
        long now;
        int mode;
        int pendingMode;
        long sentAt = -1;
        final List<Long> writes = new ArrayList<>();

        ClimateSession(int mode) {
            this.mode = mode;
        }

        public OemClimateState read() {
            if (sentAt >= 0 && now - sentAt >= 400) {
                mode = pendingMode;
                sentAt = -1;
            }
            // A transient OEM snapshot must not cause another pulse before settling.
            int observed = sentAt >= 0 ? 2 : mode;
            return ClimateCommandTest.state(1, 0, 3, observed);
        }

        public boolean send(ClimateCommand command) {
            assertEquals("AC_RECIRC_AIR", command.field);
            assertEquals(1, command.value);
            assertEquals(-1, sentAt);
            writes.add(now);
            pendingMode = mode == 0 ? 2 : mode == 2 ? 1 : 0;
            sentAt = now;
            return true;
        }

        int apply(String field, double target) {
            return ClimateControlOperation.apply(
                    this,
                    field,
                    target,
                    3000,
                    () -> true,
                    new OemClimateState[1],
                    new long[1],
                    () -> now,
                    milliseconds -> now += milliseconds);
        }
    }

    @Test
    public void manualOnSettlesBeforeReadingOrSendingAnotherPulse() {
        for (int initial = 0; initial <= 2; initial++) {
            ClimateSession session = new ClimateSession(initial);
            assertEquals(0, session.apply(CarPlayClimateProtocol.RECIRCULATION, 1));
            assertEquals(0, session.mode);
            assertEquals(initial == 0 ? 0 : initial == 1 ? 1 : 2, session.writes.size());
        }
    }

    @Test
    public void automaticAndExternalHaveDifferentConfirmedTargets() {
        for (int initial = 0; initial <= 2; initial++) {
            ClimateSession automatic = new ClimateSession(initial);
            assertEquals(0, automatic.apply(CarPlayClimateProtocol.RECIRCULATION_AUTO, 1));
            assertEquals(2, automatic.mode);
            ClimateSession external = new ClimateSession(initial);
            assertEquals(0, external.apply(CarPlayClimateProtocol.RECIRCULATION, 0));
            assertEquals(1, external.mode);
        }
    }

    @Test
    public void missingTransitionTimesOutWithoutBlindRepeats() {
        long[] now = {0};
        int[] sends = {0};
        OemVehicleStateTransport.ClimateSession session =
                new OemVehicleStateTransport.ClimateSession() {
                    public OemClimateState read() {
                        return ClimateCommandTest.state(1, 0, 3, 1);
                    }

                    public boolean send(ClimateCommand command) {
                        sends[0]++;
                        return true;
                    }
                };
        assertEquals(
                CarPlayClimateProtocol.TIMEOUT,
                ClimateControlOperation.apply(
                        session,
                        CarPlayClimateProtocol.RECIRCULATION,
                        1,
                        1500,
                        () -> true,
                        new OemClimateState[1],
                        new long[1],
                        () -> now[0],
                        milliseconds -> now[0] += milliseconds));
        assertEquals(1, sends[0]);
    }
}
