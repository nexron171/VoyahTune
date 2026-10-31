package ru.big.town.common;

public final class ApplicationStartupSettings {
    public static final String ENABLED = "applicationStartupEnabled";
    public static final String ENTRIES = "applicationStartupEntries";
    public static final String DELAY = "applicationStartupDelaySeconds";
    public static final int DEFAULT_DELAY_SECONDS = 3;
    public static final int MAX_DELAY_SECONDS = 60;
    public static final String PATH = "application-startup";
    public static final String URI =
            "content://ru.big.town.restoremode.restoremodecontentprovider/" + PATH;

    private ApplicationStartupSettings() {}
}
