package ru.big.town.common;

/** Persistent drive selection, independent of transport acceptance and physical feedback. */
public final class DriveSelectionPolicy {
    public static final String OVERRIDE = "suspensionDriveOverride";
    public static final String MEDIUM = "lastMediumDrive";
    public static final String CONFIGURED = "configuredDriveMode";
    public static final String CURRENT = "currentTripDrive";
    public static final String SOURCE = "driveSelectionSource", MODE = "driveSelectionMode";
    public static final String WIDGET = "widget", EXPLICIT = "explicit", FEEDBACK = "feedback", SETTINGS = "settings";

    public final String configured, override, medium, current;
    public DriveSelectionPolicy(String configured, String override, String medium) {
        this(configured, override, medium, "");
    }
    public DriveSelectionPolicy(String configured, String override, String medium, String current) {
        this.configured = valid(configured) ? configured : "INDIVIDUAL";
        this.override = valid(override) ? override : "";
        this.medium = isMedium(medium) ? medium : isMedium(this.configured) ? this.configured : "ECO";
        this.current = valid(current) ? current : "";
    }
    public String effective() { return current.isEmpty() ? (override.isEmpty() ? configured : override) : current; }
    public DriveSelectionPolicy nextTrip() { return new DriveSelectionPolicy(configured, override, medium); }
    public DriveSelectionPolicy select(String mode, String source, boolean remember) {
        if (!valid(mode)) return this;
        // State callbacks carry no origin and may be echoes of an automatic OEM reset.
        if (!WIDGET.equals(source) && !EXPLICIT.equals(source) && !SETTINGS.equals(source)) return this;
        return new DriveSelectionPolicy(remember || SETTINGS.equals(source) ? mode : configured,
                WIDGET.equals(source) ? mode : "", isMedium(mode) ? mode : medium, mode);
    }
    public static boolean valid(String mode) {
        return isMedium(mode) || "SPORT".equals(mode) || "OUTING".equals(mode);
    }
    public static boolean isMedium(String mode) {
        return "ECO".equals(mode) || "COMFORT".equals(mode)
                || "SNOW".equals(mode) || "INDIVIDUAL".equals(mode);
    }
    public static int value(String mode) {
        if ("ECO".equals(mode)) return 1;
        if ("COMFORT".equals(mode)) return 2;
        if ("SPORT".equals(mode)) return 3;
        if ("OUTING".equals(mode)) return 4;
        if ("INDIVIDUAL".equals(mode)) return 5;
        if ("SNOW".equals(mode)) return 6;
        return -1;
    }
}
