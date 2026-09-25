package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceSteeringPolicyTest {
    private static final String SAVED_SHORT = "steer-actions-v1:11:system_back";
    private static final String SAVED_LONG = "app:example.player";

    @Test public void missingOrUnknownPreferencePreservesLongPressDefault() {
        for (String press : new String[]{null, "", "long", "unknown"}) {
            assertEquals("long", VoiceSteeringPolicy.normalize(press));
            assertTrue(VoiceSteeringPolicy.ownsSlot(true, true, press, "steerVoiceLong"));
            assertFalse(VoiceSteeringPolicy.ownsSlot(true, true, press, "steerVoiceShort"));
        }
    }

    @Test public void switchingPressRestoresOriginalActionsInTheOtherSlot() {
        for (String press : new String[]{"long", "short", "long"}) {
            assertEquals(press.equals("short") ? "voice_assistant" : SAVED_SHORT,
                    VoiceSteeringPolicy.publishedAction(true, true, press, "steerVoiceShort", SAVED_SHORT));
            assertEquals(press.equals("long") ? "voice_assistant" : SAVED_LONG,
                    VoiceSteeringPolicy.publishedAction(true, true, press, "steerVoiceLong", SAVED_LONG));
            assertEquals(SAVED_SHORT,
                    VoiceSteeringPolicy.publishedAction(true, false, press, "steerVoiceShort", SAVED_SHORT));
            assertEquals(SAVED_LONG,
                    VoiceSteeringPolicy.publishedAction(true, false, press, "steerVoiceLong", SAVED_LONG));
        }
    }

    @Test public void editorLocksExactlyThePublishedAssistantSlot() {
        for (boolean full : new boolean[]{false, true}) for (boolean enabled : new boolean[]{false, true}) {
            for (String press : new String[]{"short", "long"}) {
                int locked = 0;
                for (String slot : new String[]{"steerVoiceShort", "steerVoiceLong", "steerStarShort", "steerPhoneLong"}) {
                    boolean owns = VoiceSteeringPolicy.ownsSlot(full, enabled, press, slot);
                    if (owns) locked++;
                    assertEquals(owns, "voice_assistant".equals(
                            VoiceSteeringPolicy.publishedAction(full, enabled, press, slot, "none")));
                }
                assertEquals(full && enabled ? 1 : 0, locked);
            }
        }
    }
}
