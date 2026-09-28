package ru.big.town.restoremode;

/** Overrides only the published slot; saved steering actions are never rewritten. */
final class VoiceSteeringPolicy {
    static final String PRESS_KEY = "voiceAssistantSteeringPress";
    static final String SHORT = "short", LONG = "long";
    static final String ACTION = "voice_assistant";

    static String normalize(String press) { return SHORT.equals(press) ? SHORT : LONG; }

    static boolean ownsSlot(boolean full, boolean enabled, String press, String slot) {
        String selected = SHORT.equals(normalize(press)) ? "steerVoiceShort" : "steerVoiceLong";
        return full && enabled && selected.equals(slot);
    }

    static String publishedAction(boolean full, boolean enabled, String press, String slot, String saved) {
        return ownsSlot(full, enabled, press, slot) ? ACTION : saved;
    }
}
