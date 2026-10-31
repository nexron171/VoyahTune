package ru.big.town.restoremode.integration.config;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import ru.big.town.common.ClusterNavigationSettings;
import ru.big.town.common.DockIconStyle;
import ru.big.town.common.NavigationAudioSettings;
import ru.big.town.common.ScenarioProtocol;
import ru.big.town.common.ScreenBrightnessSettings;
import ru.big.town.restoremode.apps.AppDpiStore;
import ru.big.town.restoremode.apps.DockLongPressAction;
import ru.big.town.restoremode.apps.FullscreenAppStore;
import ru.big.town.restoremode.apps.NavigationAudioAppStore;
import ru.big.town.restoremode.apps.split.SplitStore;
import ru.big.town.restoremode.scenarios.ScenarioStore;
import ru.big.town.restoremode.vehicle.steering.SteeringActionStore;
import ru.big.town.restoremode.voice.commands.VoiceCommands;
import ru.big.town.restoremode.voice.commands.VoiceSteeringPolicy;

import java.util.List;

/** Publishes saved configuration on edits, startup and physical wake without polling. */
public final class SplitConfigSync {
    private static final String NATIVE_PKG = "ru.big.town.anative";
    private static final String CONFIG_RECEIVER = "ru.big.town.anative.SetModesConfigReceiver";

    private SplitConfigSync() {}

    public static void pushAll(Context context, SharedPreferences prefs) {
        pushFullscreenApps(context, prefs);
        pushNavigationAudio(context, prefs);
        pushAppDpi(context, prefs, null, 0);
        pushDock(context, prefs);
        pushSteering(context, prefs);
        pushKeyboard(context, prefs);
        pushTheme(context, prefs);
        pushScenarios(context, prefs);
        pushScreenBrightness(context, prefs);
        pushClusterNavigation(context, prefs);
    }

    public static void pushClusterNavigation(Context context, SharedPreferences preferences) {
        Intent intent = configIntent(ClusterNavigationSettings.ACTION_CONFIG);
        intent.putExtra(
                ClusterNavigationSettings.ENABLED,
                preferences.getBoolean(
                        ClusterNavigationSettings.ENABLED,
                        ClusterNavigationSettings.DEFAULT_ENABLED));
        context.sendBroadcast(intent);
    }

    /** Полный снимок сценариев; движок Native перечитывает его при изменении и на старте. */
    public static void pushScenarios(Context context, SharedPreferences prefs) {
        Intent i = new Intent(ScenarioProtocol.ACTION_CONFIG);
        i.setClassName(NATIVE_PKG, "ru.big.town.anative.ScenarioConfigReceiver");
        i.putExtra(ScenarioProtocol.EXTRA_JSON, ScenarioStore.toJson(ScenarioStore.load(prefs)));
        context.sendBroadcast(i);
    }

    public static void pushScreenBrightness(Context context, SharedPreferences prefs) {

        Intent intent = new Intent(ScreenBrightnessSettings.ACTION_CONFIG);
        intent.setClassName(NATIVE_PKG, "ru.big.town.anative.ScreenBrightnessConfigReceiver");
        intent.putExtra(
                ScreenBrightnessSettings.ENABLED,
                prefs.getBoolean(ScreenBrightnessSettings.ENABLED, false));
        intent.putExtra(
                ScreenBrightnessSettings.RULES,
                prefs.getString(
                        ScreenBrightnessSettings.RULES, ScreenBrightnessSettings.DEFAULT_RULES));
        context.sendBroadcast(intent);
    }

    public static void pushFullscreenApps(Context context, SharedPreferences prefs) {
        Intent i = configIntent("ru.big.town.anative.FULLSCREEN_APPS_CONFIG");
        i.putExtra("packagesCsv", FullscreenAppStore.snapshotCsv(prefs));
        context.sendBroadcast(i);
    }

    public static void pushNavigationAudio(Context context, SharedPreferences preferences) {
        Intent intent = configIntent(NavigationAudioSettings.ACTION_CONFIG);
        intent.putExtra(
                NavigationAudioSettings.EXTRA_PACKAGES,
                NavigationAudioAppStore.snapshot(preferences));
        context.sendBroadcast(intent);
    }

