package ru.big.town.common;

/** Signature-protected RestoreMode/Native Messenger contract. */
public final class SuspensionWidgetProtocol {
    private SuspensionWidgetProtocol() {}
    public static final int WATCH = 90, UNWATCH = 91, SELECT = 92, STATE = 93;
    public static final String HEIGHT = "height", DIRECTION = "direction", MAINTENANCE = "maintenance",
            DRIVE = "drive", MESSAGE = "message", PENDING = "pending", AVAILABLE = "available";
    public static final String HEIGHT_ONLY = "heightOnly";
    public static final String LOWEST_BLOCKED_REASON = "lowestBlockedReason";
    public static final int LOWEST = 0, LOW = 1, MEDIUM = 2, HIGH = 3;
}
