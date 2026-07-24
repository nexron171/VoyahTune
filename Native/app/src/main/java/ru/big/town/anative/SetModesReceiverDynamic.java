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

    @Override
    public void onReceive(Context context, Intent intent) {
        String receivedIntent = intent.getAction();

        Log.i(TAG, "onReceive DYN enter by intent" + receivedIntent);

        if ( "android.intent.action.KEYCODE_SWC_USER_DEFINE".equals(receivedIntent)) {
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

        // Зеркалим конфиг кнопок руля в Settings.Global (оттуда читает keymng2.js). Только full.
        if ("ru.big.town.anative.STEER_CONFIG".equals(receivedIntent) && BuildConfig.IS_FULL) {
            mirrorSteer(context, intent, "steerStarShort");
            mirrorSteer(context, intent, "steerStarLong");
            mirrorSteer(context, intent, "steerDvrShort");
            mirrorSteer(context, intent, "steerDvrLong");
            Log.i(TAG, "STEER_CONFIG зеркалирован в Settings.Global");
        }

        // Зеркалим конфиг «Системного дока» в Settings.Global (оттуда читает launcherdock.js в процессе
        // лаунчера) и шлём DOCK_RELOAD, чтобы хук перечитал и перерисовал иконки. Только full.
        if ("ru.big.town.anative.DOCK_CONFIG".equals(receivedIntent) && BuildConfig.IS_FULL) {
            mirrorDock(context, intent, 1);
            mirrorDock(context, intent, 2);
            Intent r = new Intent("ru.big.town.anative.DOCK_RELOAD");
            r.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(r);   // implicit — ловит dynamic-receiver, зарегистрированный launcherdock.js
            sendWinReload(context);     // обновить кэш freeform (в т.ч. per-app DPI) в system_server (vd_bypass.js)
            Log.i(TAG, "DOCK_CONFIG зеркалирован в Settings.Global + DOCK_RELOAD");
        }

        // Конфиг «оконного режима» (фейк-freeform): флаг on/off + bounds → Settings.Global, затем WIN_RELOAD
        // (system_server перечитает кэш в vd_bypass.js). Только full.
        if ("ru.big.town.anative.FREEFORM_CONFIG".equals(receivedIntent) && BuildConfig.IS_FULL) {
            mirrorFreeform(context, intent);
            sendWinReload(context);
            Log.i(TAG, "FREEFORM_CONFIG зеркалирован + WIN_RELOAD");
        }

        // Открытие приложения из дока во freeform (launcherdock делегирует СЮДА, чтобы мы закрыли активный
        // VD-сплит и запустили приложение ЧИСТО на display 0). Иначе приложение-панель «уехало» бы с VD с
        // глитчем (чёрное окно). closeActiveSplit force-stop'ит панели → приложение стартует заново; если
        // сплит был — запускаем с задержкой (teardown асинхронный), иначе сразу. Только full.
        if ("ru.big.town.anative.OPEN_FREEFORM".equals(receivedIntent) && BuildConfig.IS_FULL) {
            openFreeformApp(context, intent.getStringExtra("pkg"));
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
                    SplitHostActivity.launchSplit(context.getApplicationContext(), l, r, ratio, lDpi, rDpi);
                    Log.i(TAG, "OPEN_DOCK_SPLIT slot=" + slot + " " + l + "/" + r + " ratio=" + ratio);
                } else {
                    Log.i(TAG, "OPEN_DOCK_SPLIT slot=" + slot + " — сплит не назначен");
                }
            }
        }

        // Исполнение действия кнопки руля (шлёт keymng2.js / хук DVR). Пока — переключение энергорежима
        // с циклированием по набору режимов. Только full.
        if ("ru.big.town.anative.STEER_ACTION".equals(receivedIntent) && BuildConfig.IS_FULL) {
            handleSteerAction(context, intent.getStringExtra("action"));
        }
