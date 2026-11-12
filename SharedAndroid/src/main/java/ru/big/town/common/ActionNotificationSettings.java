package ru.big.town.common;

/** Shared preference and configuration key for action notifications. */
public final class ActionNotificationSettings {
    public static final String ENABLED = "actionNotificationsEnabled";
    public static final String CONTACT_NAMES = "actionNotificationContactNames";
    public static final boolean DEFAULT_ENABLED = true;

    private ActionNotificationSettings() {}
}
