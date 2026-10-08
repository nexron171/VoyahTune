package ru.big.town.restoremode.voice.commands;

/** Overrides only the published slot; saved steering actions are never rewritten. */
public final class VoiceSteeringPolicy {
    public static final String SIRI_ENABLED = "carPlaySiriEnabled";
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

    public static boolean siriOwnsSlot(boolean enabled, String assistantPress, String slot) {
        String opposite = SHORT.equals(normalize(assistantPress)) ? LONG : SHORT;
        return ownsSlot(enabled, opposite, slot);
    }

    public static String publishedAction(
            boolean enabled, String press, boolean siriEnabled, String slot, String saved) {
        if (ownsSlot(enabled, press, slot)) return ACTION;
        if (siriOwnsSlot(siriEnabled, press, slot))
            return ru.big.town.common.CarPlayVehicleProtocol.SIRI_ACTION;
        return saved;
    }

    public static String publishedAction(boolean enabled, String press, String slot, String saved) {
        return ownsSlot(enabled, press, slot) ? ACTION : saved;
    }
}
