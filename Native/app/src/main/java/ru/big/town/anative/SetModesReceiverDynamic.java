package ru.big.town.anative;

import static ru.big.town.anative.SetModesService.MSG_APPLY_DRIVE_MODES_STAR_BUTTON;
import static ru.big.town.anative.SetModesService.STATE_SHUTDOWN_PREPARE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

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

        // NB: одиночный запуск приложения из слота дока БОЛЬШЕ не идёт на VD (был broadcast LAUNCH_ON_VD →
        // SplitHostActivity одиночным окном). Теперь одиночное стороннее приложение открывается freeform-окном
        // на display 0 (launcherdock.js → обычный launch-интент, системный хук vd_bypass ужимает окно). VD
        // остаётся ТОЛЬКО под сплит ДВУХ приложений (запускается из SetModesService по пресетам). Мёртвый
        // обработчик LAUNCH_ON_VD удалён.

        // Открытие приложения из дока во freeform (launcherdock делегирует СЮДА, чтобы мы закрыли активный
        // VD-сплит и запустили приложение ЧИСТО на display 0). Иначе приложение-панель «уехало» бы с VD с
        // глитчем (чёрное окно). closeActiveSplit force-stop'ит панели → приложение стартует заново; если
        // сплит был — запускаем с задержкой (teardown асинхронный), иначе сразу. Только full.
        if ("ru.big.town.anative.OPEN_FREEFORM".equals(receivedIntent) && BuildConfig.IS_FULL) {
            // display: на каком экране открыть. Отсутствует → 0 (водительский), т.е. прежнее поведение.
            String pkg = intent.getStringExtra("pkg");
            if (isConfiguredDockPackage(context, pkg)) {
                openFreeformApp(context, pkg, intent.getIntExtra("display", 0));
            } else {
                Log.w(TAG, "OPEN_FREEFORM отклонён: пакет не назначен доку: " + pkg);
            }
        }

        // Открытие СПЛИТА, назначенного слоту дока, по долгому нажатию (launcherdock.js шлёт номер слота).
        // Детали сплита читаем из Settings.Global — их зеркалит mirrorDock из DOCK_CONFIG (VoyahTune).
        // SplitHostActivity.launchSplit уходит на VD (обычный движок сплита); коллизии панелей он гасит сам.
        if ("ru.big.town.anative.OPEN_DOCK_SPLIT".equals(receivedIntent) && BuildConfig.IS_FULL) {
            int slot = intent.getIntExtra("slot", 0);
            if (slot == 1 || slot == 2) {
                android.content.ContentResolver cr = context.getContentResolver();
                String has = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "HasSplit");
                if ("1".equals(has)) {
                    String l = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitL");
                    String r = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitR");
                    int ratio = parseIntSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitRatio"), 1);
                    int lDpi  = parseIntSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitLDpi"), 0);
                    int rDpi  = parseIntSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitRDpi"), 0);
                    boolean rsz = "1".equals(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitResizable"));
                    float frac = parseFloatSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitFraction"), 0f);
                    int pIdx = parseIntSafe(android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitPresetIdx"), -1);
                    String pId = android.provider.Settings.Global.getString(cr, "voyahtune_dock" + slot + "SplitPresetId");
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
            if (isConfiguredSteerAction(context, action)) handleSteerAction(context, action);
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
        // путь остаётся. Возможные дубли с power-listener гасит дебаунс в ApplyEngine.
        if (!explicitComponent && (Intent.ACTION_SCREEN_ON.equals(receivedIntent) ||
                "com.android.server.jobscheduler.GARAGE_MODE_OFF".equals(receivedIntent))) {
            Log.i(TAG, "onReceive ACTION_SCREEN_ON or GARAGE_MODE_OFF");
            ApplyEngine.scheduleApply(receivedIntent);
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
            android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot, pkg);
            android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "Dpi", String.valueOf(dpi));
            // Per-package DPI для freeform-хука: 0 тоже обязательно зеркалируем. Иначе после выбора
            // «Авто» в Settings.Global навсегда оставалось старое ненулевое значение для пакета.
            if (!"none".equals(pkg)) {
                android.provider.Settings.Global.putString(cr, "voyahtune_dpi_" + pkg, String.valueOf(dpi));
            }
            // Сплит, открываемый долгим нажатием на слот дока. Флаг HasSplit читает launcherdock.js
            // (гейт долгого тапа), детали (L/R/Ratio/Dpi) — обработчик OPEN_DOCK_SPLIT ниже.
            boolean hasSplit = intent.getBooleanExtra("dock" + slot + "HasSplit", false);
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

    /** Флаг + bounds «оконного режима» → Settings.Global (читает vd_bypass.js в system_server).
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

    /** Разбудить system_server-хук freeform: перечитать кэш (флаг/bounds/DPI). Ресивер в vd_bypass.js
     *  гейтит пермишеном WRITE_SECURE_SETTINGS — доставить может только наш Native (он его держит). */
    static void sendWinReload(Context ctx) {
        try {
            Intent w = new Intent("ru.big.town.anative.WIN_RELOAD");
            w.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(w);
        } catch (Exception e) { Log.w(TAG, "sendWinReload: " + e.getMessage()); }
    }

    /**
     * Действие кнопки руля. Циклируем режимы ПО КРУГУ относительно ТЕКУЩЕГО СОХРАНЁННОГО режима (источник
     * истины — pref RestoreMode, его же восстанавливает ApplyEngine и показывает UI); если текущего нет в
     * наборе — первый. Одиночный набор → всегда этот режим. Новый режим СОХРАНЯЕМ как «последний
     * активированный» → переживёт пробуждение и попадёт в настройки VoyahTune.
     *   energy:&lt;режимы&gt;  — режим энергии;
     *   drive:&lt;режимы&gt;   — режим вождения;
     *   recycle:&lt;режимы&gt; — уровень рекуперации.
     */
    private static void handleSteerAction(Context ctx, String action) {
        if (action == null || action.isEmpty()) return;
        if (action.startsWith("energy:")) {
            cycleMode(ctx, action.substring("energy:".length()), "energy");
        } else if (action.startsWith("drive:")) {
            cycleMode(ctx, action.substring("drive:".length()), "driveMode");
        } else if (action.startsWith("recycle:")) {
            cycleMode(ctx, action.substring("recycle:".length()), "recycle");
        } else if ("toggle_forced_ev".equals(action)) {
            toggleSetting(ctx, "forcedEv");
        } else if ("toggle_pedestrian_sound".equals(action)) {
            toggleSetting(ctx, "disablePedestrianSound");
        } else if (action.startsWith("app:")) {
            // Открыть отдельное приложение (freeform-окно на display 0), закрыв активный сплит.
            openFreeformApp(ctx, action.substring("app:".length()));
            Log.i(TAG, "STEER_ACTION → приложение " + action.substring("app:".length()));
        } else if (action.startsWith("split:")) {
            // Backward-compatible строка: split:<L>,<R>,<ratio>,<lDpi>,<rDpi>[,<resizable>,<fraction>,<presetId>].
            String[] p = action.substring("split:".length()).split(",");
            if (p.length >= 3) {
                try {
                    int ratio = Integer.parseInt(p[2].trim());
                    int lDpi  = p.length > 3 ? Integer.parseInt(p[3].trim()) : 0;
                    int rDpi  = p.length > 4 ? Integer.parseInt(p[4].trim()) : 0;
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
        } else if ("open_voyahtune".equals(action)) {
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
        } else {
            Log.i(TAG, "STEER_ACTION неизвестно: " + action);
        }
    }

    /** Открыть приложение freeform-окном на указанном физическом экране: закрываем активный VD-сплит
     *  (иначе его панели «уехали» бы с VD с глитчем), затем стартуем приложение обычным launch-интентом
     *  (системный хук vd_bypass ужмёт окно). Общий путь для OPEN_FREEFORM (клик слота дока) и действия
     *  кнопки руля «app:». */
    static void openFreeformApp(Context context, String pkg) {
        openFreeformApp(context, pkg, 0);
    }

    /**
     * displayId: 0 — водительский экран, 1 — пассажирский.
     *
     * Целевой экран задаём ВСЕГДА, в том числе 0. Без явного setLaunchDisplayId startActivity с
     * FLAG_ACTIVITY_NEW_TASK находит УЖЕ СУЩЕСТВУЮЩУЮ задачу приложения и поднимает её НА ТОМ ЭКРАНЕ,
     * ГДЕ ОНА ЖИВЁТ, а не на дисплее по умолчанию. Из-за этого, если приложение открыто на пассажирском
     * экране, клик по его иконке в доке главного визуально «ничего не делал»: задача поднималась на
     * пассажирском. Сворачивание там же не помогало — задача никуда с display 1 не девалась.
     */
    static void openFreeformApp(Context context, String pkg, int displayId) {
        if (pkg == null || pkg.isEmpty()) return;
        final Context app = context.getApplicationContext();
        Intent li = app.getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) { Log.w(TAG, "openFreeformApp: нет launch intent для " + pkg); return; }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Ставим guard ДО teardown сплита: finish SplitHost тоже может запустить dismiss дока раньше,
        // чем Launcher успеет записать в foreground-кэш пакет нового приложения.
        DockLaunchGuard.arm(app, displayId, pkg);
        boolean hadSplit = SplitHostActivity.closeActiveSplit();
        final Intent fli = li;
        final android.os.Bundle opts;
        {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setLaunchDisplayId(displayId);
            opts = o.toBundle();
        }
        if (hadSplit) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try { app.startActivity(fli, opts); } catch (Exception e) { Log.w(TAG, "openFreeformApp delayed: " + e.getMessage()); }
            }, 500);
        } else {
            try { app.startActivity(fli, opts); } catch (Exception e) { Log.w(TAG, "openFreeformApp: " + e.getMessage()); }
        }
        Log.i(TAG, "openFreeformApp pkg=" + pkg + " display=" + displayId + " hadSplit=" + hadSplit);
    }

    /**
     * Циклировать режим по CSV-набору ОТНОСИТЕЛЬНО ТЕКУЩЕГО СОХРАНЁННОГО режима (не отдельного дрейфующего
     * указателя) и послать CAN. Правильный UX первого клика: если сейчас уже comfort, а набор comfort,sport —
     * первый клик уводит в sport, а не «в пустоту» обратно в comfort. Новый режим сохраняем как «последний
     * активированный» (MainActivity.persistSavedMode → pref RestoreMode) → переживёт пробуждение + в UI.
     */
    private static void cycleMode(Context ctx, String csv, String modeKey) {
        final Context app = ctx.getApplicationContext();
        // Пользовательский выбор должен идти ПОСЛЕ уже запущенного wake-restore, а не параллельно с ним:
        // иначе restore успевал отправить старый snapshot поверх только что выбранного режима.
        ApplyEngine.postUserCommand("steer " + modeKey, () -> {
            String cur = MainActivity.currentSavedMode(app, modeKey);
            String next = SteeringActionPolicy.nextMode(csv, cur);
            if (next == null) return;
            byte[][] cmd = "energy".equals(modeKey) ? MainActivity.getEnergyCanCommand(next)
                    : "recycle".equals(modeKey) ? MainActivity.getRecEnergyCanCommand(next)
                    : MainActivity.getDriveModeCanCommand(next);
            if (!MainActivity.setCanValues(1, cmd, "steer " + modeKey + " → " + next)) {
                Log.w(TAG, "STEER_ACTION " + modeKey + ": CAN failed, selection not persisted");
                return;
            }
            MainActivity.persistSavedMode(app, modeKey, next);
            Log.i(TAG, "STEER_ACTION " + modeKey + ": набор=" + csv
                    + " тек=" + cur + " → " + next);
        });
    }

    /** Переключить бинарную настройку относительно сохранённого значения, применить CAN и сохранить новый state. */
    private static void toggleSetting(Context ctx, String key) {
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
        });
    }

}
