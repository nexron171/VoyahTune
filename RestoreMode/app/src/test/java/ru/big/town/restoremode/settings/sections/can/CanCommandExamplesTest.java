package ru.big.town.restoremode.settings.sections.can;

import static org.junit.Assert.*;
import static ru.big.town.restoremode.voice.commands.VoiceCommandGroups.Group.HEATING;

import org.junit.Test;
import ru.big.town.restoremode.vehicle.steering.SteeringCanCommandPolicy;
import ru.big.town.restoremode.voice.commands.VoiceCommandGroups;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * The OEM CanBusService packs several one-shot fields into one command frame
 * ({@code DongfengH97CCanBusComponentImpl.setIVI_CONTROL}), so an example frame must carry only the
 * field its name promises. Fields are numbered Intel/LSB-first inside the eight data bytes.
 */
public class CanCommandExamplesTest {
    private static final int IVI_CONTROL_MESSAGE = 0x65;

    /** {@code AVN_KEY_REARDEF_SWITCH}: 2 presses on, 1 presses off. */
    private static final int REAR_DEFROG_KEY_BIT = 36;

    /** {@code BATTERY_TEP_CONTROL_SWITCH}: 1 enables battery preconditioning, 0 asks nothing. */
    private static final int BATTERY_PRECONDITION_BIT = 16;

    /** Markers the OEM service writes as 3 into every IVI_CONTROL command. */
    private static final int FIRST_CONTROL_MARKER_BIT = 22;

    private static final int SECOND_CONTROL_MARKER_BIT = 30;

    @Test
    public void rearGlassHeatingLeavesBatteryPreconditioningAlone() {
        byte[] on = iviControlData("обогрев заднего стекла вкл");
        byte[] off = iviControlData("обогрев заднего стекла выкл");

        assertEquals(2, twoBitField(on, REAR_DEFROG_KEY_BIT));
        assertEquals(1, twoBitField(off, REAR_DEFROG_KEY_BIT));
        for (byte[] frame : new byte[][] {on, off}) {
            assertEquals(0, twoBitField(frame, BATTERY_PRECONDITION_BIT));
            assertEquals(3, twoBitField(frame, FIRST_CONTROL_MARKER_BIT));
            assertEquals(3, twoBitField(frame, SECOND_CONTROL_MARKER_BIT));
        }
    }

    @Test
    public void rearGlassHeatingCommandsStayInHeatingOptionsOfTheVoiceGuide() {
        int checked = 0;
        for (String[] example : CanCommandExamples.COMMANDS) {
            if (!example[1].startsWith("обогрев заднего стекла")) {
                continue;
            }
            checked++;
            assertEquals(
                    example[1],
                    EnumSet.of(HEATING),
                    VoiceCommandGroups.forAction(SteeringCanCommandPolicy.actionId(example[0])));
        }
        assertEquals(2, checked);
    }

    private static byte[] iviControlData(String name) {
        String frame = null;
        for (String[] example : CanCommandExamples.COMMANDS) {
            if (example[1].equals(name)) {
                frame = example[0];
            }
        }
        assertNotNull(name, frame);
        String compact = SteeringCanCommandPolicy.compact(frame);
        assertEquals(name, SteeringCanCommandPolicy.HEX_LENGTH, compact.length());
        byte[] command = new byte[compact.length() / 2];
        for (int i = 0; i < command.length; i++) {
            command[i] = (byte) Integer.parseInt(compact.substring(2 * i, 2 * i + 2), 16);
        }
        assertEquals(name, IVI_CONTROL_MESSAGE, command[0] & 0xff);
        assertEquals(name, 8, command[1]);
        return Arrays.copyOfRange(command, 2, command.length);
    }

    private static int twoBitField(byte[] data, int bit) {
        return ((data[bit / 8] & 0xff) >> (bit % 8)) & 0b11;
    }
}