    /**
     * Публикует полный DPI snapshot; changedPkg нужен, чтобы надёжно передать переход в «Авто» (0).
     */
    public static void pushAppDpi(
            Context context, SharedPreferences prefs, String changedPkg, int changedDpi) {
        Intent i = configIntent("ru.big.town.anative.APP_DPI_CONFIG");
        i.putExtra("appDpiJson", AppDpiStore.snapshotJson(prefs));
        if (changedPkg != null && !changedPkg.isEmpty()) {
            i.putExtra("changedPkg", changedPkg);
            i.putExtra("changedDpi", Math.max(0, changedDpi));
        }
        context.sendBroadcast(i);
    }

    public static void pushDock(Context context, SharedPreferences prefs) {
        String p1 = prefs.getString("dockOverride1", "");
        String p2 = prefs.getString("dockOverride2", "");
        Intent i = configIntent("ru.big.town.anative.DOCK_CONFIG");
        boolean carPlayEnabled = ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(context);
        i.putExtra(ru.big.town.common.CarPlayTarget.ENABLED, carPlayEnabled);
        if (!carPlayEnabled) {
            if (ru.big.town.common.CarPlayTarget.isCarPlay(p1)) p1 = "";
            if (ru.big.town.common.CarPlayTarget.isCarPlay(p2)) p2 = "";
        }
        i.putExtra("dock1", p1.isEmpty() ? "none" : p1);
        i.putExtra("dock2", p2.isEmpty() ? "none" : p2);
        i.putExtra("dock1Dpi", p1.isEmpty() ? 0 : AppDpiStore.get(prefs, p1));
        i.putExtra("dock2Dpi", p2.isEmpty() ? 0 : AppDpiStore.get(prefs, p2));
        for (int slot = 1; slot <= 2; slot++) {
            DockIconStyle style =
                    DockIconStyle.fromValue(
                            prefs.getString(DockIconStyle.preferenceKey(slot), "default"));
            i.putExtra(DockIconStyle.configKey(slot), style.value);
        }

        i.putExtra(
                ru.big.town.common.CarPlayTarget.FULLSCREEN,
                com.shilapi.xcertplay.CarPlaySettingsAccess.fullscreen(context));
        i.putExtra(
                ru.big.town.common.CarPlayTarget.HIDE_SYSTEM_BARS,
                com.shilapi.xcertplay.CarPlaySettingsAccess.hideSystemBars(context));
        i.putExtra(
                ru.big.town.common.CarPlayTarget.ASTON_MARTIN,
                com.shilapi.xcertplay.CarPlaySettingsAccess.dock(context)
                        == com.shilapi.xcertplay.CarPlayDock.DUAL_SIDES);
        i.putExtra("dockHomeShortVoyahTune", prefs.getBoolean("dockHomeShortVoyahTune", false));
        i.putExtra(
                "dockAllAppsShortVoyahTune", prefs.getBoolean("dockAllAppsShortVoyahTune", false));

        addDockSplitExtras(i, 1, p1, prefs);
        addDockSplitExtras(i, 2, p2, prefs);
        context.sendBroadcast(i);
    }

    public static void pushSteering(Context context, SharedPreferences prefs) {
        Intent i = configIntent("ru.big.town.anative.STEER_CONFIG");
        i.putExtra(
                "steerStarShort",
                resolveSteerActions(prefs.getString("steerStarShort", "none"), prefs));
        i.putExtra(
                "steerStarLong",
                resolveSteerActions(prefs.getString("steerStarLong", "none"), prefs));
        i.putExtra(
                "steerDvrShort",
                resolveSteerActions(prefs.getString("steerDvrShort", "none"), prefs));
        i.putExtra(
                "steerDvrLong",
                resolveSteerActions(prefs.getString("steerDvrLong", "none"), prefs));
        for (String key : new String[] {"steerVoiceShort", "steerVoiceLong"}) {
            i.putExtra(
                    key,
                    VoiceSteeringPolicy.publishedAction(
                            prefs.getBoolean(VoiceCommands.ENABLED, false),
                            prefs.getString(
                                    VoiceSteeringPolicy.PRESS_KEY, VoiceSteeringPolicy.LONG),
                            ru.big.town.restoremode.carplay.CarPlayFeature.isEnabled(context)
                                    && prefs.getBoolean(VoiceSteeringPolicy.SIRI_ENABLED, false),
                            key,
                            resolveSteerActions(prefs.getString(key, "none"), prefs)));
        }
        i.putExtra(
                "steerPhoneShort",
                resolveSteerActions(prefs.getString("steerPhoneShort", "none"), prefs));
        i.putExtra(
                "steerPhoneLong",
                resolveSteerActions(prefs.getString("steerPhoneLong", "none"), prefs));
        context.sendBroadcast(i);
    }

