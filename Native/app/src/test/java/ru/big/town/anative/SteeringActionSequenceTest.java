package ru.big.town.anative;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SteeringActionSequenceTest {
    @Test
    public void decodesProtocolAndLegacyValues() {
        String sequence = "steer-actions-v1:16:toggle_forced_ev24:can:64088000000000000003";

        assertEquals(Arrays.asList("toggle_forced_ev", "can:64088000000000000003"),
                SteeringActionSequence.decode(sequence));
        assertEquals(Collections.singletonList("open_voyahtune"),
                SteeringActionSequence.decode("open_voyahtune"));
        assertTrue(SteeringActionSequence.contains(sequence, "toggle_forced_ev"));
        assertFalse(SteeringActionSequence.contains(sequence, "system_back"));
    }

    @Test
    public void parsesOnlyOneExactCanFrame() {
        assertArrayEquals(new byte[] {
                        0x64, 0x08, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0x03
                },
                SteeringActionSequence.parseCustomCan("can:64088000000000000003"));
        assertNull(SteeringActionSequence.parseCustomCan("can:6408"));
        assertNull(SteeringActionSequence.parseCustomCan("can:6408800000000000000z"));
    }
}
