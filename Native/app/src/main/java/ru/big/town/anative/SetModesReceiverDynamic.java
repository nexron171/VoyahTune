package ru.big.town.anative;

import static ru.big.town.anative.SetModesService.MSG_APPLY_DRIVE_MODES_STAR_BUTTON;
import static ru.big.town.anative.SetModesService.STATE_SHUTDOWN_PREPARE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

public class SetModesReceiverDynamic extends BroadcastReceiver {
    public static volatile int repeat = 7;
    public static volatile boolean isButton = false;
    static final String TAG = "$$$ SetModesReceiverDynamic $$$";
    private final Runnable sleepCallback;
    private final Runnable wakeCallback;

    /** Нужен framework для manifest-declared explicit bridge от launcher/steering hooks. */
    public SetModesReceiverDynamic() {
        this(null, null);
    }

    /** Экземпляр, который SetModesService регистрирует для системных screen broadcasts. */
    SetModesReceiverDynamic(Runnable sleepCallback, Runnable wakeCallback) {
        this.sleepCallback = sleepCallback;
        this.wakeCallback = wakeCallback;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String receivedIntent = intent.getAction();
        boolean explicitComponent = intent.getComponent() != null;

        Log.i(TAG, "onReceive DYN enter by intent" + receivedIntent);

        // Это системный implicit-broadcast. Явный вызов экспортированного компонента не должен
        // превращать Native в публичную кнопку изменения режима автомобиля.
        if ("android.intent.action.KEYCODE_SWC_USER_DEFINE".equals(receivedIntent)
                && intent.getComponent() == null) {
            Log.i(TAG, "android.intent.action.KEYCODE_SWC_USER_DEFINE");
            Log.i(TAG, "GlobalVars.buttonDriveMode: " +
                    GlobalVars.buttonDriveMode);
            //MainActivity.setCanValues(1, MainActivity.getCustomCommandOff());
            switch (GlobalVars.buttonDriveMode){
                case 1:
                    SetModesService.worker(1, 200, MSG_APPLY_DRIVE_MODES_STAR_BUTTON,1);
                    GlobalVars.buttonDriveMode=2;
                    break;
                case 2:
                    SetModesService.worker(1, 200, MSG_APPLY_DRIVE_MODES_STAR_BUTTON,2);
                    GlobalVars.buttonDriveMode=1;
                    break;

            }

        }

        // Одиночное приложение из дока открываем обычной задачей целевого пакета на физическом дисплее.
        // Возврат из медиакарточки восстанавливает OEM-карточку и учитывает экран нажатия. Только full.
        if ("ru.big.town.anative.OPEN_FREEFORM".equals(receivedIntent) && BuildConfig.IS_FULL) {
            // Accept only configured dock packages and the two physical application screens.
            String pkg = intent.getStringExtra("pkg");
            int displayId = intent.getIntExtra("display", 0);
            if (displayId != 0 && displayId != 1) {
                Log.w(TAG, "OPEN_FREEFORM отклонён: неверный physical display " + displayId);
            } else if (isConfiguredDockPackage(context, pkg)) {
                openFreeformApp(context, pkg, displayId);
            } else {
                Log.w(TAG, "OPEN_FREEFORM отклонён: пакет не назначен доку: " + pkg);
            }
        }

        // Плитка «Быстрый запуск»: открыть приложение на выбранном физическом дисплее.
        // 0 — водительский экран, 1 — пассажирский. Как и OPEN_FREEFORM, только в full-сборке.
        if ("ru.big.town.anative.OPEN_ON_DISPLAY".equals(receivedIntent) && BuildConfig.IS_FULL) {
            String pkg = intent.getStringExtra("pkg");
            int displayId = intent.getIntExtra("display", 0);
            if (displayId != 0 && displayId != 1) {
                Log.w(TAG, "OPEN_ON_DISPLAY отклонён: неверный physical display " + displayId);
            } else if (pkg == null || pkg.isEmpty()) {
                Log.w(TAG, "OPEN_ON_DISPLAY отклонён: пустой пакет");
            } else {
                openFreeformApp(context, pkg, displayId);
            }
        }

        // Launcher hook routes an allowlisted All Apps tile here so ActivityOptions can normalize a
        // reused freeform task before the activity is resumed. The exported bridge accepts only the
        // exact package persisted by the protected fullscreen config receiver.
        if ("ru.big.town.anative.OPEN_FULLSCREEN".equals(receivedIntent) && BuildConfig.IS_FULL) {
            String pkg = intent.getStringExtra("pkg");
            int displayId = intent.getIntExtra("display", 0);
            if (displayId != 0 && displayId != 1) {
                Log.w(TAG, "OPEN_FULLSCREEN отклонён: неверный physical display " + displayId);
            } else if (isConfiguredFullscreenPackage(context, pkg)) {
                openFreeformApp(context, pkg, displayId);
            } else {
                Log.w(TAG, "OPEN_FULLSCREEN отклонён: пакет не в fullscreen-списке: " + pkg);
            }
        }

        // Long press resolves only protected slot config: split or the slot app in the cluster.
        // Keep the legacy action for an older launcher hook during upgrades.
        if (("ru.big.town.anative.OPEN_DOCK_SPLIT".equals(receivedIntent)
                || "ru.big.town.anative.OPEN_DOCK_LONG_PRESS".equals(receivedIntent)) && BuildConfig.IS_FULL) {
            int slot = intent.getIntExtra("slot", 0);
            if (slot == 1 || slot == 2) {
                android.content.ContentResolver cr = context.getContentResolver();
                String action = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "LongAction");
                String has = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "HasSplit");
                if ("cluster".equals(action)) {
                    String pkg = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot);
                    ClusterMediaHostActivity.launch(context, pkg);
                } else if ((action == null || "split".equals(action)) && "1".equals(has)) {
                    String l = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitL");
                    String r = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitR");
                    int ratio = parseIntSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitRatio"), 1);
                    int lDpi  = parseIntSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitLDpi"), 0);
                    int rDpi  = parseIntSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitRDpi"), 0);
                    boolean rsz = "1".equals(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitResizable"));
                    float frac = parseFloatSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitFraction"), 0f);
                    int pIdx = parseIntSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitPresetIdx"), -1);
                    String pId = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitPresetId");
                    ClusterMediaHostActivity.closeForPackage(l);
                    ClusterMediaHostActivity.closeForPackage(r);
                    SplitHostActivity.launchSplit(context.getApplicationContext(), l, r, ratio, lDpi, rDpi,
                            rsz, frac, pIdx, pId);
                    Log.i(TAG, "OPEN_DOCK_SPLIT slot=" + slot + " " + l + "/" + r + " ratio=" + ratio);
                } else {
                    Log.i(TAG, "OPEN_DOCK_SPLIT slot=" + slot + " — сплит не назначен");
                }
            }
        }

        // Исполнение назначенного действия кнопки руля. Только full.
        if ("ru.big.town.anative.STEER_ACTION".equals(receivedIntent) && BuildConfig.IS_FULL) {
            String action = intent.getStringExtra("action");
            if (isConfiguredSteerAction(context, action)) handleSteerActions(context, action);
            else Log.w(TAG, "STEER_ACTION отклонён: действие не настроено: " + action);
        }
