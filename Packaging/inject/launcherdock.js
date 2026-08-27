// launcherdock.js — переопределение доков водительского и пассажирского экранов Open Voyah.
// На ОД-прошивках это NavigationBarMain + NavigationBarSecond, на ПИ — общий NavigationBar с mScreenId.
//
// Механика: хук навигационного бара и списка приложений штатного лаунчера:
//   • КОНФИГ — живьём из Settings.Global: voyahtune_dock1/2 и voyahtune_dockPassenger1/2
//     (= packageName; "none" = слот не переопределён). Опц. одноимённые *Dpi ключи.
//     Пишет их Native (SetModesReceiverDynamic.mirrorDock), читаем как action() в steeringwheelkeys.js.
//   • ИКОНКА слота — через view.setBackground(drawable), НЕ setImageDrawable: картинка слота живёт в
//     background у NoToggleRadioButton. Оригинал бэкапим один раз (getBackground), кастом строим из
//     pm.getApplicationIcon → Bitmap → 50x50 → BitmapDrawable + Java.retain. Всё на main-треде + invalidate.
//     Хук updateTheme переустанавливает иконки после каждой перекраски темы (иначе фон сбрасывается).
//   • КЛИК — onClick сравнивает view.getId() с getId() закэшированных полей mScreenUpItemView1/2. При
//     совпадении и если pkg установлен — делегируем Native. Native запускает обычную задачу целевого
//     пакета на display 0, а vd_bypass ужимает её WindowManager-рамку. VD — только для split-пресетов.
//   • ДОЛГИЙ ТАП по слоту — если слоту назначен сплит (voyahtune_dockN HasSplit=="1"), шлём Native
//     broadcast OPEN_DOCK_SPLIT (slot) → Native читает детали сплита из Settings.Global и стартует его на
//     VD. Если сплит не назначен — слушатель возвращает false (штатное долгое поведение лаунчера). Назначение
//     сплита слоту делается в VoyahTune («Приложения и разделение экрана» → Системный док).
//   • ПОДСВЕТКА — updateSelectedApp: reverse-mapping (наш pkg слота → штатный pkg, закреплённый за слотом),
//     чтобы родной лаунчер чекнул правильную кнопку. Косметика, не блокер.
//   • RELOAD — приёмник ru.big.town.anative.DOCK_RELOAD перечитывает конфиг и перерисовывает иконки
//     (иконки рисуются проактивно; клик читает конфиг живьём, ему reload не нужен).
//   • ALL APPS — в списки обоих экранов добавляются все launchable user-apps, которых штатный
//     лаунчер не показывает. PackageManager scan кэшируется до PACKAGE_ADDED/REMOVED/CHANGED;
//     package-broadcast через штатный AllAppDataManager.reload() пересобирает оба списка и обновляет открытые UI
//     без polling. Клик идёт через OEM AppLauncher с mScreenId владельца All Apps (и проверочным fallback по view),
//     поэтому top activity остаётся целевым package на соответствующем физическом display.
//   • ВОЗВРАТ ИЗ FULLSCREEN — TOP_ACTIVITY_CHANGED повторно просит штатный LauncherModel показать
//     главный navigation bar для нашего оконного приложения. Это закрывает возврат из угловой камеры,
//     которая штатно скрывает док своим fullscreen-переходом.
Java.perform(function () {
    // Слот → штатный pkg, который родной лаунчер умеет подсвечивать (oversea, главный экран).
    // ВНИМАНИЕ: значения версионно-хрупкие, подтвердить на живой голове H97C.
    var STOCK_SLOT_PKG = { 1: "com.qinggan.bluetoothphone", 2: "com.qinggan.app.music" };
    var NAV_MAIN   = "com.qinggan.launcher.navigation.NavigationBarMain"; // класс навбара в ОД-прошивках
    var NAV_SECOND = "com.qinggan.launcher.navigation.NavigationBarSecond";
    var RELOAD_ACT = "ru.big.town.anative.DOCK_RELOAD";
    var OUR_PKG    = "ru.big.town.anative";           // наш VD-хост (SplitHostActivity) для подсветки
    var RESTORE_PKG = "ru.big.town.restoremode";      // VoyahTune (UI) — открывается долгим тапом по «меню»

    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var SystemClock    = Java.use("android.os.SystemClock");
    var Intent         = Java.use("android.content.Intent");
    var Bitmap         = Java.use("android.graphics.Bitmap");
    var BitmapConfig   = Java.use("android.graphics.Bitmap$Config");
    var BitmapDrawable = Java.use("android.graphics.drawable.BitmapDrawable");
    var Canvas         = Java.use("android.graphics.Canvas");

    var TAG = "vt_launcherdock";
    var Log = Java.use("android.util.Log");

    // На ОД классы экранов раздельные, на ПИ общий класс различается полем mScreenId.
    var SHARED_NAV = false;
    var NAV_CLASSES = [];
    try {
        Java.use(NAV_MAIN);
        NAV_CLASSES.push({ name: NAV_MAIN, screen: 0 });
        try {
            Java.use(NAV_SECOND);
            NAV_CLASSES.push({ name: NAV_SECOND, screen: 1 });
        } catch (e2) { Log.w(TAG, "OD passenger NavigationBarSecond unavailable: " + e2); }
        Log.i(TAG, "OD firmware");
    } catch (e) {
        NAV_MAIN   = "com.qinggan.mainlauncher.navigation.NavigationBar";  // класс навбара в ПИ-прошивках
        NAV_SECOND = null;
        SHARED_NAV = true;
        NAV_CLASSES.push({ name: NAV_MAIN, screen: -1 });
        Log.i(TAG, "PI firmware");
    }

    // Номер экрана инстанса навбара: 0 = водительский (наш), 1 = пассажирский, -1 = определить не удалось.
    // Основной источник — поле mScreenId (им же пользуется сам лаунчер). Фолбэк — displayId вьюхи бара:
    // не зависит от приватных полей лаунчера и переживает переименования на другой прошивке.
    function screenIdOf(instance, fallbackScreen) {
        try {
            var v = instance.mScreenId.value;
            if (v === 0 || v === 1) return v;
        } catch (e) {}
        try {
            var anyView = instance.mScreenUpAllAppView.value || instance.mScreenUpItemView1.value;
            if (anyView) {
                var d = anyView.getDisplay();
                if (d) return d.getDisplayId();
            }
        } catch (e) {}
        try {
            var className = "" + instance.getClass().getName();
            if (className.indexOf("NavigationBarSecond") >= 0) return 1;
            if (!SHARED_NAV && className.indexOf("NavigationBarMain") >= 0) return 0;
        } catch (e) {}
        return (fallbackScreen === 0 || fallbackScreen === 1) ? fallbackScreen : -1;
    }

    function declaresMethod(clazz, name) {
        try {
            var methods = clazz.class.getDeclaredMethods();
            for (var i = 0; i < methods.length; i++) {
                if (("" + methods[i].getName()) === name) return true;
            }
        } catch (e) {}
        return false;
    }

    function managedScreenId(instance, fallbackScreen) {
        var id = screenIdOf(instance, fallbackScreen);
        if (id === 0 || id === 1) return id;
        if (!managedScreenId._warned) {
            managedScreenId._warned = true;
            try { Log.i(TAG, "screenId неизвестен на общем классе навбара — хуки пропущены"); } catch (e) {}
        }
        return -1;
    }

    // Кэш иконочного конфига (для проактивной перерисовки). Клик читает Settings.Global живьём.
    var cache = { dock1: "none", dock2: "none", passenger1: "none", passenger2: "none" };
    // Бэкап штатных фонов слотов: originalBg["<screenId>:<viewName>"] = Drawable (один раз на экран+поле,
    // чтобы Drawable одного экрана никогда не попал во вьюху другого — см. updateIcons).
    var originalBg = {};
    // Удержанные Drawable (иначе GC уберёт background).
    var retained = [];
    var MAX_RETAINED_DRAWABLES = 64;
    // Последний нажатый слот (для корректной подсветки нашего VD-хоста в updateSelectedApp).
    var lastSlot = { 0: 0, 1: 0 };
    // viewId слота дока → номер слота (1/2). Заполняется в updateIcons, читается в долгом тапе слота
    // (устойчиво к нескольким инстансам навбара — ключ по id вью, а не по последнему инстансу).
    var slotByViewId = {};
    // Приложение переднего плана ПО ЭКРАНАМ. Один общий кэш позволял пассажирскому бару перетирать
    // foreground водительского, и решения о видимости дока принимались по чужому экрану.
    var fgByScreen = { 0: { pkg: "", act: "" }, 1: { pkg: "", act: "" } };

    // Штатные пакеты, которым МОЖНО скрывать док: их окна оконный режим не ужимает (они честно
    // разворачиваются на весь экран), поэтому прятать док для них — правильное штатное поведение.
    // ВАЖНО: список должен соответствовать блэклисту ffBlacklisted в vd_bypass.js — если там
    // появится новый префикс, добавить и сюда, иначе док зависнет поверх полноэкранного окна.
    // ИСКЛЮЧЕНИЕ — ru.big.town: наши окна тоже полноэкранные, но часть из них сама резервирует полосу
    // под родной док, поэтому решение по ним принимает не этот список, а dockKept() по имени активити.
    var STOCK_PREFIX = ["com.android", "com.qinggan", "com.pateo", "com.baidu", "com.huawei",
                        "com.iflytek", "com.iland", "com.mega", "com.qti", "com.qualcomm",
                        "com.tencent", "com.nng.igo.primong", "com.bz.CA08"];
    function isStockPkg(pkg) {
        if (!pkg) return true;                                   // неизвестно → считаем штатным (не мешаем)
        if (pkg === "com.android.settings" || pkg === "com.android.documentsui") return false;
        for (var i = 0; i < STOCK_PREFIX.length; i++) if (pkg.indexOf(STOCK_PREFIX[i]) === 0) return true;
        return false;
    }

    // Наши активити, которые САМИ отступают на полосу родного дока (их контент туда не залезает).
    // Под ними док обязан остаться — иначе получается пустая чёрная полоса. Остальные наши экраны
    // отступа не делают, им док прятать нужно, иначе он накроет их левый край.
    function ourInsetActivity(act) {
        act = "" + (act || "");
        return act.indexOf("SplitHostActivity") >= 0
            || act.indexOf("restoremode.MainActivity") >= 0
            || act.indexOf("AdvanceActivity") >= 0
            || act.indexOf("TripHistoryActivity") >= 0;
    }

    // ЕДИНОЕ условие «док должен остаться под этим окном». Одно на всех потребителей — раньше их было
    // три с разными предикатами, и они противоречили друг другу.
    function dockKept(pkg, act) {
        if (cfg("dockpin") === "0" || cfg("freeform") === "0") return false;
        if (!pkg) return false;                                  // неизвестно → не мешаем штатному
        if (pkg.indexOf("ru.big.town") === 0) return ourInsetActivity(act);
        return !isStockPkg(pkg);
    }

    // Native публикует guard одной строкой "elapsedDeadline|package" непосредственно перед
    // startActivity. Это закрывает окно гонки dismiss → updateSelectedApp при запуске со звёздочки:
    // foreground-кэш в этот момент ещё закономерно содержит Launcher/старое приложение.
    function pendingDockLaunch(screenId) {
        if (cfg("dockpin") === "0" || cfg("freeform") === "0") return null;
        try {
            var raw = cfg("dockLaunchGuard" + screenId);
            if (raw === "none") return null;
            var sep = raw.indexOf("|");
            if (sep <= 0 || sep >= raw.length - 1) return null;
            var deadline = parseInt(raw.substring(0, sep), 10);
            var now = Number(SystemClock.elapsedRealtime());
            var remaining = deadline - now;
            // Верхний предел делает persisted Settings-запись безопасной после reboot, когда
            // elapsedRealtime снова начинается с нуля. Штатный guard держится 5 секунд.
            if (isNaN(deadline) || remaining <= 0 || remaining > 10000) return null;
            var pkg = raw.substring(sep + 1);
            // Guard не должен удержать док поверх полноэкранного штатного приложения, которому
            // штатный dismiss как раз нужен. Для наших двух inset-экранов activity заранее известна.
            var keep = (pkg === OUR_PKG || pkg === RESTORE_PKG)
                    || (pkg.indexOf("ru.big.town") !== 0 && !isStockPkg(pkg));
            if (!keep) return null;
            return { pkg: pkg, remaining: remaining };
        } catch (e) { return null; }
    }

    function ctx() {
        try { var app = ActivityThread.currentApplication(); if (app !== null) return app.getApplicationContext(); } catch (e) {}
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    // Значение слота из Settings.Global; нет значения → "none".
    function cfg(key) {
        try {
            var v = SettingsGlobal.getString(ctx().getContentResolver(), "voyahtune_" + key);
            return (v === null || v === "") ? "none" : v.toString();
        } catch (e) { return "none"; }
    }

    function refreshCache() {
        cache.dock1 = cfg("dock1");
        cache.dock2 = cfg("dock2");
        cache.passenger1 = cfg("dockPassenger1");
        cache.passenger2 = cfg("dockPassenger2");
        Log.i(TAG, "[dock] cache: driver=" + cache.dock1 + "/" + cache.dock2
                + " passenger=" + cache.passenger1 + "/" + cache.passenger2);
    }

    function dockPackage(screenId, slot, live) {
        var key = screenId === 1 ? "dockPassenger" + slot : "dock" + slot;
        if (live) return cfg(key);
        if (screenId === 1) return slot === 1 ? cache.passenger1 : cache.passenger2;
        return slot === 1 ? cache.dock1 : cache.dock2;
    }

    // Проверка «pkg установлен и запускаем» — гейт перед перехватом клика.
    function isInstalled(pkg) {
        if (pkg === "none") return false;
        try {
            var li = ctx().getPackageManager().getLaunchIntentForPackage(pkg);
            return li !== null;
        } catch (e) { return false; }
    }

    function retainDrawable(obj) {
        try {
            var r = Java.retain(obj);
            retained.push(r);
            // updateTheme может вызываться много раз за жизнь launcher. Старые background уже давно
            // заменены; освобождаем их global refs с большим запасом для живых navbar instances.
            while (retained.length > MAX_RETAINED_DRAWABLES) {
                var old = retained.shift();
                try { old.$dispose(); } catch (ignored) {}
            }
            return r;
        } catch (e) { return obj; }
    }

    // Drawable иконки приложения: pm.getApplicationIcon → рисуем на Bitmap → масштаб 50x50 → BitmapDrawable.
    function getAppDrawable(pkg) {
        try {
            var pm = ctx().getPackageManager();
            var ai = pm.getApplicationInfo(pkg, 0);
            var icon = pm.getApplicationIcon(ai);
            var bmp = Bitmap.createBitmap(icon.getIntrinsicWidth(), icon.getIntrinsicHeight(), BitmapConfig.ARGB_8888.value);
            var canvas = Canvas.$new(bmp);
            icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            icon.draw(canvas);
            var scaled = Bitmap.createScaledBitmap(bmp, 50, 50, true);
            var d = BitmapDrawable.$new(ctx().getResources(), scaled);
            d.setGravity(17);              // Gravity.CENTER
            d.setBounds(0, 0, 50, 50);
            return retainDrawable(d);
        } catch (e) {
            Log.e(TAG, "[dock] getAppDrawable err " + pkg + ": " + e);
            return null;
        }
    }

    // Открыть VoyahTune (UI RestoreMode) — по долгому тапу «меню».
    function openVoyahTune() {
        try {
            var i = Intent.$new();
            i.setClassName(RESTORE_PKG, "ru.big.town.restoremode.MainActivity");
            i.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
            ctx().startActivity(i);
            try { Java.use("android.util.Log").i("voyahdock", "menu long-press -> VoyahTune"); } catch (ee) {}
            Log.i(TAG, "[dock] menu long-press -> VoyahTune");
        } catch (e) { Log.e(TAG, "[dock] openVoyahTune err: " + e); }
    }

    // Слушатель долгого тапа «меню» (кнопка «все приложения», последний элемент дока). Регистрируем
    // один раз лениво. onLongClick → VoyahTune + return true (гасим штатное долгое). Короткий тап не
    // трогаем — идёт штатно (открытие списка приложений).
    var menuLC = null;
    function getMenuLongClick() {
        if (menuLC !== null) return menuLC;
        try {
            var Listener = Java.registerClass({
                name: "ru.big.town.dock.MenuLongClick",
                implements: [Java.use("android.view.View$OnLongClickListener")],
                methods: {
                    onLongClick: {
                        returnType: "boolean",
                        argumentTypes: ["android.view.View"],
                        implementation: function (view) { openVoyahTune(); return true; }
                    }
                }
            });
            menuLC = Listener.$new();
        } catch (e) { Log.e(TAG, "[dock] menuLongClick reg err: " + e); }
        return menuLC;
    }

    // Долгий тап по слоту дока → открыть назначенный слоту СПЛИТ (делегируем Native: он читает детали
    // сплита из Settings.Global и стартует SplitHostActivity на VD). Слушатель один на оба слота; слот
    // определяем по view.getId() через slotByViewId. Если сплит слоту не назначен (voyahtune_dockN
    // HasSplit != "1") — возвращаем false, чтобы штатное долгое поведение лаунчера не ломать.
    var slotLC = null;
    function getSlotLongClick() {
        if (slotLC !== null) return slotLC;
        try {
            var Listener = Java.registerClass({
                name: "ru.big.town.dock.SlotLongClick",
                implements: [Java.use("android.view.View$OnLongClickListener")],
                methods: {
                    onLongClick: {
                        returnType: "boolean",
                        argumentTypes: ["android.view.View"],
                        implementation: function (view) {
                            try {
                                var slot = slotByViewId["" + view.getId()] || 0;
                                var has = slot ? cfg("dock" + slot + "HasSplit") : "?";
                                try { Java.use("android.util.Log").i("voyahdock", "slot long-press id=" + view.getId() + " slot=" + slot + " hasSplit=" + has); } catch (ee) {}
                                if (slot === 0) return false;
                                if (has !== "1") return false;  // сплит не назначен → штатно
                                openDockSplit(slot);
                                return true;
                            } catch (e) {
                                try { Log.e(TAG, "slot long-press err: " + e); } catch (ee) {}
                                return false;
                            }
                        }
                    }
                }
            });
            slotLC = Listener.$new();
        } catch (e) { Log.e(TAG, "[dock] slotLongClick reg err: " + e); }
        return slotLC;
    }

    // Открыть назначенный слоту сплит — broadcast OPEN_DOCK_SPLIT в Native (тот резолвит детали и стартует).
    function openDockSplit(slot) {
        try {
            var i = Intent.$new("ru.big.town.anative.OPEN_DOCK_SPLIT");
            i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra.overload('java.lang.String', 'int').call(i, "slot", slot);
            i.addFlags(0x00000020);   // FLAG_INCLUDE_STOPPED_PACKAGES — добудиться, даже если Native стоплен
            ctx().sendBroadcast(i);
            Log.i(TAG, "[dock] OPEN_DOCK_SPLIT slot=" + slot);
            try { Java.use("android.util.Log").i("voyahdock", "OPEN_DOCK_SPLIT sent slot=" + slot); } catch (ee) {}
        } catch (e) {
            Log.e(TAG, "[dock] openDockSplit err: " + e);
            try { Log.e(TAG, "openDockSplit err: " + e); } catch (ee) {}
        }
    }

    function setDockViewVisibility(view, visibility, label) {
        if (!view) return;
        try { view.setVisibility(visibility); }
        catch (e) { Log.e(TAG, "[dock] visibility " + label + " err: " + e); }
    }

    function setDockViewHeight(view, height, label) {
        if (!view) return;
        try {
            var lp = view.getLayoutParams();
            if (lp === null) return;
            lp.height.value = height;
            view.setLayoutParams(lp);
        } catch (e) { Log.e(TAG, "[dock] height " + label + " err: " + e); }
    }

    function applyScreenLiftDock(instance, type, fallbackScreen) {
        var sid = managedScreenId(instance, fallbackScreen);
        if (sid < 0) return;
        var compact = type === 1;
        try {
            var up = instance.mScreenUpView.value;
            var down = instance.mScreenDownView.value;
            var group = instance.mScreenUpRadioGroup.value;
            var home = instance.mScreenUpHomeView.value;
            var item1 = instance.mScreenUpItemView1.value;
            var item2 = instance.mScreenUpItemView2.value;
            var item3 = instance.mScreenUpItemView3.value;
            var item4 = instance.mScreenUpItemView4.value;
            var allApps = instance.mScreenUpAllAppView.value;

            // OEM doScreenLift(1) перед нашим post-hook показывает отдельный one-button screenDown.
            // Всегда возвращаем полноценный screenUp и явно делаем четыре обязательных элемента
            // видимыми: в некоторых resource-вариантах All Apps изначально имеет visibility=gone.
            setDockViewVisibility(up, 0, "screenUp");
            setDockViewVisibility(down, 8, "screenDown");
            setDockViewHeight(up, compact ? 560 : 720, "screenUp");
            setDockViewHeight(group, compact ? 560 : -1, "radioGroup");
            setDockViewVisibility(home, 0, "home");
            setDockViewVisibility(item1, 0, "slot1");
            setDockViewVisibility(item2, 0, "slot2");
            setDockViewVisibility(allApps, 0, "allApps");
            setDockViewVisibility(item3, compact ? 8 : 0, "slot3");
            setDockViewVisibility(item4, compact ? 8 : 0, "slot4");
            Log.i(TAG, "[dock] screen=" + sid + " lift=" + type
                    + " mode=" + (compact ? "compact(home+1+2+allapps)" : "normal"));
        } catch (e) { Log.e(TAG, "[dock] screen-lift layout err: " + e); }
    }

    function currentScreenLiftType() {
        try {
            var SP = Java.use("android.os.SystemProperties");
            var type = SP.getInt("persist.qg.canbus.bcm_screenAutoLiftFdb", 2);
            if (type === 1 || type === 2) return type;
        } catch (e) {}
        var saved = parseInt(cfg("screen_lift_type"), 10);
        return saved === 1 ? 1 : 2;
    }

    // Перерисовка иконок слотов на инстансе навбара. Только слоты 1 и 2. Строго на main-треде.
    function updateIcons(instance, fallbackScreen) {
        try {
            var sid = managedScreenId(instance, fallbackScreen);
            if (sid < 0) return;
            // Ключ бэкапа включает номер экрана: иначе штатный Drawable водительского бара мог быть
            // восстановлен в ОДНОИМЁННОЕ поле пассажирского. Иконка слота живёт в background у
            // NoToggleRadioButton (StateListDrawable) — один такой объект на двух View даёт общее
            // состояние на двоих, и на одном из экранов кнопка резолвится в пустоту, т.е. «исчезает».
            var map = { "mScreenUpItemView1": dockPackage(sid, 1, false),
                        "mScreenUpItemView2": dockPackage(sid, 2, false) };
            for (var name in map) {
                var view;
                try { view = (name === "mScreenUpItemView1") ? instance.mScreenUpItemView1.value
                                                              : instance.mScreenUpItemView2.value; } catch (e) { continue; }
                if (!view) continue;
                var bgKey = sid + ":" + name;
                if (!originalBg[bgKey]) originalBg[bgKey] = view.getBackground();  // бэкап штатного фона один раз
                var pkg = map[name];
                if (pkg === "none" || !isInstalled(pkg)) {
                    view.setBackground(originalBg[bgKey]);                         // восстановить штатную иконку
                } else {
                    var d = getAppDrawable(pkg);
                    if (d) view.setBackground(d);
                }
                view.invalidate();
            }
            // Долгий тап по «меню» (все приложения, mScreenUpAllAppView) → VoyahTune. Короткий тап НЕ
            // трогаем — идёт штатно (открытие списка приложений). setOnLongClickListener идемпотентен,
            // навешиваем на каждом проходе updateIcons (init/theme/reload) — переживает перекраску темы.
            try {
                var av = instance.mScreenUpAllAppView.value;
                if (av && sid === 0) {
                    var lc = getMenuLongClick();
                    if (lc) { av.setLongClickable(true); av.setOnLongClickListener(lc); }
                } else if (av) {
                    av.setOnLongClickListener(null);
                    av.setLongClickable(false);
                }
            } catch (e) { Log.e(TAG, "[dock] menu long-press attach err: " + e); }
            // Долгий тап по слотам 1/2 → открыть назначенный сплит. Регистрируем viewId→slot и вешаем
            // слушатель (идемпотентно, переживает перекраску темы, как и меню-лонгтап выше).
            try {
                var sv1 = instance.mScreenUpItemView1.value;
                var sv2 = instance.mScreenUpItemView2.value;
                if (sid === 0) {
                    var slc = getSlotLongClick();
                    if (slc && sv1) { slotByViewId["" + sv1.getId()] = 1; sv1.setLongClickable(true); sv1.setOnLongClickListener(slc); }
                    if (slc && sv2) { slotByViewId["" + sv2.getId()] = 2; sv2.setLongClickable(true); sv2.setOnLongClickListener(slc); }
                } else {
                    if (sv1) { sv1.setOnLongClickListener(null); sv1.setLongClickable(false); }
                    if (sv2) { sv2.setOnLongClickListener(null); sv2.setLongClickable(false); }
                }
            } catch (e) { Log.e(TAG, "[dock] slot long-press attach err: " + e); }
            applyScreenLiftDock(instance, currentScreenLiftType(), fallbackScreen);
        } catch (e) { Log.e(TAG, "[dock] updateIcons err: " + e); }
    }

    // Первичный проход + reload: перерисовать иконки на всех живых навбарах обоих экранов.
    function updateAllNavbars() {
        NAV_CLASSES.forEach(function (entry) {
            try {
                Java.choose(entry.name, {
                    onMatch: function (inst) {
                        Java.scheduleOnMainThread(function () {
                            try { updateIcons(inst, entry.screen); } catch (e) { Log.e(TAG, "[dock] updateAll err: " + e); }
                        });
                    },
                    onComplete: function () {}
                });
            } catch (e) { Log.e(TAG, "[dock] choose " + entry.name + " err: " + e); }
        });
    }

    // Freeform-запуск приложения из слота дока делегируем Native: Native закроет активный VD-сплит и
    // запустит обычную задачу целевого пакета на выбранном физическом display.
    function launchFreeform(pkg, displayId) {
        try {
            var i = Intent.$new("ru.big.town.anative.OPEN_FREEFORM");
            i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "pkg", "" + pkg);
            i.putExtra.overload('java.lang.String', 'int').call(i, "display", displayId);
            i.addFlags(0x00000020);   // FLAG_INCLUDE_STOPPED_PACKAGES — добудиться, даже если Native стоплен
            ctx().sendBroadcast(i);
            Log.i(TAG, "[dock] OPEN_FREEFORM -> " + pkg + " display=" + displayId);
        } catch (e) { Log.e(TAG, "[dock] launchFreeform err: " + e); }
    }

    // Штатный All Apps фильтрует почти все сторонние APK. Вариант voboost решает это хуком
    // AllAppDataManager + AllAppAdapter. Здесь тот же контракт для обоих физических экранов;
    // запуск делегируется OEM AppLauncher с mScreenId владельца All Apps. У AllAppAdapter нет mScreenId,
    // поэтому вычислять экран при bind нельзя: null в JavaScript превращается в 0 и тап пассажира
    // ошибочно уходит водителю.
    // Никакого периодического PackageManager polling: снимок живёт до ближайшего package-broadcast.
    function installAllAppsHooks() {
        try {
            var AppBean = Java.use("com.qinggan.launcher.base.bean.AppBean");
            var Data = Java.use("com.qinggan.launcher.base.allapp.AllAppDataManager");
            var Adapter = Java.use("com.qinggan.launcher.base.adapter.AllAppAdapter");
            var AllAppBarView = Java.use("com.qinggan.launcher.base.allapp.AllAppBarView");
            var AppLauncher = Java.use("com.qinggan.launcher.base.utils.AppLauncher");
            var JavaString = Java.use("java.lang.String");
            var pm = ctx().getPackageManager();
            var installedSnapshot = null;
            var iconCache = {};
            var packageRefreshTimer = null;
            var FLAG_SYSTEM = 0x00000001;
            var SYNTHETIC_PREFIX = "__voyahtune_allapps__:";
            var resourceTemplate = null;

            function launchAllApp(pkg, screenId) {
                try {
                    if (screenId !== 0 && screenId !== 1) {
                        Log.e(TAG, "[allapps] reject non-physical display=" + screenId + " for " + pkg);
                        return false;
                    }
                    var intent = pm.getLaunchIntentForPackage(pkg);
                    if (intent === null) return false;
                    intent.addFlags(0x10000000); // FLAG_ACTIVITY_NEW_TASK
                    AppLauncher.startApp(ctx(), intent, screenId);
                    Log.i(TAG, "[allapps] launch " + pkg + " display=" + screenId);
                    return true;
                } catch (e) {
                    Log.e(TAG, "[allapps] launch " + pkg + ": " + e);
                    return false;
                }
            }

            function fieldValue(obj, name) {
                try { return obj[name].value; } catch (direct) {}
                var c = obj.getClass();
                while (c !== null) {
                    try {
                        var f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (ignored) {
                        try { c = c.getSuperclass(); } catch (end) { c = null; }
                    }
                }
                return null;
            }

            function snapshotInstalled() {
                if (installedSnapshot !== null) return installedSnapshot;
                var result = [];
                var installed = pm.getInstalledApplications(0);
                for (var i = 0; i < installed.size(); i++) {
                    try {
                        var ai = installed.get(i);
                        var pkg = "" + ai.packageName.value;
                        var flags = Number(ai.flags.value);
                        if ((flags & FLAG_SYSTEM) !== 0 || pkg === "com.qinggan.app.launcher") continue;
                        if (pm.getLaunchIntentForPackage(pkg) === null) continue;
                        result.push(pkg);
                    } catch (ignored) {}
                }
                installedSnapshot = result;
                Log.i(TAG, "[allapps] cached launchable user apps=" + result.length);
                return installedSnapshot;
            }

            // OEM bind безусловно вызывает Resources.getText(nameRes) и SkinResourceManager.getDrawable(icon).
            // AppBean(0, 0, pkg), который использовал voboost, поэтому падает ещё до нашего post-bind.
            // Берём валидные placeholder-ресурсы из первого штатного app-bean, а после OEM bind заменяем
            // их настоящими label/icon целевого пакета.
            function findAppTemplate(list) {
                if (list === null || list === undefined) return resourceTemplate;
                for (var i = 0; i < list.size(); i++) {
                    try {
                        var bean = Java.cast(list.get(i), AppBean);
                        if (Number(bean.getType()) === 1 && Number(bean.getIcon()) > 0
                                && Number(bean.getNameRes()) > 0) {
                            resourceTemplate = {
                                icon: Number(bean.getIcon()),
                                name: Number(bean.getNameRes())
                            };
                            return resourceTemplate;
                        }
                    } catch (ignored) {}
                }
                return resourceTemplate;
            }

            function addMissingApps(list) {
                var existing = {};
                for (var i = 0; i < list.size(); i++) {
                    try {
                        var current = Java.cast(list.get(i), AppBean);
                        existing["pkg:" + current.getPackageName()] = true;
                    } catch (ignored) {}
                }
                var apps = snapshotInstalled();
                var template = null;
                for (var j = 0; j < apps.length; j++) {
                    var pkg = apps[j];
                    if (existing["pkg:" + pkg]) continue;
                    try {
                        if (template === null) template = findAppTemplate(list);
                        // На редкой конфигурации passenger OEM-list может быть пустым. Ресурсы обоих
                        // списков принадлежат одному launcher-base APK, поэтому безопасно взять шаблон
                        // из main list, вызвав именно оригинальный getAllApps без рекурсии в hook.
                        if (template === null && getAll !== null && getAll !== undefined) {
                            template = findAppTemplate(getAll.call(Data, 0));
                        }
                        if (template === null) {
                            Log.e(TAG, "[allapps] no valid OEM app template; cannot safely add " + pkg);
                            return;
                        }
                        var bean = AppBean.$new(template.icon, template.name, pkg);
                        bean.setSubType(SYNTHETIC_PREFIX + pkg);
                        list.add(bean);
                        existing["pkg:" + pkg] = true;
                    } catch (e) { Log.e(TAG, "[allapps] add " + pkg + ": " + e); }
                }
            }

            function beanAt(adapter, position) {
                var beans = fieldValue(adapter, "mAppBeans");
                if (beans === null || position < 0 || position >= beans.size()) return null;
                return Java.cast(beans.get(position), AppBean);
            }

            function syntheticPackage(bean) {
                if (bean === null) return null;
                try {
                    var pkg = "" + bean.getPackageName();
                    var subType = "" + bean.getSubType();
                    if (!pkg || subType !== SYNTHETIC_PREFIX + pkg) return null;
                    return pkg;
                } catch (ignored) {
                    return null;
                }
            }

            function loadIcon(pkg) {
                var icon = iconCache[pkg];
                if (!icon) {
                    icon = pm.getApplicationIcon(pkg);
                    try { icon = Java.retain(icon); } catch (ignored) {}
                    iconCache[pkg] = icon;
                }
                return icon;
            }

            function invalidateIconCache(packageName) {
                var keys = packageName ? [packageName] : Object.keys(iconCache);
                for (var i = 0; i < keys.length; i++) {
                    var cached = iconCache[keys[i]];
                    if (cached) {
                        try { cached.$dispose(); } catch (ignored) {}
                    }
                    delete iconCache[keys[i]];
                }
            }

            function loadLabel(pkg) {
                // PackageManager label зависит от текущей locale. Не кэшируем его на жизнь launcher,
                // иначе payload 10001 после смены языка снова нарисует прежнюю подпись.
                return "" + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            }

            function physicalScreenId(owner, view) {
                var rawScreenId = fieldValue(owner, "mScreenId");
                var screenId = (rawScreenId === null || rawScreenId === undefined)
                        ? -1 : Number(rawScreenId);
                if (screenId !== 0 && screenId !== 1 && view !== null) {
                    try {
                        var display = view.getDisplay();
                        screenId = display !== null ? Number(display.getDisplayId()) : -1;
                    } catch (ignored) {}
                }
                return (screenId === 0 || screenId === 1) ? screenId : -1;
            }

            function finishBoundItem(adapter, holder, position) {
                var bean = beanAt(adapter, position);
                var pkg = syntheticPackage(bean);
                if (pkg === null) return;
                var icon = loadIcon(pkg);
                var label = loadLabel(pkg);
                var iconView = fieldValue(holder, "iconView");
                var nameView = fieldValue(holder, "nameView");
                if (iconView !== null && icon) iconView.setBackground(icon);
                if (nameView !== null) nameView.setText(JavaString.$new(label));
            }

            // Владельцем штатного listener является AllAppBarView, и именно у него хранится точный
            // mScreenId. Перехватываем только наши записи по tag, не меняя listener RecyclerView-holder:
            // так recycling обычных OEM-плиток не может унаследовать чужой package.
            var allAppClick = AllAppBarView.onClick.overload('android.view.View');
            allAppClick.implementation = function (view) {
                try {
                    var tagged = view !== null ? view.getTag() : null;
                    var bean = tagged !== null ? Java.cast(tagged, AppBean) : null;
                    var pkg = syntheticPackage(bean);
                    if (pkg !== null) {
                        var screenId = physicalScreenId(this, view);
                        if (screenId < 0) {
                            Log.e(TAG, "[allapps] owner has no physical screen for " + pkg);
                            return;
                        }
                        if (launchAllApp(pkg, screenId)) {
                            try { this.dismiss(); } catch (ignored) {}
                        }
                        return;
                    }
                } catch (e) { Log.e(TAG, "[allapps] owner click: " + e); }
                return allAppClick.call(this, view);
            };

            var bind = Adapter.onBindViewHolder.overload(
                    'com.qinggan.launcher.base.adapter.AllAppAdapter$AppViewHolder', 'int');
            bind.implementation = function (holder, position) {
                bind.call(this, holder, position);
                try {
                    finishBoundItem(this, holder, position);
                } catch (e) { Log.e(TAG, "[allapps] bind: " + e); }
            };

            // Theme/state refreshes use the payload overload and can overwrite the real icon with the
            // placeholder. Re-apply the custom presentation after every such OEM update as well.
            try {
                var bindPayload = Adapter.onBindViewHolder.overload(
                        'com.qinggan.launcher.base.adapter.AllAppAdapter$AppViewHolder',
                        'int', 'java.util.List');
                bindPayload.implementation = function (holder, position, payloads) {
                    bindPayload.call(this, holder, position, payloads);
                    try {
                        finishBoundItem(this, holder, position);
                    } catch (e) { Log.e(TAG, "[allapps] payload bind: " + e); }
                };
            } catch (e) { Log.e(TAG, "[allapps] payload bind hook unavailable: " + e); }

            // На части OD launcher пассажирская home-лента читает тот же mSecondAllApps через отдельный
            // SecondAllAppAdapter. Если класс присутствует, его тоже надо декорировать и перехватить
            // owner-click; иначе глобально добавленные записи были бы placeholder-плитками без запуска.
            var SecondAdapter = null;
            try {
                SecondAdapter = Java.use("com.qinggan.secondlauncher.adapter.SecondAllAppAdapter");
            } catch (absent) {
                Log.i(TAG, "[allapps] optional SecondAllAppAdapter is absent");
            }
            if (SecondAdapter !== null) {
                try {
                    var SecondFragment = Java.use("com.qinggan.secondlauncher.fragment.SecondMainFragment");
                    var secondBind = SecondAdapter.onBindViewHolder.overload(
                            'com.qinggan.secondlauncher.adapter.SecondAllAppAdapter$ViewHolder', 'int');
                    secondBind.implementation = function (holder, position) {
                        secondBind.call(this, holder, position);
                        try {
                            var list = fieldValue(this, "allAppList");
                            if (list === null || position < 0 || position >= list.size()) return;
                            var bean = Java.cast(list.get(position), AppBean);
                            var pkg = syntheticPackage(bean);
                            if (pkg === null) return;
                            var iconView = fieldValue(holder, "iconView");
                            var nameView = fieldValue(holder, "nameView");
                            var icon = loadIcon(pkg);
                            if (iconView !== null && icon) iconView.setImageDrawable(icon);
                            if (nameView !== null) nameView.setText(JavaString.$new(loadLabel(pkg)));
                        } catch (e) { Log.e(TAG, "[allapps] passenger rail bind: " + e); }
                    };

                    var secondClick = SecondFragment.onItemClick.overload(
                            'com.qinggan.launcher.base.bean.AppBean');
                    secondClick.implementation = function (bean) {
                        try {
                            var pkg = syntheticPackage(bean);
                            if (pkg !== null) {
                                launchAllApp(pkg, 1);
                                return;
                            }
                        } catch (e) { Log.e(TAG, "[allapps] passenger rail click: " + e); }
                        return secondClick.call(this, bean);
                    };
                    Log.i(TAG, "[allapps] passenger home rail hooks installed");
                } catch (e) {
                    // Passenger rail is optional. ABI drift here must not prevent the full-screen
                    // driver/passenger lists from receiving their getAllApps hook below.
                    Log.e(TAG, "[allapps] optional passenger rail hooks unavailable: " + e);
                }
            }

            // Ставим data hook последним: если обязательный renderer/click ABI выше разошёлся с
            // прошивкой, synthetic entries не успеют попасть в разделяемый OEM list.
            var getAll = Data.getAllApps.overload('int');
            getAll.implementation = function (screenId) {
                var list = getAll.call(Data, screenId);
                if ((screenId === 0 || screenId === 1) && list !== null) addMissingApps(list);
                return list;
            };

            try {
                // AllAppDataManager.reload() сам очищает/пересобирает mMainAllApps и mSecondAllApps,
                // затем зовёт onAppReload() у AllAppBarView и SecondMainFragment. Их повторные
                // getAllApps(0/1) проходят через хук выше, поэтому synthetic entries возвращаются до
                // notify/setAllAppList открытых адаптеров. Не мутируем OEM-списки параллельно с reload.
                var reloadData = Data.reload.overload();

                function schedulePackageRefresh(action, packageName) {
                    // Инвалидация сразу: если UI запросит список до debounce, он уже получит
                    // свежий PackageManager snapshot. Штатный reload через 300 ms доведёт списки/UI до
                    // консистентного состояния. REMOVE+ADD при APK update схлопываются в один reload.
                    installedSnapshot = null;
                    invalidateIconCache(packageName);
                    if (packageRefreshTimer !== null) clearTimeout(packageRefreshTimer);
                    packageRefreshTimer = setTimeout(function () {
                        packageRefreshTimer = null;
                        Java.scheduleOnMainThread(function () {
                            try {
                                reloadData.call(Data);
                                Log.i(TAG, "[allapps] package refresh action=" + action
                                        + " package=" + packageName);
                            } catch (e) { Log.e(TAG, "[allapps] package refresh failed: " + e); }
                        });
                    }, 300);
                }

                // Dynamic receiver нужен именно в процессе OEM launcher: manifest VoyahTune не может
                // обновить его in-memory RecyclerView. data-scheme "package" обязателен для package actions.
                var PackageReceiver = Java.registerClass({
                    name: "ru.big.town.dock.AllAppsPackageReceiver",
                    superClass: Java.use("android.content.BroadcastReceiver"),
                    methods: {
                        onReceive: {
                            returnType: "void",
                            argumentTypes: ["android.content.Context", "android.content.Intent"],
                            implementation: function (context, intent) {
                                try {
                                    var action = intent !== null ? "" + intent.getAction() : "";
                                    if (action !== "android.intent.action.PACKAGE_ADDED"
                                            && action !== "android.intent.action.PACKAGE_REMOVED"
                                            && action !== "android.intent.action.PACKAGE_CHANGED") return;
                                    var data = intent.getData();
                                    var packageName = data !== null ? "" + data.getSchemeSpecificPart() : "";
                                    schedulePackageRefresh(action, packageName);
                                } catch (e) { Log.e(TAG, "[allapps] package receiver: " + e); }
                            }
                        }
                    }
                });
                var packageFilter = Java.use("android.content.IntentFilter").$new();
                packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
                packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
                packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
                packageFilter.addDataScheme("package");
                var packageReceiver = PackageReceiver.$new();
                var packageSdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
                if (packageSdk >= 33) {
                    ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                        'android.content.IntentFilter', 'int').call(ctx(), packageReceiver, packageFilter, 0x2);
                } else {
                    ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                        'android.content.IntentFilter').call(ctx(), packageReceiver, packageFilter);
                }
                Log.i(TAG, "[allapps] package receiver registered (sdk=" + packageSdk + ")");
            } catch (e) {
                // Старая/другая прошивка без reload не должна отключать базовое добавление
                // synthetic apps: getAllApps/bind/click хуки уже установлены и остаются рабочими.
                Log.e(TAG, "[allapps] event refresh unavailable: " + e);
            }
            Log.i(TAG, "[allapps] both physical display list hooks installed");
        } catch (e) {
            // Firmware variant without these launcher-base classes: dock remains fully functional.
            Log.e(TAG, "[allapps] hooks unavailable: " + e);
        }
    }

    try {
        var NavigationBarMain = Java.use(NAV_MAIN);
        var mainFallbackScreen = SHARED_NAV ? -1 : 0;

        // 1) ИКОНКА: переустановка после каждой перекраски темы (иначе штатная тема затрёт наш фон).
        var origUpdateTheme = NavigationBarMain.updateTheme;
        NavigationBarMain.updateTheme.implementation = function () {
            origUpdateTheme.call(this);
            try { updateIcons(this, mainFallbackScreen); } catch (e) {}
        };

        // 1b) ИНИЦИАЛИЗАЦИЯ СЛОТОВ: навбар строит up-view'ы в initScreenUpViews — сразу после него
        //     слоты существуют, применяем иконки. Страховка от гонки: если инъекция прошла ДО создания
        //     навбара (первичный Java.choose ничего не нашёл), иконка всё равно встанет здесь.
        try {
            var origInitUp = NavigationBarMain.initScreenUpViews;
            NavigationBarMain.initScreenUpViews.implementation = function () {
                origInitUp.call(this);
                try { updateIcons(this, mainFallbackScreen); } catch (e) {}
            };
        } catch (e) { Log.e(TAG, "[dock] initScreenUpViews hook skip: " + e); }

        // 2) ПОДСВЕТКА (косметика): reverse-mapping нашего pkg слота → штатный pkg, чтобы родной код чекнул
        //    правильную кнопку. Для нашего VD-хоста (SplitHostActivity) чекаем слот 2 напрямую.
        var origUpdateSelectedApp = NavigationBarMain.updateSelectedApp;
        NavigationBarMain.updateSelectedApp.implementation = function (packageName, activityName) {
            // Запоминаем приложение переднего плана ДЛЯ СВОЕГО ЭКРАНА (см. dockKept/dismiss).
            try {
                var sid = managedScreenId(this, mainFallbackScreen);
                if (sid === 0 || sid === 1) {
                    fgByScreen[sid].pkg = "" + (packageName || "");
                    fgByScreen[sid].act = "" + (activityName || "");
                }
            } catch (e) {}
            var sid = managedScreenId(this, mainFallbackScreen);
            if (sid < 0) return origUpdateSelectedApp.call(this, packageName, activityName);
            try {
                // Наш VD-хост запущен по клику слота → чекнуть именно тот слот, что нажали (lastSlot).
                if (packageName === OUR_PKG && ("" + activityName).indexOf("SplitHostActivity") >= 0) {
                    var v = (lastSlot[sid] === 1) ? this.mScreenUpItemView1.value
                          : (lastSlot[sid] === 2) ? this.mScreenUpItemView2.value : null;
                    if (v) { v.setChecked(true); return; }
                }
                // Реверс-маппинг: наш pkg слота → штатный pkg, чтобы родной код подсветил правильную кнопку.
                if (dockPackage(sid, 1, false) !== "none" && packageName === dockPackage(sid, 1, false)) packageName = STOCK_SLOT_PKG[1];
                else if (dockPackage(sid, 2, false) !== "none" && packageName === dockPackage(sid, 2, false)) packageName = STOCK_SLOT_PKG[2];
            } catch (e) {}
            return origUpdateSelectedApp.call(this, packageName, activityName);
        };

        // 3) КЛИК: слот определяем сравнением view.getId() с getId() закэшированных полей (НЕ по индексу).
        //    Совпал + pkg установлен → обычная задача на display этого дока; иначе штатный onClick.
        NavigationBarMain.onClick.implementation = function (view) {
            var sid = managedScreenId(this, mainFallbackScreen);
            if (sid < 0) { this.onClick(view); return; }
            try {
                var viewId = view.getId();
                if (currentScreenLiftType() === 1) {
                    if (viewId === this.mScreenUpHomeView.value.getId()) {
                        this.startLauncherMain(true);
                        return;
                    }
                    if (viewId === this.mScreenUpAllAppView.value.getId()) {
                        this.openAllApp();
                        return;
                    }
                }
                var id1 = this.mScreenUpItemView1.value.getId();
                var id2 = this.mScreenUpItemView2.value.getId();
                if (viewId === id1) {
                    var p1 = dockPackage(sid, 1, true);
                    if (isInstalled(p1)) { lastSlot[sid] = 1; launchFreeform(p1, sid); return; }
                }
                if (viewId === id2) {
                    var p2 = dockPackage(sid, 2, true);
                    if (isInstalled(p2)) { lastSlot[sid] = 2; launchFreeform(p2, sid); return; }
                }
            } catch (e) { Log.e(TAG, "[dock] onClick err: " + e); }
            this.onClick(view);
        };

        // OEM compact mode normally replaces the complete dock with a separate one-button view.
        // Keep the regular view and reduce it to Home + slot1 + slot2 + All Apps instead.
        try {
            var origDoScreenLift = NavigationBarMain.doScreenLift.overload('int');
            origDoScreenLift.implementation = function (type) {
                var result = origDoScreenLift.call(this, type);
                try { applyScreenLiftDock(this, type, mainFallbackScreen); } catch (e) {}
                return result;
            };
        } catch (e) { Log.e(TAG, "[dock] doScreenLift hook skip: " + e); }

        // OD firmware has a separate passenger class; PI reaches passenger instances through the
        // shared hooks above. Passenger hooks intentionally contain no long-press behavior.
        if (!SHARED_NAV && NAV_SECOND !== null) {
            try {
                var NavigationBarSecond = Java.use(NAV_SECOND);
                if (declaresMethod(NavigationBarSecond, "updateTheme")) {
                    var secondUpdateTheme = NavigationBarSecond.updateTheme;
                    NavigationBarSecond.updateTheme.implementation = function () {
                        secondUpdateTheme.call(this);
                        try { updateIcons(this, 1); } catch (e) {}
                    };
                }
                try {
                    if (declaresMethod(NavigationBarSecond, "initScreenUpViews")) {
                        var secondInitUp = NavigationBarSecond.initScreenUpViews;
                        NavigationBarSecond.initScreenUpViews.implementation = function () {
                            secondInitUp.call(this);
                            try { updateIcons(this, 1); } catch (e) {}
                        };
                    }
                } catch (e) { Log.e(TAG, "[dock] passenger initScreenUpViews skip: " + e); }
                if (declaresMethod(NavigationBarSecond, "updateSelectedApp")) {
                    var secondSelected = NavigationBarSecond.updateSelectedApp;
                    NavigationBarSecond.updateSelectedApp.implementation = function (packageName, activityName) {
                        fgByScreen[1].pkg = "" + (packageName || "");
                        fgByScreen[1].act = "" + (activityName || "");
                        try {
                            if (dockPackage(1, 1, false) !== "none" && packageName === dockPackage(1, 1, false)) packageName = STOCK_SLOT_PKG[1];
                            else if (dockPackage(1, 2, false) !== "none" && packageName === dockPackage(1, 2, false)) packageName = STOCK_SLOT_PKG[2];
                        } catch (e) {}
                        return secondSelected.call(this, packageName, activityName);
                    };
                }
                if (declaresMethod(NavigationBarSecond, "onClick")) {
                    NavigationBarSecond.onClick.implementation = function (view) {
                        try {
                            var viewId = view.getId();
                            if (currentScreenLiftType() === 1) {
                                if (viewId === this.mScreenUpHomeView.value.getId()) {
                                    this.startLauncherMain(true);
                                    return;
                                }
                                if (viewId === this.mScreenUpAllAppView.value.getId()) {
                                    this.openAllApp();
                                    return;
                                }
                            }
                            if (viewId === this.mScreenUpItemView1.value.getId()) {
                                var p1 = dockPackage(1, 1, true);
                                if (isInstalled(p1)) { lastSlot[1] = 1; launchFreeform(p1, 1); return; }
                            }
                            if (viewId === this.mScreenUpItemView2.value.getId()) {
                                var p2 = dockPackage(1, 2, true);
                                if (isInstalled(p2)) { lastSlot[1] = 2; launchFreeform(p2, 1); return; }
                            }
                        } catch (e) { Log.e(TAG, "[dock] passenger onClick err: " + e); }
                        this.onClick(view);
                    };
                }
                try {
                    if (declaresMethod(NavigationBarSecond, "doScreenLift")) {
                        var secondLift = NavigationBarSecond.doScreenLift.overload('int');
                        secondLift.implementation = function (type) {
                            var result = secondLift.call(this, type);
                            try { applyScreenLiftDock(this, type, 1); } catch (e) {}
                            return result;
                        };
                    }
                } catch (e) { Log.e(TAG, "[dock] passenger doScreenLift skip: " + e); }
                Log.i(TAG, "[dock] passenger NavigationBarSecond hooks installed (short taps only)");
            } catch (e) { Log.e(TAG, "[dock] passenger hooks unavailable: " + e); }
        }

        // 4) ДОК НЕ ДОЛЖЕН САМ УЕЗЖАТЬ ИЗ-ПОД НАШЕГО FREEFORM-ОКНА/VD-СПЛИТА.
        //    При переносе приложения между экранами система вызывает dismiss() у навбара, и док
        //    анимированно скрывается. Для стороннего приложения это тупик: наш оконный режим оставляет
        //    полосу дока свободной, окно её не перекрывает — но самого дока уже нет, и свернуть
        //    приложение или уйти на главный экран нечем.
        //
        //    Гасим dismiss для любого стороннего приложения, потому что глобальный WindowManager hook
        //    оставляет под ним полосу дока независимо от источника запуска. Аварийно отключить pinning:
        //      settings put global voyahtune_dockpin 0
        //
        //    Хукаем ДВА класса: систему устраивает дёрнуть как сам бар, так и его контроллер.
        function pinDock(clsName, label, fallbackScreen) {
            try {
                var C = Java.use(clsName);
                var origDismiss = C.dismiss;
                C.dismiss.implementation = function () {
                    var sid = 0;
                    try { sid = managedScreenId(this, fallbackScreen); } catch (e) {}
                    if (sid !== 0 && sid !== 1) return origDismiss.call(this);
                    var fg = fgByScreen[sid];
                    var pending = pendingDockLaunch(sid);
                    // Разведочный лог ДО решения: без него «хук не встал» неотличимо от «условие не
                    // сработало». console.log после -e мёртв, поэтому только android.util.Log.
                    try { Java.use("android.util.Log").i("voyahdock",
                            "dismiss ENTER " + label + " screen=" + sid + " fg=" + fg.pkg + " act=" + fg.act
                            + (pending ? " pending=" + pending.pkg + "/" + Math.ceil(pending.remaining) + "ms" : "")); } catch (ee) {}
                    try {
                        if (pending !== null) {
                            try { Java.use("android.util.Log").i("voyahdock", "dismiss BLOCKED " + label
                                    + " pending launch " + pending.pkg); } catch (ee) {}
                            return;
                        }
                        if (dockKept(fg.pkg, fg.act)) {
                            try { Java.use("android.util.Log").i("voyahdock", "dismiss BLOCKED " + label); } catch (ee) {}
                            return;                      // док остаётся на месте
                        }
                    } catch (e) {}
                    return origDismiss.call(this);
                };
                Log.i(TAG, "[dock] dismiss pinned on " + label);
            } catch (e) { Log.e(TAG, "[dock] dismiss hook skip " + label + ": " + e); }
        }
        pinDock(NAV_MAIN, "main/shared bar", mainFallbackScreen);
        pinDock(NAV_MAIN.replace(/\.[^.]+$/, ".NavigationBarController"), "main/shared controller", mainFallbackScreen);
        if (!SHARED_NAV && NAV_SECOND !== null) {
            pinDock(NAV_SECOND, "passenger bar", 1);
            pinDock(NAV_SECOND.replace(/\.[^.]+$/, ".NavigationBarSecondController"), "passenger controller", 1);
            pinDock(NAV_SECOND.replace(/\.[^.]+$/, ".SecondNavigationBarController"), "passenger controller alt", 1);
        }

        // 4b) Fullscreen OEM UI (угловая камера и т.п.) штатно скрывает navigation bar. Блокировка
        // dismiss не помогает, если скрытие произошло ДО возврата к нашему приложению. LauncherModel
        // уже получает TOP_ACTIVITY_CHANGED; после его оригинальной обработки повторно просим показать
        // водительский bar, когда top activity снова относится к приложению с оконным viewport.
        try {
            var TopLM = Java.use("com.qinggan.app.launcher.LauncherModel");
            var AppUtils = Java.use("com.qinggan.launcher.base.utils.AppUtils");
            var AccountConstantUtil = Java.use("com.qinggan.account.AccountConstantUtil");
            var topReceive = TopLM.onReceive.overload('android.content.Context', 'android.content.Intent');
            topReceive.implementation = function (context, intent) {
                var result = topReceive.call(this, context, intent);
                try {
                    if (intent === null || ("" + intent.getAction()) !==
                            "android.intent.action.TOP_ACTIVITY_CHANGED") return result;
                    var displayId = intent.getIntExtra("displayId", -1);
                    if (displayId !== 0) return result;
                    var top = "" + AppUtils.getTopAppInfo(context, displayId, 4);
                    var separator = "|";
                    try { separator = "" + AccountConstantUtil.SEPARATOR.value; } catch (ignored) {}
                    var parts = top.split(separator);
                    var pkg = parts.length > 0 ? parts[0] : "";
                    var act = parts.length > 1 ? parts[1] : "";
                    fgByScreen[0].pkg = pkg;
                    fgByScreen[0].act = act;
                    if (dockKept(pkg, act)) {
                        this.handleUpdateMainNavigationBar(pkg, act, true);
                        Log.i("voyahdock", "TOP_ACTIVITY_CHANGED restored main dock for " + pkg);
                    }
                } catch (e) { Log.e(TAG, "[dock] TOP_ACTIVITY_CHANGED recovery: " + e); }
                return result;
            };
            Log.i(TAG, "[dock] TOP_ACTIVITY_CHANGED recovery installed");
        } catch (e) { Log.e(TAG, "[dock] TOP_ACTIVITY_CHANGED recovery unavailable: " + e); }

        // 5) ПЛАВАЮЩАЯ HOME — подавление ВОЗВРАЩЕНО.
        //    Снимать его было ошибкой. Обоснование при снятии («во freeform-окне кнопка и так не
        //    всплывает») оказалось ложным: наш оконный режим НЕ переводит окно в настоящий freeform —
        //    vd_bypass.js настоящий freeform (windowing mode 5) наоборот пропускает, а обычному
        //    полноэкранному окну лишь переписывает рамки уже ПОСЛЕ раскладки. Для лаунчера приложение
        //    остаётся «сторонним на весь экран», поэтому предикат истинен всегда — и кнопка вылезала
        //    постоянно, даже когда док на месте и она не нужна.
        //
        //    Аварийно вернуть штатное поведение: settings put global voyahtune_floathome 0
        try {
            var floatHomeOff = function () { return cfg("floathome") !== "0"; };
            var LM = Java.use("com.qinggan.app.launcher.LauncherModel");
            LM.isThirdShowFloatApp.overload('java.lang.String').implementation = function (cn) {
                return floatHomeOff() ? false : this.isThirdShowFloatApp(cn);
            };
            Log.i(TAG, "[dock] floating home suppressed (LauncherModel)");
        } catch (e) { Log.e(TAG, "[dock] LauncherModel.isThirdShowFloatApp skip: " + e); }
        try {
            var TAU = Java.use("com.qinggan.launcher.base.drag.ThirdAppUtil");
            TAU.isThirdShowFloatApp.overload('java.lang.String').implementation = function (cn) {
                return cfg("floathome") !== "0" ? false : this.isThirdShowFloatApp(cn);
            };
            Log.i(TAG, "[dock] floating home suppressed (ThirdAppUtil)");
        } catch (e) { Log.e(TAG, "[dock] ThirdAppUtil.isThirdShowFloatApp skip: " + e); }

        // 6) РАЗВЕДКА пути скрытия дока при переносе окна между экранами.
        //    Симптом «перенёс жестом на водительский — док пропал» блокировкой dismiss НЕ лечится,
        //    значит док прячет другой путь. Наиболее вероятный кандидат — оркестрация переноса в самом
        //    лаунчере (LauncherModel.onMoveStart), которая гасит навбары по своей бухгалтерии.
        //    Исходников лаунчера у нас нет, поэтому пока НЕ подменяем — только логируем факт и аргументы,
        //    чтобы подтвердить путь на живой голове (logcat -s voyahdock), а уже потом чинить.
        try {
            var LM2 = Java.use("com.qinggan.app.launcher.LauncherModel");
            var Log2 = Java.use("android.util.Log");
            LM2.onMoveStart.overloads.forEach(function (ov) {
                ov.implementation = function () {
                    try {
                        var a = [];
                        for (var i = 0; i < arguments.length; i++) a.push("" + arguments[i]);
                        Log2.i("voyahdock", "onMoveStart(" + a.join(", ") + ")");
                    } catch (e) {}
                    return ov.apply(this, arguments);        // оригинал ВЫЗЫВАЕМ — это разведка, не правка
                };
            });
            Log.i(TAG, "[dock] onMoveStart traced");
        } catch (e) { Log.e(TAG, "[dock] onMoveStart trace skip: " + e); }

        // Приёмник reload: Native шлёт DOCK_RELOAD после записи voyahtune_dock* → перечитать + перерисовать.
        // ВАЖНО: BroadcastReceiver.onReceive — АБСТРАКТНЫЙ метод. Shorthand-форма registerClass
        // (methods:{onReceive:function(){}}) на этой прошивке НЕ переопределяла абстрактный слот в vtable →
        // AbstractMethodError при доставке брэдкаста → КРЭШ лаунчера (весь UI). Объявляем метод с ЯВНОЙ
        // сигнатурой (returnType/argumentTypes) — это гарантирует конкретный override поверх абстрактного.
        try {
            var Receiver = Java.registerClass({
                name: "ru.big.town.dock.DockReloadReceiver",
                superClass: Java.use("android.content.BroadcastReceiver"),
                methods: {
                    onReceive: {
                        returnType: "void",
                        argumentTypes: ["android.content.Context", "android.content.Intent"],
                        implementation: function (context, intent) {
                            // NB: console.log после eternalize уходит в никуда → лог через android.util.Log
                            // (виден в logcat -s voyahdock), чтобы подтверждать доставку брэдкаста на голове.
                            try { Java.use("android.util.Log").i("voyahdock", "onReceive DOCK_RELOAD"); } catch (e) {}
                            try {
                                refreshCache();
                                setTimeout(updateAllNavbars, 300);   // дать навбару стабилизироваться
                            } catch (e) { Log.e(TAG, "[dock] onReceive err: " + e); }
                        }
                    }
                }
            });
            var IntentFilter = Java.use("android.content.IntentFilter");
            var recv = Receiver.$new();
            var filt = IntentFilter.$new(RELOAD_ACT);
            // На API≥33 форма (receiver, filter) для чужого implicit-broadcast бросает SecurityException —
            // нужен флаг RECEIVER_EXPORTED (0x2). На нашей голове Android 11 (API 30) — обычная 2-арг форма.
            var sdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
            if (sdk >= 33) {
                ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                    'android.content.IntentFilter', 'int').call(ctx(), recv, filt, 0x2);
            } else {
                ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                    'android.content.IntentFilter').call(ctx(), recv, filt);
            }
            Log.i(TAG, "[dock] reload receiver registered: " + RELOAD_ACT + " (sdk=" + sdk + ")");
        } catch (e) { Log.e(TAG, "[dock] receiver reg err: " + e); }

        // Первичная загрузка конфига + отрисовка иконок на уже живых навбарах. Повторы — на случай,
        // если навбар создаётся чуть позже инъекции (на буте load.bin инжектит рано).
        refreshCache();
        installAllAppsHooks();
        setImmediate(updateAllNavbars);
        setTimeout(updateAllNavbars, 800);
        setTimeout(updateAllNavbars, 2500);
        setTimeout(updateAllNavbars, 5000);

        Log.i(TAG, "[dock] NavigationBarMain hooks installed (updateTheme/updateSelectedApp/onClick)");
    } catch (e) {
        // Класс не найден (скрипт заинжектили не в лаунчер, либо CN/другая прошивка) — тихо выходим.
        Log.e(TAG, "[dock] NavigationBarMain not found (not launcher/oversea?): " + e);
    }
});
