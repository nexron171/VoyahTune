package ru.big.town.restoremode;

import ru.big.town.common.ScenarioProtocol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Adds the actions already available in the UI to the shared built-in phrase catalog. */
final class VoiceCommands {
    static final String ENABLED = "voiceAssistantEnabled";
    static final String START = VoiceSteeringPolicy.ACTION;
    static final int MESSAGE = 36;

    /** Rebuilt only after a settings change or TTL expiry; the returned list is shared and read-only. */
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final VoiceCommandCache<List<VoiceCommandCatalog.Command>> CACHE =
            new VoiceCommandCache<>(TTL_MS, () -> System.nanoTime() / 1_000_000L);
    private static final ExecutorService PRELOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voice-catalog-preload");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    static List<VoiceCommandCatalog.Command> load(Context context) {
        return CACHE.load(() -> Collections.unmodifiableList(build(context.getApplicationContext())));
    }

    /** Warms the shared cache on a background thread so the first visible invocation is fast. */
    static void preload(Context context) {
        final Context app = context.getApplicationContext();
        PRELOADER.execute(() -> load(app));
    }

    /** Settings edits invalidate the cache; the next load rebuilds it. */
    static void invalidate() {
        CACHE.invalidate();
    }

    private static List<VoiceCommandCatalog.Command> build(Context context) {
        List<VoiceCommandCatalog.Command> out = VoiceCommandCatalog.builtIns();
        SharedPreferences prefs = context.getSharedPreferences("DrivePreferences", Context.MODE_PRIVATE);
        for (String[] example : AdvanceActivity.EXAMPLE_COMMANDS) {
            // Forced EV uses the typed controller (off restores the saved energy mode).
            if (example[1].startsWith("форсе")) continue;
            // Now dispatched through the typed OEM API; keep raw examples for other entry points.
            if (example[1].startsWith("обогрев руля ")) continue;
            boolean on = example[1].endsWith(" вкл");
            String name = example[1].replace(" вкл", "").replace(" выкл", "");
            VoiceCommandCatalog.addVehicle(out, "can:" + example[0].replace(" ", ""),
                    name + (on ? ": включить" : ": выключить"),
                    (on ? "включи " : "выключи ") + name,
                    (on ? "включить " : "отключи ") + name);
        }
        Set<String> packages = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = context.getPackageManager().queryIntentActivities(launcher, 0);
        // An updated preinstalled app remains a system app, even when its APK is in /data.
        apps.removeIf(app -> app.activityInfo == null || app.activityInfo.applicationInfo == null
                || (app.activityInfo.applicationInfo.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
        apps.sort((a, b) -> a.loadLabel(context.getPackageManager()).toString()
                .compareToIgnoreCase(b.loadLabel(context.getPackageManager()).toString()));
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            if (!packages.add(pkg) || pkg.equals(context.getPackageName())) continue;
            String name = app.loadLabel(context.getPackageManager()).toString();
            VoiceCommandCatalog.add(out, "app:" + pkg, "Открыть приложение: " + name,
                    "открой " + name, "запусти " + name, "открой приложение " + name);
        }
        List<SplitStore.Preset> splits = SplitStore.load(prefs);
        for (int i = 0; i < splits.size(); i++) {
            SplitStore.Preset split = splits.get(i);
            if (!split.ready()) continue;
            VoiceCommandCatalog.add(out, SplitConfigSync.resolveSteerAction("split:" + i, prefs),
                    "Сплит " + (i + 1) + ": " + split.ll + " / " + split.rl,
                    "открой сплит " + number(i + 1), "запусти сплит " + number(i + 1));
        }

        for (DialWidgetStore.Entry entry : DialWidgetStore.load(prefs)) {
            String number = entry.number.replaceAll("[^0-9]", "");
            if (number.length() < 4 || number.length() > 10) continue;
            if (number.length() == 10) number = "8" + number;
            if (!entry.name.trim().isEmpty()) VoiceCommandCatalog.add(out, "call:" + number,
                    "Набрать номер: " + entry.name, "позвони " + entry.name, "набери " + entry.name);
        }
        // Включённые сценарии запускаются по имени; имена с запрещёнными словами пропускаются,
        // их можно вызвать плиткой или из настроек.
        for (ScenarioStore.Scenario scenario : ScenarioStore.load(prefs)) {
            if (!scenario.enabled || scenario.name.trim().isEmpty()) continue;
            if (!VoiceCommandCatalog.phraseUsable(scenario.name)) continue;
            VoiceCommandCatalog.add(out, ScenarioProtocol.ACTION_RUN_PREFIX + scenario.id,
                    "Сценарий: " + scenario.name,
                    "запусти сценарий " + scenario.name,
                    "сценарий " + scenario.name,
                    "выполни сценарий " + scenario.name);
        }
        Set<String> custom = new java.util.LinkedHashSet<>();
        for (String button : new String[]{"Star", "Dvr", "Voice", "Phone"}) {
            for (String press : new String[]{"Short", "Long"}) {
                for (String action : SteeringActionStore.load(prefs, "steer" + button + press)) {
                    if (action.startsWith("can:")) custom.add(action);
                }
            }
        }
        for (String line : prefs.getString("customCommand", "").split("\\n")) {
            String hex = SteeringCanCommandPolicy.compact(line);
            if (hex.length() == SteeringCanCommandPolicy.HEX_LENGTH) custom.add("can:" + hex);
        }
        int index = 0;
        for (String action : custom) out.add(new VoiceCommandCatalog.Command(action,
                "Своя CAN-команда " + (++index) + ": " + action.substring(4), true,
                "выполни команду " + number(index), "своя команда " + number(index)));

        return out;
    }

    private static String number(int n) {
        String[] words = {"ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять"};
        return n < words.length ? words[n] : Integer.toString(n);
    }
}