//        if (receivedIntent.equals("ru.big.town.anative.APPLY_DRIVE_MODES")) {
//            repeat = 3;
//            isButton = true;
//        } else {
//            repeat = 7;
//            isButton = false;
//        }
        // SCREEN_OFF приходит раньше suspend/wake CAN-эхо и закрывает sync заранее. Это страховка
        // для прошивок, где CarPowerListener периодически пропускает SUSPEND_ENTER.
        if (Intent.ACTION_SCREEN_OFF.equals(receivedIntent) && !explicitComponent) {
            ApplyEngine.resetRestoreGate("SCREEN_OFF");
            if (sleepCallback != null) sleepCallback.run();
            Log.i(TAG, "onReceive SCREEN_OFF — mode sync gate reset");
        }

        // Fallback-триггер пробуждения через броадкасты. Держим его активным всегда (даже если
        // power-listener работает): при рестарте CarService слушатель может «протухнуть», а этот
        // путь остаётся. Режимы восстанавливаются отдельно по двери и Drive.
        if (!explicitComponent && (Intent.ACTION_SCREEN_ON.equals(receivedIntent) ||
                "com.android.server.jobscheduler.GARAGE_MODE_OFF".equals(receivedIntent))) {
            Log.i(TAG, "onReceive ACTION_SCREEN_ON or GARAGE_MODE_OFF");
            ApplyEngine.activateWake(receivedIntent);
            if (Intent.ACTION_SCREEN_ON.equals(receivedIntent) && wakeCallback != null) {
                wakeCallback.run();
            }
        }

        if (explicitComponent && (Intent.ACTION_SCREEN_ON.equals(receivedIntent)
                || Intent.ACTION_SCREEN_OFF.equals(receivedIntent)
                || "com.android.server.jobscheduler.GARAGE_MODE_OFF".equals(receivedIntent))) {
            Log.w(TAG, "ignored explicit power broadcast: " + receivedIntent);
        }

                //throw new UnsupportedOperationException("Not yet implemented");
        if (isOrderedBroadcast()) {
            setResultCode(-1);
        }
    }

    /** Записать выбор действия слота в Settings.Global под ключом voyahtune_<slot> (нужен WRITE_SECURE_SETTINGS). */
    static void mirrorSteer(Context ctx, Intent intent, String key) {
        String v = intent.getStringExtra(key);
        if (v == null) v = "none";
        try {
            android.provider.Settings.Global.putString(ctx.getContentResolver(), "voyahtune_" + key, v);
        } catch (Exception e) {
            Log.w(TAG, "mirrorSteer " + key + ": " + e.getMessage());
        }
    }

    /** Записать выбор слота дока в Settings.Global: voyahtune_dock&lt;slot&gt; (pkg) + voyahtune_dock&lt;slot&gt;Dpi (int).
     *  Нужен WRITE_SECURE_SETTINGS (уже в privapp-whitelist, раз mirrorSteer работает). Читает launcherdock.js. */
    static void mirrorDock(Context ctx, Intent intent, int slot) {
        String pkg = intent.getStringExtra("dock" + slot);
        if (pkg == null || pkg.isEmpty()) pkg = "none";
        int dpi = intent.getIntExtra("dock" + slot + "Dpi", 0);
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String previousPkg = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot);
            if (previousPkg != null && !previousPkg.equals(pkg)) {
                ClusterMediaHostActivity.closeForPackage(previousPkg);
            }
            android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot, pkg);
            android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "Dpi", String.valueOf(dpi));
            // Per-package DPI остаётся для VD split-панелей: 0 тоже обязательно зеркалируем. Иначе
            // после выбора «Авто» в Settings.Global навсегда оставалось старое ненулевое значение.
            if (!"none".equals(pkg)) {
                android.provider.Settings.Global.putString(cr, "voyahtune_dpi_" + pkg, String.valueOf(dpi));
            }
            // Сплит, открываемый долгим нажатием на слот дока. Флаг HasSplit читает launcherdock.js
            // (гейт долгого тапа), детали (L/R/Ratio/Dpi) — обработчик OPEN_DOCK_SPLIT ниже.
            boolean hasSplit = intent.getBooleanExtra("dock" + slot + "HasSplit", false);
            String action = intent.getStringExtra("dock" + slot + "LongAction");
            if (action == null) action = hasSplit ? "split" : "none";
            if ("none".equals(pkg) || !("split".equals(action) || "cluster".equals(action))) action = "none";
            hasSplit = hasSplit && "split".equals(action);
            android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "LongAction", action);
            android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "HasSplit", hasSplit ? "1" : "0");
            if (hasSplit) {
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitL", nz(intent.getStringExtra("dock" + slot + "SplitL")));
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitR", nz(intent.getStringExtra("dock" + slot + "SplitR")));
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitRatio", String.valueOf(intent.getIntExtra("dock" + slot + "SplitRatio", 1)));
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitLDpi", String.valueOf(intent.getIntExtra("dock" + slot + "SplitLDpi", 0)));
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitRDpi", String.valueOf(intent.getIntExtra("dock" + slot + "SplitRDpi", 0)));
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitResizable",
                        intent.getBooleanExtra("dock" + slot + "SplitResizable", false) ? "1" : "0");
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitFraction",
                        String.valueOf(intent.getFloatExtra("dock" + slot + "SplitFraction", 0f)));
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitPresetIdx",
                        String.valueOf(intent.getIntExtra("dock" + slot + "SplitPresetIdx", -1)));
                android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "SplitPresetId",
                        nz(intent.getStringExtra("dock" + slot + "SplitPresetId")));
            }
        } catch (Exception e) {
            Log.w(TAG, "mirrorDock " + slot + ": " + e.getMessage());
        }
    }

    /**
     * Миграция со сборок, где пассажирские Air/Seat ошибочно предлагались как настраиваемые слоты.
     * Выполняется при каждом DOCK_CONFIG, чтобы уже записанное на ГУ значение не продолжало влиять
     * на eternalized launcher hook после обновления без очистки данных приложений.
     */
    static void clearLegacyPassengerDock(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.provider.Settings.Global.putString(cr, "voyahtune_dockPassenger1", "none");
            android.provider.Settings.Global.putString(cr, "voyahtune_dockPassenger2", "none");
            android.provider.Settings.Global.putString(cr, "voyahtune_dockPassenger1Dpi", "0");
            android.provider.Settings.Global.putString(cr, "voyahtune_dockPassenger2Dpi", "0");
        } catch (Exception e) {
            Log.w(TAG, "clearLegacyPassengerDock: " + e.getMessage());
        }
    }

    /** Полный event-driven снимок per-app DPI. Никакого polling: вызывается при изменении и startup/wake. */
    static void mirrorAppDpi(Context ctx, Intent intent) {
        String json = intent.getStringExtra("appDpiJson");
        if (json == null) return;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            org.json.JSONObject values = new org.json.JSONObject(json);
            java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>();
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                if (!validPackageName(pkg)) continue;
                int dpi = sanitizeDpi(values.optInt(pkg, 0));
                if (dpi <= 0) continue;
                android.provider.Settings.Global.putString(cr, "voyahtune_dpi_" + pkg,
                        String.valueOf(dpi));
                next.add(pkg);
            }

            String previous = android.provider.Settings.Global.getString(cr,
                    "voyahtune_dpi_packages");
            if (previous != null && !previous.isEmpty()) {
                for (String pkg : previous.split(",")) {
                    if (validPackageName(pkg) && !next.contains(pkg)) {
                        android.provider.Settings.Global.putString(cr, "voyahtune_dpi_" + pkg, "0");
                    }
                }
            }

            // Explicit delta closes the first-migration hole when the user changes a previously
            // unindexed package to «Авто» and the authoritative JSON no longer contains that key.
            String changedPkg = intent.getStringExtra("changedPkg");
            if (validPackageName(changedPkg)) {
                int changedDpi = sanitizeDpi(intent.getIntExtra("changedDpi", 0));
                android.provider.Settings.Global.putString(cr, "voyahtune_dpi_" + changedPkg,
                        String.valueOf(changedDpi));
                if (changedDpi > 0) next.add(changedPkg); else next.remove(changedPkg);
            }
            android.provider.Settings.Global.putString(cr, "voyahtune_dpi_packages",
                    android.text.TextUtils.join(",", next));
        } catch (Exception e) {
            Log.w(TAG, "mirrorAppDpi: " + e.getMessage());
        }
    }

    /** Launch-time fallback for an app tile if the earlier config broadcast was missed. */
    static boolean ensureAppDpi(Context ctx, String pkg, int dpi) {
        if (!validPackageName(pkg)) return false;
        dpi = sanitizeDpi(dpi);
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String key = "voyahtune_dpi_" + pkg;
            String wanted = String.valueOf(dpi);
            String current = android.provider.Settings.Global.getString(cr, key);
            if (wanted.equals(current)) return false;
            android.provider.Settings.Global.putString(cr, key, wanted);
            sendWinReload(ctx);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ensureAppDpi " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean validPackageName(String pkg) {
        return pkg != null && !pkg.isEmpty() && pkg.matches("[A-Za-z0-9_.]+") && pkg.indexOf('.') > 0;
    }

    private static int sanitizeDpi(int dpi) {
        return dpi >= 100 && dpi <= 640 ? dpi : 0;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static int parseIntSafe(String s, int def) {
        try { return (s == null || s.isEmpty()) ? def : Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }

    private static float parseFloatSafe(String s, float def) {
        try { return (s == null || s.isEmpty()) ? def : Float.parseFloat(s.trim()); }
        catch (Exception e) { return def; }
    }

    /** Публичный launcher bridge принимает только пакет, уже записанный защищённым config-receiver. */
    private static boolean isConfiguredDockPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        android.content.ContentResolver cr = ctx.getContentResolver();
        return pkg.equals(android.provider.Settings.Global.getString(cr, "voyahtune_dock1"))
                || pkg.equals(android.provider.Settings.Global.getString(cr, "voyahtune_dock2"));
    }

    /** STEER_ACTION должен совпадать с одним из значений, зеркалированных из подписанного RestoreMode. */
    private static boolean isConfiguredSteerAction(Context ctx, String action) {
        if (action == null || action.isEmpty() || "none".equals(action)) return false;
        android.content.ContentResolver cr = ctx.getContentResolver();
        String[] buttons = {"Star", "Dvr", "Voice", "Phone"};
        for (String button : buttons) {
            if (action.equals(android.provider.Settings.Global.getString(cr,
                    "voyahtune_steer" + button + "Short"))) return true;
            if (action.equals(android.provider.Settings.Global.getString(cr,
                    "voyahtune_steer" + button + "Long"))) return true;
        }
        return false;
    }

    /** Публичный launcher bridge принимает только пакет из защищённого fullscreen snapshot. */
    private static boolean isConfiguredFullscreenPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            String csv = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), "voyahtune_fullscreen_apps");
            return FullscreenPackagePolicy.contains(csv, pkg);
        } catch (Exception e) {
            Log.w(TAG, "fullscreen allowlist unavailable: " + e.getMessage());
            return false;
        }
    }

    /** Флаг + bounds физического «оконного режима» → Settings.Global.
     *  Два system_server hook читают их в кэш только при attach/WIN_RELOAD, не на каждом layout.
     *  extras: on(boolean, опц.), left/top/right/bottom(int, опц., пишем только >=0). */
    static void mirrorFreeform(Context ctx, Intent intent) {
        try {
            if (intent.hasExtra("on")) {
                android.provider.Settings.Global.putString(ctx.getContentResolver(),
                        "voyahtune_freeform", intent.getBooleanExtra("on", false) ? "1" : "0");
            }
            int[] v = { intent.getIntExtra("left", -1), intent.getIntExtra("top", -1),
                        intent.getIntExtra("right", -1), intent.getIntExtra("bottom", -1) };
            String[] k = { "voyahtune_win_left", "voyahtune_win_top", "voyahtune_win_right", "voyahtune_win_bottom" };
            for (int i = 0; i < 4; i++) {
                if (v[i] >= 0) android.provider.Settings.Global.putString(ctx.getContentResolver(), k[i], String.valueOf(v[i]));
            }
        } catch (Exception e) { Log.w(TAG, "mirrorFreeform: " + e.getMessage()); }
    }

    /**
     * Авторитетный список пакетов, которые обходят physical window clamp. Один и тот же CSV читают
     * system_server/launcher hooks, а NativePrefs держит accessibility-сервис для forced Назад/Home.
     */
    static void mirrorFullscreenApps(Context ctx, Intent intent) {
        String packages = FullscreenPackagePolicy.normalizeCsv(intent.getStringExtra("packagesCsv"));
        try {
            android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), "voyahtune_fullscreen_apps", packages);
            BackButtonService.setFullscreenPackages(ctx, packages);
        } catch (Exception e) {
            Log.w(TAG, "mirrorFullscreenApps: " + e.getMessage());
        }
    }

    /** Разбудить vd_bypass config receiver: перечитать кэш и переустановить WindowManager hooks.
     *  Receiver гейтится WRITE_SECURE_SETTINGS. */
    static void sendWinReload(Context ctx) {
        try {
            Intent w = new Intent("ru.big.town.anative.WIN_RELOAD");
            w.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(w);
        } catch (Exception e) { Log.w(TAG, "sendWinReload: " + e.getMessage()); }
    }

    private static final Handler STEER_SEQUENCE_HANDLER = new Handler(Looper.getMainLooper());

    /** Декодирует назначение и запускает каждый пункт только после terminal callback предыдущего. */
    private static void handleSteerActions(Context ctx, String configured) {
        List<String> actions = SteeringActionSequence.decode(configured);
        if (actions.isEmpty()) return;
        Log.i(TAG, "STEER_ACTION sequence start, count=" + actions.size());
        runSteerAction(ctx.getApplicationContext(), actions, 0);
    }

    private static void runSteerAction(Context ctx, List<String> actions, int index) {
        if (index >= actions.size()) {
            Log.i(TAG, "STEER_ACTION sequence complete, count=" + actions.size());
            return;
        }
        String action = actions.get(index);
        Log.i(TAG, "STEER_ACTION sequence " + (index + 1) + "/" + actions.size() + ": " + action);
        handleSteerAction(ctx, action, () -> STEER_SEQUENCE_HANDLER.post(
                () -> runSteerAction(ctx, actions, index + 1)));
    }

    /**
     * Один пункт последовательности. Асинхронные CAN-действия вызывают completion через exactly-once
     * terminal callback ApplyEngine; синхронные действия завершаются сразу после вызова API.
     */
    static void handleSteerAction(Context ctx, String action, Runnable completion) {
        if (action == null || action.isEmpty()) {
            completeSteerAction(completion);
            return;
        }
        if (action.startsWith("energy:")) {
            cycleMode(ctx, action.substring("energy:".length()), "energy", completion);
        } else if (action.startsWith("drive:")) {
            cycleMode(ctx, action.substring("drive:".length()), "driveMode", completion);
        } else if (action.startsWith("recycle:")) {
            cycleMode(ctx, action.substring("recycle:".length()), "recycle", completion);
        } else if ("toggle_forced_ev".equals(action)) {
            toggleSetting(ctx, "forcedEv", completion);
        } else if ("toggle_pedestrian_sound".equals(action)) {
            toggleSetting(ctx, "disablePedestrianSound", completion);
        } else if ("toggle_headlights".equals(action)) {
            toggleHeadlights(ctx, completion);
        } else if ("toggle_headlights_auto".equals(action)) {
            toggleHeadlightsAuto(ctx, completion);
        } else if (action.startsWith("can:")) {
            sendCustomCan(action, completion);
        } else {
            try {
                if ("voice_assistant".equals(action)) {
                    Intent voice = new Intent().setClassName("ru.big.town.restoremode", "ru.big.town.restoremode.VoiceActivity");
                    voice.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(0);
                    android.os.Bundle voiceOptions = options.toBundle();
                    voiceOptions.putInt("android.activity.windowingMode", 1);
                    ctx.startActivity(voice, voiceOptions);
                } else if ("system_back".equals(action)) {
                    BackButtonService.performBack(ctx);
                } else if (action.startsWith("app:")) {
                    // Открыть отдельное приложение (freeform-окно на display 0), закрыв активный сплит.
                    openFreeformApp(ctx, action.substring("app:".length()));
                    Log.i(TAG, "STEER_ACTION → приложение " + action.substring("app:".length()));
                } else if (action.startsWith("split:")) {
                    launchSteerSplit(ctx, action);
                } else if (action.startsWith("call:")) {
                    String number = action.substring("call:".length());
                    Intent call = new Intent("com.qinggan.broadcast.action.callfromcard");
                    call.putExtra("dial_number", number);
                    ctx.sendBroadcast(call);
                    Log.i(TAG, "STEER_ACTION → вызов номера " + number);
                } else if ("open_voyahtune".equals(action)) {
                    openVoyahTune(ctx);
                } else {
                    Log.i(TAG, "STEER_ACTION неизвестно: " + action);
                }
            } finally {
                completeSteerAction(completion);
            }
        }
    }

    private static void launchSteerSplit(Context ctx, String action) {
        // Backward-compatible строка: split:<L>,<R>,<ratio>,<lDpi>,<rDpi>[,<resizable>,<fraction>,<presetId>].
        String[] p = action.substring("split:".length()).split(",");
        if (p.length >= 3) {
            try {
                int ratio = Integer.parseInt(p[2].trim());
                int lDpi = p.length > 3 ? Integer.parseInt(p[3].trim()) : 0;
                int rDpi = p.length > 4 ? Integer.parseInt(p[4].trim()) : 0;
                boolean resizable = p.length > 5 && "1".equals(p[5].trim());
                float fraction = p.length > 6 ? parseFloatSafe(p[6], 0f) : 0f;
                String presetId = p.length > 7 ? p[7].trim() : "";
                SplitHostActivity.launchSplit(ctx.getApplicationContext(), p[0].trim(), p[1].trim(),
                        ratio, lDpi, rDpi, resizable, fraction, -1, presetId);
                Log.i(TAG, "STEER_ACTION → сплит " + p[0] + "/" + p[1] + " ratio=" + ratio);
            } catch (Exception e) {
                Log.w(TAG, "STEER_ACTION split parse: " + e.getMessage());
            }
        }
    }

    private static void openVoyahTune(Context ctx) {
        try {
            Intent i = new Intent();
            i.setClassName("ru.big.town.restoremode", "ru.big.town.restoremode.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            DockLaunchGuard.arm(ctx, 0, "ru.big.town.restoremode");
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setLaunchDisplayId(0);
            ctx.startActivity(i, o.toBundle());
            Log.i(TAG, "STEER_ACTION → открыть VoyahTune");
        } catch (Exception e) {
            Log.w(TAG, "open VoyahTune failed: " + e.getMessage());
        }
    }

    private static void sendCustomCan(String action, Runnable completion) {
        byte[] frame = SteeringActionSequence.parseCustomCan(action);
        if (frame == null) {
            Log.w(TAG, "STEER_ACTION custom CAN отклонён: " + action);
            completeSteerAction(completion);
            return;
        }
        ApplyEngine.postUserCommand("steer custom CAN", () -> {
            boolean sent = MainActivity.setCanValues(1, new byte[][] {frame}, "steering custom CAN");
            Log.i(TAG, "STEER_ACTION custom CAN: " + (sent ? "sent" : "failed"));
        }, completion);
    }

    private static void completeSteerAction(Runnable completion) {
        if (completion != null) completion.run();
    }

    /** Открыть приложение обычной задачей на физическом экране; vd_bypass.js ужмёт рамку окна. */
    static void openFreeformApp(Context context, String pkg) {
        openFreeformApp(context, pkg, 0);
    }

    /** Always honor the clicked physical display, including when returning from the cluster. */
    static void openFreeformApp(Context context, String pkg, int displayId) {
        if (pkg == null || pkg.isEmpty() || (displayId != 0 && displayId != 1)) return;
        Context app = context.getApplicationContext();
        ClusterMediaHostActivity.closeForPackage(pkg);
        SplitHostActivity.closeActiveHost();
        AppDisplayLauncher.launch(app, pkg, displayId,
                isConfiguredFullscreenPackage(app, pkg), () -> true,
                () -> android.widget.Toast.makeText(app, "Не удалось открыть приложение",
                        android.widget.Toast.LENGTH_LONG).show());
    }

    /** Cycle the vehicle state; only remember-last opt-in updates the saved UI selection. */
    private static void cycleMode(Context ctx, String csv, String modeKey, Runnable completion) {
        final Context app = ctx.getApplicationContext();
        // Пользовательский выбор должен идти ПОСЛЕ уже запущенного wake-restore, а не параллельно с ним:
        // иначе restore успевал отправить старый snapshot поверх только что выбранного режима.
        ApplyEngine.postUserCommand("steer " + modeKey, () -> {
            String cur = MainActivity.currentVehicleMode(app, modeKey);
            String next = SteeringActionPolicy.nextMode(csv, cur);
            if (next == null) return;
            boolean sent = "driveMode".equals(modeKey)
                    ? MainActivity.sendDriveModeCommand(app, next)
                    : "energy".equals(modeKey)
                            ? MainActivity.sendEnergyModeCommand(app, next)
                            : MainActivity.sendRecuperationModeCommand(app, next);
            if (!sent) {
                Log.w(TAG, "STEER_ACTION " + modeKey + ": CAN failed, selection not persisted");
                return;
            }
            ApplyEngine.noteVehicleMode(modeKey, next);
            MainActivity.persistSavedMode(app, modeKey, next);
            Log.i(TAG, "STEER_ACTION " + modeKey + ": набор=" + csv
                    + " тек=" + cur + " → " + next);
        }, completion);
    }

    /** Переключить бинарную настройку относительно сохранённого значения, применить CAN и сохранить новый state. */
    private static void toggleSetting(Context ctx, String key, Runnable completion) {
        final Context app = ctx.getApplicationContext();
        ApplyEngine.postUserCommand("steer " + key, () -> {
            boolean current = MainActivity.currentSavedToggle(app, key);
            boolean next = !current;
            boolean sent;
            if ("forcedEv".equals(key)) {
                sent = MainActivity.sendForcedEvCommand(next);
            } else if ("disablePedestrianSound".equals(key)) {
                // В pref хранится инвертированная семантика: true = звук выключен.
                sent = MainActivity.sendPedestrianSoundCommand(next);
            } else {
                return;
            }
            if (!sent) {
                Log.w(TAG, "STEER_ACTION " + key + ": CAN failed, toggle not persisted");
                return;
            }
            MainActivity.persistSavedToggle(app, key, next);
            Log.i(TAG, "STEER_ACTION " + key + ": " + current + " → " + next);
        }, completion);
    }

    /** Переключить фары теми же CAN-командами, которые использует автоматический свет. */
    private static void toggleHeadlights(Context ctx, Runnable completion) {
        final Context app = ctx.getApplicationContext();
        final ManualAutoGate.Ticket manualTicket =
                LightSensorService.reserveManualHeadlightCommand();
        ApplyEngine.postUserCommand("steer headlights", () -> {
            android.content.SharedPreferences prefs =
                    app.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
            boolean current = prefs.getBoolean("steerHeadlightsOn", false);
            boolean next = !current;
            boolean previousManualAuto = LightSensorService.setManualAutoOverride(false);
            if (!MainActivity.setHeadlights(app, next)) {
                LightSensorService.setManualAutoOverride(previousManualAuto);
                Log.w(TAG, "STEER_ACTION headlights: CAN failed, state not persisted");
                return;
            }
            prefs.edit().putBoolean("steerHeadlightsOn", next).apply();
            Log.i(TAG, "STEER_ACTION headlights: " + current + " → " + next);
        }, () -> {
            manualTicket.close();
            completeSteerAction(completion);
        });
    }

    /** Независимая пара для руля: штатный Auto ↔ ручной ближний свет. */
    private static void toggleHeadlightsAuto(Context ctx, Runnable completion) {
        final Context app = ctx.getApplicationContext();
        final ManualAutoGate.Ticket manualTicket =
                LightSensorService.reserveManualHeadlightCommand();
        ApplyEngine.postUserCommand("steer headlights auto/low", () -> {
            android.content.SharedPreferences prefs =
                    app.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
            boolean currentLowBeam = prefs.getBoolean("steerHeadlightsAutoLowBeam", false);
            boolean nextLowBeam = !currentLowBeam;
            boolean previousManualAuto =
                    LightSensorService.setManualAutoOverride(!nextLowBeam);
            if (!MainActivity.setHeadlightsAutoLow(app, nextLowBeam)) {
                LightSensorService.setManualAutoOverride(previousManualAuto);
                Log.w(TAG, "STEER_ACTION headlights auto/low: CAN failed, state not persisted");
                return;
            }
            prefs.edit().putBoolean("steerHeadlightsAutoLowBeam", nextLowBeam).apply();
            Log.i(TAG, "STEER_ACTION headlights auto/low: "
                    + (currentLowBeam ? "LOW_BEAM" : "AUTO") + " → "
                    + (nextLowBeam ? "LOW_BEAM" : "AUTO"));
        }, () -> {
            manualTicket.close();
            completeSteerAction(completion);
        });
    }

}
