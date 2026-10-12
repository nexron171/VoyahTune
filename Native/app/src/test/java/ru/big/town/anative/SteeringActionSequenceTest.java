package ru.big.town.anative;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.big.town.common.CustomVoiceCommandProtocol;

import java.util.Arrays;
import java.util.Collections;

public class SteeringActionSequenceTest {
    @Test
    public void decodesProtocolAndLegacyValues() {
        String sequence = "steer-actions-v1:16:toggle_forced_ev24:can:64088000000000000003";

        assertEquals(
                Arrays.asList("toggle_forced_ev", "can:64088000000000000003"),
                SteeringActionSequence.decode(sequence));
        assertEquals(
                Collections.singletonList("open_voyahtune"),
                SteeringActionSequence.decode("open_voyahtune"));
        assertTrue(SteeringActionSequence.contains(sequence, "toggle_forced_ev"));
        assertFalse(SteeringActionSequence.contains(sequence, "system_back"));
    }

    @Test
    public void parsesOnlyOneExactCanFrame() {
        assertArrayEquals(
                new byte[] {0x64, 0x08, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0x03},
                SteeringActionSequence.parseCustomCan("can:64088000000000000003"));
        assertNull(SteeringActionSequence.parseCustomCan("can:6408"));
        assertNull(SteeringActionSequence.parseCustomCan("can:6408800000000000000z"));
    }

    @Test
    public void customVoiceUsesTheSteeringProtocolAndRejectsMalformedLists() {
        String sequence = "steer-actions-v1:18:headlights:parking16:toggle_forced_ev";
        assertEquals(
                Arrays.asList("headlights:parking", "toggle_forced_ev"),
                SteeringActionSequence.decodeCustomVoice(
                        CustomVoiceCommandProtocol.action("entry", sequence)));
        assertTrue(
                SteeringActionSequence.decodeCustomVoice("voice-custom-v1::" + sequence).isEmpty());
        assertTrue(
                SteeringActionSequence.decodeCustomVoice("voice-custom-v1:entry:headlights:parking")
                        .isEmpty());
        assertTrue(
                SteeringActionSequence.decodeCustomVoice(
                                CustomVoiceCommandProtocol.action(
                                        "entry", "steer-actions-v1:2147483647:headlights:parking"))
                        .isEmpty());
        assertTrue(
                SteeringActionSequence.decodeCustomVoice(
                                CustomVoiceCommandProtocol.action(
                                        "entry", "steer-actions-v1:15:voice_assistant"))
                        .isEmpty());
        StringBuilder tooLong = new StringBuilder("steer-actions-v1:");
        for (int index = 0; index <= CustomVoiceCommandProtocol.MAX_ACTIONS; index++) {
            tooLong.append("4:wash");
        }
        assertTrue(
                SteeringActionSequence.decodeCustomVoice(
                                CustomVoiceCommandProtocol.action("entry", tooLong.toString()))
                        .isEmpty());
    }
}