    /**
     * Keyboard hooks are opt-in. Both UI switches are projections of this single mutually-exclusive
     * mode because the English and Russian agents hook the same Qinggan IME methods and must never
     * be injected together.
     */
    public static void pushKeyboard(Context context, SharedPreferences prefs) {
        String mode = normalizeKeyboardMode(prefs.getString("keyboardMode", "off"));
        Intent i = configIntent("ru.big.town.anative.KEYBOARD_CONFIG");
        i.putExtra("keyboardMode", mode);
        context.sendBroadcast(i);
    }

    public static void pushTheme(Context context, SharedPreferences preferences) {
        if (!preferences.contains("themeOverride")) {
            return;
        }
        Intent intent = configIntent("ru.big.town.anative.THEME_CONFIG");
        intent.putExtra("themeOverride", preferences.getInt("themeOverride", 0));
        context.sendBroadcast(intent);
    }

    public static String normalizeKeyboardMode(String mode) {
        return "en".equals(mode) || "ru".equals(mode) ? mode : "off";
    }

    private static Intent configIntent(String action) {
        Intent i = new Intent(action);
        i.setClassName(NATIVE_PKG, CONFIG_RECEIVER);
        return i;
    }

    private static void addDockSplitExtras(
            Intent i, int slot, String slotPkg, SharedPreferences prefs) {
        String action = DockLongPressAction.resolve(prefs, slot);
        i.putExtra("dock" + slot + "LongAction", action);
        if (!"split".equals(action)) {
            i.putExtra("dock" + slot + "HasSplit", false);
            return;
        }
        int idx = slotPkg.isEmpty() ? -1 : prefs.getInt("dockOverride" + slot + "Split", -1);
        List<SplitStore.Preset> all = SplitStore.load(prefs);
        if (idx < 0 || idx >= all.size() || !all.get(idx).ready()) {
            i.putExtra("dock" + slot + "LongAction", "none");
            i.putExtra("dock" + slot + "HasSplit", false);
            return;
        }
        SplitStore.Preset ps = all.get(idx);
        i.putExtra("dock" + slot + "HasSplit", true);
        i.putExtra("dock" + slot + "SplitL", ps.l);
        i.putExtra("dock" + slot + "SplitR", ps.r);
        i.putExtra("dock" + slot + "SplitRatio", ps.ratio);
        i.putExtra("dock" + slot + "SplitLDpi", AppDpiStore.get(prefs, ps.l));
        i.putExtra("dock" + slot + "SplitRDpi", AppDpiStore.get(prefs, ps.r));
        i.putExtra("dock" + slot + "SplitResizable", ps.resizable);
        i.putExtra("dock" + slot + "SplitFraction", SplitStore.leftFraction(ps));
        i.putExtra("dock" + slot + "SplitPresetIdx", idx); // fallback для старого Native
        i.putExtra("dock" + slot + "SplitPresetId", ps.id);
    }

    static String resolveSteerActions(String stored, SharedPreferences prefs) {
        List<String> resolved = new java.util.ArrayList<>();
        for (String action : SteeringActionStore.decode(stored)) {
            String value = resolveSteerAction(action, prefs);
            if (value != null && !value.isEmpty() && !"none".equals(value)) {
                resolved.add(value);
            }
        }
        return SteeringActionStore.encode(resolved);
    }

    /** Backward-compatible CSV: старый Native прочитает первые пять полей, новый — все восемь. */
    public static String resolveSteerAction(String id, SharedPreferences prefs) {
        if (id == null || !id.startsWith("split:")) {
            return id;
        }
        try {
            int n = Integer.parseInt(id.substring("split:".length()));
            List<SplitStore.Preset> all = SplitStore.load(prefs);
            if (n >= 0 && n < all.size() && all.get(n).ready()) {
                SplitStore.Preset ps = all.get(n);
                return "split:"
                        + ps.l
                        + ","
                        + ps.r
                        + ","
                        + ps.ratio
                        + ","
                        + AppDpiStore.get(prefs, ps.l)
                        + ","
                        + AppDpiStore.get(prefs, ps.r)
                        + ","
                        + (ps.resizable ? "1" : "0")
                        + ","
                        + SplitStore.leftFraction(ps)
                        + ","
                        + ps.id;
            }
        } catch (Exception ignored) {
        }
        return "none";
    }
}