//        if (receivedIntent.equals("ru.big.town.anative.APPLY_DRIVE_MODES")) {
//            repeat = 3;
//            isButton = true;
//        } else {
//            repeat = 7;
//            isButton = false;
//        }
        // Fallback-триггер пробуждения через броадкасты. Держим его активным всегда (даже если
        // power-listener работает): при рестарте CarService слушатель может «протухнуть», а этот
        // путь остаётся. Возможные дубли с power-listener гасит дебаунс в ApplyEngine.
        if (Intent.ACTION_SCREEN_ON.equals(receivedIntent) ||
                "com.android.server.jobscheduler.GARAGE_MODE_OFF".equals(receivedIntent)) {
            Log.i(TAG, "onReceive ACTION_SCREEN_ON or GARAGE_MODE_OFF");
            ApplyEngine.scheduleApply(receivedIntent);
        }

                //throw new UnsupportedOperationException("Not yet implemented");
        if (isOrderedBroadcast()) {
            setResultCode(-1);
        }
    }

    /** Записать выбор действия слота в Settings.Global под ключом voyahtune_<slot> (нужен WRITE_SECURE_SETTINGS). */
    private static void mirrorSteer(Context ctx, Intent intent, String key) {
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
    private static void mirrorDock(Context ctx, Intent intent, int slot) {
        String pkg = intent.getStringExtra("dock" + slot);
        if (pkg == null || pkg.isEmpty()) pkg = "none";
        int dpi = intent.getIntExtra("dock" + slot + "Dpi", 0);
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot, pkg);
            android.provider.Settings.Global.putString(cr, "voyahtune_dock" + slot + "Dpi", String.valueOf(dpi));
            // Per-package DPI для freeform-хука: vd_bypass.ensureActivityConfiguration читает voyahtune_dpi_<pkg>.
            if (!"none".equals(pkg) && dpi > 0) {
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

    /** Флаг + bounds «оконного режима» → Settings.Global (читает vd_bypass.js в system_server).
     *  extras: on(boolean, опц.), left/top/right/bottom(int, опц., пишем только >=0). */
    private static void mirrorFreeform(Context ctx, Intent intent) {
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
    private static void sendWinReload(Context ctx) {
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
     *   energy:&lt;режимы&gt; — режим энергии (getEnergyCanCommand);
     *   drive:&lt;режимы&gt;  — режим вождения (getDriveModeCanCommand).
     */
    private static void handleSteerAction(Context ctx, String action) {
        if (action == null || action.isEmpty()) return;
        if (action.startsWith("energy:")) {
            cycleMode(ctx, action.substring("energy:".length()), true);
        } else if (action.startsWith("drive:")) {
            cycleMode(ctx, action.substring("drive:".length()), false);
        } else if (action.startsWith("app:")) {
            // Открыть отдельное приложение (freeform-окно на display 0), закрыв активный сплит.
            openFreeformApp(ctx, action.substring("app:".length()));
            Log.i(TAG, "STEER_ACTION → приложение " + action.substring("app:".length()));
        } else if (action.startsWith("split:")) {
            // Открыть пресет сплита. Резолвленная строка от VoyahTune: split:<L>,<R>,<ratio>,<lDpi>,<rDpi>.
            String[] p = action.substring("split:".length()).split(",");
            if (p.length >= 3) {
                try {
                    int ratio = Integer.parseInt(p[2].trim());
                    int lDpi  = p.length > 3 ? Integer.parseInt(p[3].trim()) : 0;
                    int rDpi  = p.length > 4 ? Integer.parseInt(p[4].trim()) : 0;
                    SplitHostActivity.launchSplit(ctx.getApplicationContext(), p[0].trim(), p[1].trim(), ratio, lDpi, rDpi);
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
                ctx.startActivity(i);
                Log.i(TAG, "STEER_ACTION → открыть VoyahTune");
            } catch (Exception e) {
                Log.w(TAG, "open VoyahTune failed: " + e.getMessage());
            }
        } else {
            Log.i(TAG, "STEER_ACTION неизвестно: " + action);
        }
    }

    /** Открыть приложение freeform-окном на display 0: закрываем активный VD-сплит (иначе его панели
     *  «уехали» бы с VD с глитчем), затем стартуем приложение обычным launch-интентом (системный хук
     *  vd_bypass ужмёт окно). Общий путь для OPEN_FREEFORM (клик слота дока) и действия кнопки руля «app:». */
    static void openFreeformApp(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        boolean hadSplit = SplitHostActivity.closeActiveSplit();
        final Context app = context.getApplicationContext();
        Intent li = app.getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) { Log.w(TAG, "openFreeformApp: нет launch intent для " + pkg); return; }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent fli = li;
        if (hadSplit) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try { app.startActivity(fli); } catch (Exception e) { Log.w(TAG, "openFreeformApp delayed: " + e.getMessage()); }
            }, 500);
        } else {
            try { app.startActivity(fli); } catch (Exception e) { Log.w(TAG, "openFreeformApp: " + e.getMessage()); }
        }
        Log.i(TAG, "openFreeformApp pkg=" + pkg + " hadSplit=" + hadSplit);
    }

    /**
     * Циклировать режим по CSV-набору ОТНОСИТЕЛЬНО ТЕКУЩЕГО СОХРАНЁННОГО режима (не отдельного дрейфующего
     * указателя) и послать CAN. Правильный UX первого клика: если сейчас уже comfort, а набор comfort,sport —
     * первый клик уводит в sport, а не «в пустоту» обратно в comfort. Новый режим сохраняем как «последний
     * активированный» (MainActivity.persistSavedMode → pref RestoreMode) → переживёт пробуждение + в UI.
     */
    private static void cycleMode(Context ctx, String csv, boolean energy) {
        String[] modes = csv.split(",");
        if (modes.length == 0) return;
        String cur = MainActivity.currentSavedMode(ctx, energy);
        int idx = -1;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(cur)) { idx = i; break; }
        String next = modes[idx >= 0 ? (idx + 1) % modes.length : 0];
        byte[][] cmd = energy ? MainActivity.getEnergyCanCommand(next) : MainActivity.getDriveModeCanCommand(next);
        MainActivity.setCanValues(1, cmd, (energy ? "steer energy → " : "steer drive → ") + next);
        MainActivity.persistSavedMode(ctx, energy, next);
        Log.i(TAG, "STEER_ACTION " + (energy ? "energy" : "drive") + ": набор=" + csv + " тек=" + cur + " → " + next);
    }

}