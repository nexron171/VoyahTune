package ru.big.town.restoremode.voice.commands;

import static org.junit.Assert.*;

import org.junit.Test;

public class VoiceSteeringPolicyTest {
    private static final String SAVED_SHORT = "steer-actions-v1:11:system_back";
    private static final String SAVED_LONG = "app:example.player";

    @Test
    public void siriUsesOppositePressAndDisablingRestoresSavedActions() {
        for (String press : new String[] {"short", "long"}) {
            String assistantSlot = press.equals("short") ? "steerVoiceShort" : "steerVoiceLong";
            String siriSlot = press.equals("short") ? "steerVoiceLong" : "steerVoiceShort";
            assertEquals(
                    "voice_assistant",
                    VoiceSteeringPolicy.publishedAction(
                            true, press, true, assistantSlot, SAVED_SHORT));
            assertEquals(
                    "carplay_siri",
                    VoiceSteeringPolicy.publishedAction(true, press, true, siriSlot, SAVED_LONG));
            assertEquals(
                    "carplay_siri",
                    VoiceSteeringPolicy.publishedAction(false, press, true, siriSlot, SAVED_LONG));
            assertEquals(
                    SAVED_LONG,
                    VoiceSteeringPolicy.publishedAction(true, press, false, siriSlot, SAVED_LONG));
            assertEquals(
                    SAVED_SHORT,
                    VoiceSteeringPolicy.publishedAction(
                            false, press, true, assistantSlot, SAVED_SHORT));
            assertEquals(
                    SAVED_LONG,
                    VoiceSteeringPolicy.publishedAction(
                            true, press, true, "steerPhoneLong", SAVED_LONG));
        }
    }

    @Test
    public void missingOrUnknownPreferencePreservesLongPressDefault() {
        for (String press : new String[] {null, "", "long", "unknown"}) {
            assertEquals("long", VoiceSteeringPolicy.normalize(press));
            assertTrue(VoiceSteeringPolicy.ownsSlot(true, press, "steerVoiceLong"));
            assertFalse(VoiceSteeringPolicy.ownsSlot(true, press, "steerVoiceShort"));
        }
    }

    @Test
    public void switchingPressRestoresOriginalActionsInTheOtherSlot() {
        for (String press : new String[] {"long", "short", "long"}) {
            assertEquals(
                    press.equals("short") ? "voice_assistant" : SAVED_SHORT,
                    VoiceSteeringPolicy.publishedAction(
                            true, press, "steerVoiceShort", SAVED_SHORT));
            assertEquals(
                    press.equals("long") ? "voice_assistant" : SAVED_LONG,
                    VoiceSteeringPolicy.publishedAction(true, press, "steerVoiceLong", SAVED_LONG));
            assertEquals(
                    SAVED_SHORT,
                    VoiceSteeringPolicy.publishedAction(
                            false, press, "steerVoiceShort", SAVED_SHORT));
            assertEquals(
                    SAVED_LONG,
                    VoiceSteeringPolicy.publishedAction(
                            false, press, "steerVoiceLong", SAVED_LONG));
        }
    }

    @Test
    public void editorLocksExactlyThePublishedAssistantSlot() {
        for (boolean enabled : new boolean[] {false, true}) {
            for (String press : new String[] {"short", "long"}) {
                int locked = 0;
                for (String slot :
                        new String[] {
                            "steerVoiceShort", "steerVoiceLong", "steerStarShort", "steerPhoneLong"
                        }) {
                    boolean owns = VoiceSteeringPolicy.ownsSlot(enabled, press, slot);
                    if (owns) {
                        locked++;
                    }
                    assertEquals(
                            owns,
                            "voice_assistant"
                                    .equals(
                                            VoiceSteeringPolicy.publishedAction(
                                                    enabled, press, slot, "none")));
                }
                assertEquals(enabled ? 1 : 0, locked);
            }
        }
    }
}
