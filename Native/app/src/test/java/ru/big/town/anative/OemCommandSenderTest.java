package ru.big.town.anative;

import org.junit.Test;
import java.util.concurrent.TimeoutException;
import static org.junit.Assert.*;

public class OemCommandSenderTest {
    private static class Fake implements OemCommandSender.Transport {
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
        assertNull(OemCommandSender.send("seat:passenger:massage:rollers", 7000, f));
        assertEquals(1, f.prepares); assertEquals(1, f.sends);
        assertEquals("FRONT_SEAT_MASS_COMMAND_RIGHT", f.field); assertEquals(2, f.value);
    }

    @Test public void failedOrUncertainSendIsNeverRetried() {
        for (int code : new int[]{-1, 1, 2}) {
            Fake f = new Fake(); f.result = code;
            assertNotNull(OemCommandSender.send("wheel_heat:on", 7000, f));
            assertEquals(1, f.sends);
        }
        Fake f = new Fake(); f.sendFailure = new Exception("Lost reply after write");
        assertNotNull(OemCommandSender.send("wheel_heat:off", 7000, f));
        assertEquals(1, f.sends);
    }

    @Test public void missingPermissionApiFieldOrConnectionPreventsWrite() {
        for (Exception error : new Exception[]{new SecurityException(), new UnsupportedOperationException(),
                new TimeoutException(), new Exception("Disconnected")}) {
            Fake f = new Fake(); f.preparationFailure = error;
            assertNotNull(OemCommandSender.send("seat:driver:vent:3", 7000, f));
            assertEquals(0, f.sends);
        }
    }

    @Test public void invalidActionDoesNotEvenInitializeTransport() {
        Fake f = new Fake();
        assertNotNull(OemCommandSender.send("seat:passenger:heat:99", 7000, f));
        assertEquals(0, f.prepares); assertEquals(0, f.sends);
    }

    @Test public void acceptedCommandSurvivesSessionCloseButItsResultDoesNotReachNextSession() {
        for (String action : new String[]{"seat:passenger:heat:on", "windows:left:close", "sunroof:vent"}) {
            VoiceSessionGate gate = new VoiceSessionGate();
            gate.begin("first"); assertTrue(gate.submit("first"));
            Fake f = new Fake();
            Runnable acceptedWork = () -> assertNull(OemCommandSender.send(action, 7000, f));
            assertFalse(gate.submit("first"));
            gate.cancel("first"); gate.begin("second");
            acceptedWork.run();
            assertEquals(1, f.sends); assertFalse(gate.active("first")); assertTrue(gate.active("second"));
        }
    }

    @Test public void windowsGroupsRoofAndShadeEachSendOnlyOneField() {
        String[] targets = {"", "driver:", "passenger:", "rear_left:", "rear_right:", "front:", "rear:", "left:", "right:"};
        for (String target : targets) for (String operation : new String[]{"open", "close"})
            sendsWindow("windows:" + target + operation);
        for (String action : new String[]{"windows:vent", "sunroof:open", "sunroof:close", "sunroof:vent",
                "sunshade:open", "sunshade:close"}) sendsWindow(action);
    }

    private void sendsWindow(String action) {
        Fake f = new Fake();
        assertNull(action, OemCommandSender.send(action, 7000, f));
        WindowCommand expected = WindowCommand.parse(action);
        assertEquals(1, f.prepares); assertEquals(1, f.sends);
        assertEquals(expected.field, f.field); assertEquals(expected.value, f.value);
    }

    @Test public void missingWindowOrGroupSupportNeverFallsBackToAllWindows() {
        for (String action : new String[]{"windows:driver:open", "windows:rear:close", "sunroof:vent", "sunshade:close"}) {
            for (Exception e : new Exception[]{new SecurityException(), new UnsupportedOperationException(),
                    new TimeoutException(), new Exception("Disconnected")}) {
                Fake f = new Fake(); f.preparationFailure = e;
                assertNotNull(OemCommandSender.send(action, 7000, f));
                assertEquals(1, f.prepares); assertEquals(0, f.sends);
            }
            Fake refused = new Fake(); refused.result = -1;
            assertNotNull(OemCommandSender.send(action, 7000, refused));
            assertEquals(1, refused.sends);
            Fake uncertain = new Fake(); uncertain.sendFailure = new Exception("Lost reply after write");
            assertNotNull(OemCommandSender.send(action, 7000, uncertain));
            assertEquals(1, uncertain.sends);
        }
    }

    @Test public void malformedWindowIpcDoesNotTouchTransport() {
        for (String action : new String[]{"windows:driver:vent", "sunshade:vent", "windows:unknown:open", "windows:stop"}) {
            Fake f = new Fake();
            assertNotNull(OemCommandSender.send(action, 7000, f));
            assertEquals(0, f.prepares); assertEquals(0, f.sends);
        }
    }
}
