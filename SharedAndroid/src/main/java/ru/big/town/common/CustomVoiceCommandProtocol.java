package ru.big.town.common;

public final class CustomVoiceCommandProtocol {
    public static final String ACTION_PREFIX = "voice-custom-v1:";
    public static final String STEERING_SEQUENCE_PREFIX = "steer-actions-v1:";
    public static final int MAX_ACTIONS = 32;

    private CustomVoiceCommandProtocol() {}

    public static String action(String id, String configuration) {
        return ACTION_PREFIX + id + ":" + configuration;
    }

    public static boolean isAction(String action) {
        return action != null && action.startsWith(ACTION_PREFIX);
    }

    public static String configuration(String action) {
        if (!isAction(action)) return null;
        int separator = action.indexOf(':', ACTION_PREFIX.length());
        if (separator <= ACTION_PREFIX.length()) return null;
        String configured = action.substring(separator + 1);
        return configured.startsWith(STEERING_SEQUENCE_PREFIX) ? configured : null;
    }

    public static long responseTimeoutMillis(String action) {
        return isAction(action) ? MAX_ACTIONS * 8000L : 8000L;
    }
}
