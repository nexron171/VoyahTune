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

    public enum Screen {
        MAIN(ENABLED, ENTRIES, DELAY, PATH, 0),
        PASSENGER(
                "passengerApplicationStartupEnabled",
                "passengerApplicationStartupEntries",
                "passengerApplicationStartupDelaySeconds",
                "passenger-application-startup",
                1);

        public final String enabledKey;
        public final String entriesKey;
        public final String delayKey;
        public final String path;
        public final int displayId;

        Screen(String enabledKey, String entriesKey, String delayKey, String path, int displayId) {
            this.enabledKey = enabledKey;
            this.entriesKey = entriesKey;
            this.delayKey = delayKey;
            this.path = path;
            this.displayId = displayId;
        }

        public String uri() {
            return "content://ru.big.town.restoremode.restoremodecontentprovider/" + path;
        }
    }

    private ApplicationStartupSettings() {}
}
