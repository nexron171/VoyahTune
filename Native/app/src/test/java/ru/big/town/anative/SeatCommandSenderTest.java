package ru.big.town.anative;

import org.junit.Test;
import java.util.concurrent.TimeoutException;
import static org.junit.Assert.*;

public class SeatCommandSenderTest {
    private static class Fake implements SeatCommandSender.Transport {
        int prepares, sends, result;
        String field; int value;
        Exception preparationFailure, sendFailure;
        @Override public void prepare(String field, long deadline) throws Exception {
            prepares++; assertEquals(7000, deadline);
            if (preparationFailure != null) throw preparationFailure;
        }
        @Override public int send(String field, int value) throws Exception {
            sends++; this.field = field; this.value = value;
            if (sendFailure != null) throw sendFailure;
            return result;
        }
    }

    @Test public void reportsSuccessImmediatelyWithoutFeedbackOrCompensatingWrites() {
        Fake f = new Fake();
        assertNull(SeatCommandSender.send("seat:passenger:massage:rollers", 7000, f));
        assertEquals(1, f.prepares); assertEquals(1, f.sends);
        assertEquals("FRONT_SEAT_MASS_COMMAND_RIGHT", f.field); assertEquals(2, f.value);
    }

    @Test public void failedOrUncertainSendIsNeverRetried() {
        for (int code : new int[]{-1, 1, 2}) {
            Fake f = new Fake(); f.result = code;
            assertNotNull(SeatCommandSender.send("wheel_heat:on", 7000, f));
            assertEquals(1, f.sends);
        }
        Fake f = new Fake(); f.sendFailure = new Exception("Lost reply after write");
        assertNotNull(SeatCommandSender.send("wheel_heat:off", 7000, f));
        assertEquals(1, f.sends);
    }

    @Test public void missingPermissionApiFieldOrConnectionPreventsWrite() {
        for (Exception error : new Exception[]{new SecurityException(), new UnsupportedOperationException(),
                new TimeoutException(), new Exception("Disconnected")}) {
            Fake f = new Fake(); f.preparationFailure = error;
            assertNotNull(SeatCommandSender.send("seat:driver:vent:3", 7000, f));
            assertEquals(0, f.sends);
        }
    }

    @Test public void invalidActionDoesNotEvenInitializeTransport() {
        Fake f = new Fake();
        assertNotNull(SeatCommandSender.send("seat:passenger:heat:99", 7000, f));
        assertEquals(0, f.prepares); assertEquals(0, f.sends);
    }

    @Test public void acceptedCommandSurvivesSessionCloseButItsResultDoesNotReachNextSession() {
        VoiceSessionGate gate = new VoiceSessionGate();
        gate.begin("first"); assertTrue(gate.submit("first"));
        Fake f = new Fake();
        Runnable acceptedWork = () -> assertNull(SeatCommandSender.send("seat:passenger:heat:on", 7000, f));
        assertFalse(gate.submit("first"));
        gate.cancel("first"); gate.begin("second");
        acceptedWork.run();
        assertEquals(1, f.sends); assertFalse(gate.active("first")); assertTrue(gate.active("second"));
    }
}
