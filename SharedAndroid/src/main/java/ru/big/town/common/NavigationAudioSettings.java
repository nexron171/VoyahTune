package ru.big.town.common;

import java.util.LinkedHashSet;

public final class NavigationAudioSettings {
    public static final String PACKAGES = "navigationAudioPackages";
    public static final String GLOBAL_PACKAGES = "voyahtune_navigation_audio_apps";
    public static final String ACTION_CONFIG = "ru.big.town.anative.NAVIGATION_AUDIO_CONFIG";
    public static final String EXTRA_PACKAGES = "packagesCsv";
    public static final String TAG_PREFIX = "voyahtune.navigation:";
    public static final int STREAM = 1;

    private NavigationAudioSettings() {}

    public static String normalizeCsv(String value) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        if (value != null) {
            for (String entry : value.split(",")) {
                String packageName = entry.trim();
                if (isSelectablePackage(packageName)) packages.add(packageName);
            }
        }
        return String.join(",", packages);
    }

    public static boolean isSelectablePackage(String packageName) {
        return packageName != null
                && packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                && !packageName.startsWith("ru.big.town.");
    }

    public static boolean contains(String packages, String packageName) {
        if (packages == null || !isSelectablePackage(packageName)) return false;
        for (String entry : packages.split(",")) {
            if (entry.equals(packageName)) return true;
        }
        return false;
    }
}
