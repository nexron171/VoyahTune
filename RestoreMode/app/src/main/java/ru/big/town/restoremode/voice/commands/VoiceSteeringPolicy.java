package ru.big.town.restoremode.voice.commands;

/** Overrides only the published slot; saved steering actions are never rewritten. */
public final class VoiceSteeringPolicy {
    public static final String PRESS_KEY = "voiceAssistantSteeringPress";
    public static final String SHORT = "short", LONG = "long";
    static final String ACTION = "voice_assistant";

    public static String normalize(String press) {
        return SHORT.equals(press) ? SHORT : LONG;
    }

    public static boolean ownsSlot(boolean enabled, String press, String slot) {
        String selected = SHORT.equals(normalize(press)) ? "steerVoiceShort" : "steerVoiceLong";
        return enabled && selected.equals(slot);
    }

    public static String publishedAction(boolean enabled, String press, String slot, String saved) {
        return ownsSlot(enabled, press, slot) ? ACTION : saved;
    }
}
