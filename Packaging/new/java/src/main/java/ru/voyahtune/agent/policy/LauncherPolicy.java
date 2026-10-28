package ru.voyahtune.agent.policy;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public final class LauncherPolicy {
    public static final String NATIVE = "ru.big.town.anative";
    public static final String LAUNCHER = "com.qinggan.app.launcher";
    public final boolean shared;
    private final Map<String, String> settings;
    private final Set<String> fullscreen = new LinkedHashSet<>();

    public LauncherPolicy(boolean shared, Map<String, String> settings) {
        this.shared = shared;
        this.settings = Map.copyOf(settings);
        for (String name : setting("fullscreen_apps").split(",")) if (!name.isBlank() && !name.equals("none")) fullscreen.add(name.trim());
    }

    public String setting(String key) {
        String value = settings.get(key);
        return value == null || value.isEmpty() ? "none" : value;
    }
    public boolean pinned() { return !setting("dockpin").equals("0") && !setting("freeform").equals("0"); }
    public boolean fullscreen(String name) { return fullscreen.contains(name); }
    public boolean dashboardShort(LauncherNavigation.Button button) { return setting(button == LauncherNavigation.Button.HOME ? "dockHomeShortVoyahTune" : "dockAllAppsShortVoyahTune").equals("1"); }
    public boolean hideCarPlay() {
        try { return Integer.parseInt(setting("carplay_hide_system_bars")) == 1; }
        catch (NumberFormatException invalid) { return false; }
    }

    public boolean carPlay(Top foreground) {
        return carPlayActivity(foreground) && (shared || foreground.live);
    }

    public boolean hides(Top foreground, long revealRemaining) {
        if (!shared && carPlayActivity(foreground) && !foreground.live) return false;
        if (carPlay(foreground)) return hideCarPlay() && revealRemaining == 0;
        return fullscreen(foreground.packageName);
    }

    public boolean keeps(Top foreground) {
        if (NATIVE.equals(foreground.packageName) && foreground.activity.equals(NATIVE + ".CarPlayFullscreenActivity")) return !hideCarPlay();
        if (!pinned() || foreground.packageName.isEmpty() || fullscreen(foreground.packageName)) return false;
        if (foreground.packageName.startsWith("ru.big.town")) return inset(foreground.activity);
        return !stock(foreground.packageName);
    }

    public Boolean desired(int display, Top foreground, long revealRemaining) {
        if (!shared && carPlayActivity(foreground) && !foreground.live) return true;
        if (carPlay(foreground)) return !hideCarPlay() || revealRemaining > 0;
        if (foreground.packageName.isEmpty()) return null;
        if (fullscreen(foreground.packageName)) return false;
        if (keeps(foreground)) return true;
        if (shared && LAUNCHER.equals(foreground.packageName)) {
            if (foreground.activity.contains("SecondMainActivity")) return false;
            if (display == 0) return true;
        }
        return null;
    }

    public static boolean stock(String name) {
        if (name == null || name.isEmpty()) return true;
        return WindowPolicy.blacklisted(name) && !name.startsWith("ru.big.town");
    }

    private static boolean carPlayActivity(Top foreground) {
        return NATIVE.equals(foreground.packageName) && foreground.activity.equals(NATIVE + ".CarPlayFullscreenActivity");
    }

    private boolean inset(String activity) {
        return activity.contains("SplitHostActivity") || activity.contains("restoremode.MainActivity")
                || activity.equals("com.shilapi.xcertplay.DiPlayActivity") || activity.equals("com.shilapi.xcertplay.CarPlayHostActivity")
                || activity.equals("ru.big.town.restoremode.carplay.CarPlayLauncherActivity") || activity.contains("AdvanceActivity")
                || activity.contains("TripHistoryActivity") || shared && activity.contains("TaskManagerActivity")
                || !shared && (activity.equals(NATIVE + ".CarPlayWindowedActivity") || activity.equals("ru.big.town.restoremode.passenger.PassengerDashboardActivity"));
    }

    public static Top top(String value, String separator, boolean live) {
        value = value == null ? "" : value;
        String matched = separator == null || separator.isEmpty() ? "|" : separator;
        int index = value.indexOf(matched);
        if (index < 0 && !matched.equals("|")) { matched = "|"; index = value.indexOf(matched); }
        return index < 0 ? new Top(value, "", live) : new Top(value.substring(0, index), value.substring(index + matched.length()), live);
    }

    public static final class Top {
        public final String packageName;
        public final String activity;
        public final boolean live;
        public Top(String packageName, String activity, boolean live) {
            this.packageName = clean(packageName);
            activity = clean(activity);
            this.activity = activity.startsWith(".") ? this.packageName + activity : activity;
            this.live = live;
        }
        private static String clean(String value) { return value == null || value.equals("null") || value.equals("undefined") ? "" : value; }
    }
}
