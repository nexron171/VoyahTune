package ru.big.town.restoremode.apps;

import android.content.SharedPreferences;

import ru.big.town.common.NavigationAudioSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class NavigationAudioAppStore {
    private NavigationAudioAppStore() {}

    public static String snapshot(SharedPreferences preferences) {
        return NavigationAudioSettings.normalizeCsv(
                preferences.getString(NavigationAudioSettings.PACKAGES, ""));
    }

    public static List<String> load(SharedPreferences preferences) {
        String packages = snapshot(preferences);
        return packages.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(packages.split(",")));
    }

    public static void save(SharedPreferences preferences, List<String> packages) {
        preferences
                .edit()
                .putString(
                        NavigationAudioSettings.PACKAGES,
                        NavigationAudioSettings.normalizeCsv(String.join(",", packages)))
                .apply();
    }
}
