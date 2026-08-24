package ru.big.town.restoremode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Единая сериализация пресетов для Native. Dock и steering читают копии из Settings.Global, поэтому
 * после любого изменения пресета обе копии должны обновляться одновременно.
 */
final class SplitConfigSync {
    private static final String NATIVE_PKG = "ru.big.town.anative";
    private static final String CONFIG_RECEIVER = "ru.big.town.anative.SetModesConfigReceiver";

    private SplitConfigSync() {}

    static void pushAll(Context context, SharedPreferences prefs) {
        pushAppDpi(context, prefs, null, 0);
        pushDock(context, prefs);
        pushSteering(context, prefs);
    }

    /** Публикует полный DPI snapshot; changedPkg нужен, чтобы надёжно передать переход в «Авто» (0). */
    static void pushAppDpi(Context context, SharedPreferences prefs, String changedPkg, int changedDpi) {
        Intent i = configIntent("ru.big.town.anative.APP_DPI_CONFIG");
        i.putExtra("appDpiJson", AppDpiStore.snapshotJson(prefs));
        if (changedPkg != null && !changedPkg.isEmpty()) {
            i.putExtra("changedPkg", changedPkg);
            i.putExtra("changedDpi", Math.max(0, changedDpi));
        }
        context.sendBroadcast(i);
    }

    static void pushDock(Context context, SharedPreferences prefs) {
        String p1 = prefs.getString("dockOverride1", "");
        String p2 = prefs.getString("dockOverride2", "");
        Intent i = configIntent("ru.big.town.anative.DOCK_CONFIG");
        i.putExtra("dock1", p1.isEmpty() ? "none" : p1);
        i.putExtra("dock2", p2.isEmpty() ? "none" : p2);
        i.putExtra("dock1Dpi", p1.isEmpty() ? 0 : AppDpiStore.get(prefs, p1));
        i.putExtra("dock2Dpi", p2.isEmpty() ? 0 : AppDpiStore.get(prefs, p2));
        addDockSplitExtras(i, 1, p1, prefs);
        addDockSplitExtras(i, 2, p2, prefs);
        context.sendBroadcast(i);
    }

    static void pushSteering(Context context, SharedPreferences prefs) {
        Intent i = configIntent("ru.big.town.anative.STEER_CONFIG");
        i.putExtra("steerStarShort", resolveSteerAction(prefs.getString("steerStarShort", "none"), prefs));
        i.putExtra("steerStarLong",  resolveSteerAction(prefs.getString("steerStarLong",  "none"), prefs));
        i.putExtra("steerDvrShort",  resolveSteerAction(prefs.getString("steerDvrShort",  "none"), prefs));
        i.putExtra("steerDvrLong",   resolveSteerAction(prefs.getString("steerDvrLong",   "none"), prefs));
        i.putExtra("steerVoiceShort",  resolveSteerAction(prefs.getString("steerVoiceShort",  "none"), prefs));
        i.putExtra("steerVoiceLong",   resolveSteerAction(prefs.getString("steerVoiceLong",   "none"), prefs));
        i.putExtra("steerPhoneShort",  resolveSteerAction(prefs.getString("steerPhoneShort",  "none"), prefs));
        i.putExtra("steerPhoneLong",   resolveSteerAction(prefs.getString("steerPhoneLong",   "none"), prefs));
        context.sendBroadcast(i);
    }

    private static Intent configIntent(String action) {
        Intent i = new Intent(action);
        i.setClassName(NATIVE_PKG, CONFIG_RECEIVER);
        return i;
    }

    private static void addDockSplitExtras(Intent i, int slot, String slotPkg, SharedPreferences prefs) {
        int idx = slotPkg.isEmpty() ? -1 : prefs.getInt("dockOverride" + slot + "Split", -1);
        List<SplitStore.Preset> all = SplitStore.load(prefs);
        if (idx < 0 || idx >= all.size() || !all.get(idx).ready()) {
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
        i.putExtra("dock" + slot + "SplitPresetIdx", idx);       // fallback для старого Native
        i.putExtra("dock" + slot + "SplitPresetId", ps.id);
    }

    /** Backward-compatible CSV: старый Native прочитает первые пять полей, новый — все восемь. */
    static String resolveSteerAction(String id, SharedPreferences prefs) {
        if (id == null || !id.startsWith("split:")) return id;
        try {
            int n = Integer.parseInt(id.substring("split:".length()));
            List<SplitStore.Preset> all = SplitStore.load(prefs);
            if (n >= 0 && n < all.size() && all.get(n).ready()) {
                SplitStore.Preset ps = all.get(n);
                return "split:" + ps.l + "," + ps.r + "," + ps.ratio + ","
                        + AppDpiStore.get(prefs, ps.l) + "," + AppDpiStore.get(prefs, ps.r) + ","
                        + (ps.resizable ? "1" : "0") + "," + SplitStore.leftFraction(ps) + "," + ps.id;
            }
        } catch (Exception ignored) {}
        return "none";
    }
}
