// launcherdock.js — переопределение дока ГЛАВНОГО экрана Open Voyah в процессе лаунчера.
// Работаем ТОЛЬКО с водительским/верхним баром. На ОД-прошивках это отдельный класс
// com.qinggan.launcher.navigation.NavigationBarMain (пассажирский NavigationBarSecond не трогаем вовсе).
// На ПИ-прошивках класс com.qinggan.mainlauncher.navigation.NavigationBar ОБЩИЙ для обоих экранов, поэтому
// каждый хук дополнительно гейтится isDriverBar() по mScreenId (фолбэк — displayId вьюхи).
//
// Механика: хук навигационного бара лаунчера, на нашей шине и с нашим запуском на VirtualDisplay:
//   • КОНФИГ — живьём из Settings.Global: voyahtune_dock1 / voyahtune_dock2 (= packageName; "none" = слот
//     не переопределён). Опц. voyahtune_dock1Dpi / voyahtune_dock2Dpi (per-app DPI для VD, int строкой).
//     Пишет их Native (SetModesReceiverDynamic.mirrorDock), читаем как action() в steeringwheelkeys.js.
//   • ИКОНКА слота — через view.setBackground(drawable), НЕ setImageDrawable: картинка слота живёт в
//     background у NoToggleRadioButton. Оригинал бэкапим один раз (getBackground), кастом строим из
//     pm.getApplicationIcon → Bitmap → 50x50 → BitmapDrawable + Java.retain. Всё на main-треде + invalidate.
//     Хук updateTheme переустанавливает иконки после каждой перекраски темы (иначе фон сбрасывается).
//   • КЛИК — onClick сравнивает view.getId() с getId() закэшированных полей mScreenUpItemView1/2. При
//     совпадении и если pkg установлен — запускаем приложение обычным launch-интентом на display 0 и
//     return (оригинал не зовём). Системный хук vd_bypass (layoutWindowLw) ужимает окно справа от дока
//     (freeform всегда on). Отдельный VD/SplitHost для одиночного приложения НЕ используется — VD остаётся
//     под сплит ДВУХ приложений (запускается из RestoreMode).
//   • ДОЛГИЙ ТАП по слоту — если слоту назначен сплит (voyahtune_dockN HasSplit=="1"), шлём Native
//     broadcast OPEN_DOCK_SPLIT (slot) → Native читает детали сплита из Settings.Global и стартует его на
//     VD. Если сплит не назначен — слушатель возвращает false (штатное долгое поведение лаунчера). Назначение
//     сплита слоту делается в VoyahTune («Приложения и разделение экрана» → Системный док).
//   • ПОДСВЕТКА — updateSelectedApp: reverse-mapping (наш pkg слота → штатный pkg, закреплённый за слотом),
//     чтобы родной лаунчер чекнул правильную кнопку. Косметика, не блокер.
//   • RELOAD — приёмник ru.big.town.anative.DOCK_RELOAD перечитывает конфиг и перерисовывает иконки
//     (иконки рисуются проактивно; клик читает конфиг живьём, ему reload не нужен).
Java.perform(function () {
    // Слот → штатный pkg, который родной лаунчер умеет подсвечивать (oversea, главный экран).
    // ВНИМАНИЕ: значения версионно-хрупкие, подтвердить на живой голове H97C.
    var STOCK_SLOT_PKG = { 1: "com.qinggan.bluetoothphone", 2: "com.qinggan.app.music" };
    var NAV_MAIN   = "com.qinggan.launcher.navigation.NavigationBarMain"; // класс навбара в ОД-прошивках
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

    // На ОД-прошивках NavigationBarMain — ОТДЕЛЬНЫЙ класс водительского бара (пассажирский —
    // NavigationBarSecond), поэтому сам факт хука уже ограничивает нас нужным экраном. На ПИ-прошивках
    // класс NavigationBar ОБЩИЙ для обоих экранов и различается только полем mScreenId → без явного гарда
    // хуки залезали на пассажирский док (симптом: пропадала кнопка «меню», сторонние приложения нечем
    // открыть). SHARED_NAV помечает этот случай: при нём неизвестный экран трактуем как «не наш».
    var SHARED_NAV = false;
    try {
        Java.use(NAV_MAIN);
        Log.i(TAG, "OD firmware");
    } catch (e) {
        NAV_MAIN   = "com.qinggan.mainlauncher.navigation.NavigationBar";  // класс навбара в ПИ-прошивках
        SHARED_NAV = true;
        Log.i(TAG, "PI firmware");
    }

    // Номер экрана инстанса навбара: 0 = водительский (наш), 1 = пассажирский, -1 = определить не удалось.
    // Основной источник — поле mScreenId (им же пользуется сам лаунчер). Фолбэк — displayId вьюхи бара:
    // не зависит от приватных полей лаунчера и переживает переименования на другой прошивке.
    function screenIdOf(instance) {
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
        return -1;
    }

    // Гейт всех хуков: работаем ТОЛЬКО с водительским баром. На ОД (класс-специфичный хук) неизвестный
    // экран считаем своим — там чужого бара в этом классе быть не может. На ПИ (общий класс) неизвестный
    // экран считаем ЧУЖИМ: лучше не переопределить свой док, чем сломать пассажирский.
    function isDriverBar(instance) {
        var id = screenIdOf(instance);
        if (id === 0) return true;
        if (id === -1 && !SHARED_NAV) return true;
        if (id === -1 && !isDriverBar._warned) {
            isDriverBar._warned = true;
            try { Log.i(TAG, "screenId неизвестен на общем классе навбара — хуки пропущены"); } catch (e) {}
        }
        return false;
    }

    // Кэш иконочного конфига (для проактивной перерисовки). Клик читает Settings.Global живьём.
    var cache = { dock1: "none", dock2: "none" };
    // Бэкап штатных фонов слотов: originalBg["<screenId>:<viewName>"] = Drawable (один раз на экран+поле,
    // чтобы Drawable одного экрана никогда не попал во вьюху другого — см. updateIcons).
    var originalBg = {};
    // Удержанные Drawable (иначе GC уберёт background).
    var retained = [];
    var MAX_RETAINED_DRAWABLES = 64;
    // Последний нажатый слот (для корректной подсветки нашего VD-хоста в updateSelectedApp).
    var lastSlot = 0;
    // viewId слота дока → номер слота (1/2). Заполняется в updateIcons, читается в долгом тапе слота
    // (устойчиво к нескольким инстансам навбара — ключ по id вью, а не по последнему инстансу).
    var slotByViewId = {};
    // Приложение переднего плана ПО ЭКРАНАМ. Раньше это была одна переменная fgPkg, и она врала:
    // запись шла ДО гейта isDriverBar, поэтому на ПИ (общий класс навбара) пассажирский бар перетирал
    // значение водительского, а на ОД пассажирский бар не хукается вовсе. Решения по доку принимались
    // по чужому экрану.
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
        Log.i(TAG, "[dock] cache: dock1=" + cache.dock1 + " dock2=" + cache.dock2);
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

    // Перерисовка иконок слотов на инстансе навбара. Только слоты 1 и 2 (наш док). Строго на main-треде.
    function updateIcons(instance) {
        try {
            if (!isDriverBar(instance)) return;   // пассажирский бар не трогаем (см. isDriverBar)
            // Ключ бэкапа включает номер экрана: иначе штатный Drawable водительского бара мог быть
            // восстановлен в ОДНОИМЁННОЕ поле пассажирского. Иконка слота живёт в background у
            // NoToggleRadioButton (StateListDrawable) — один такой объект на двух View даёт общее
            // состояние на двоих, и на одном из экранов кнопка резолвится в пустоту, т.е. «исчезает».
            var sid = screenIdOf(instance);
            var map = { "mScreenUpItemView1": cache.dock1, "mScreenUpItemView2": cache.dock2 };
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
                if (av) {
                    var lc = getMenuLongClick();
                    if (lc) { av.setLongClickable(true); av.setOnLongClickListener(lc); }
                }
            } catch (e) { Log.e(TAG, "[dock] menu long-press attach err: " + e); }
            // Долгий тап по слотам 1/2 → открыть назначенный сплит. Регистрируем viewId→slot и вешаем
            // слушатель (идемпотентно, переживает перекраску темы, как и меню-лонгтап выше).
            try {
                var slc = getSlotLongClick();
                var sv1 = instance.mScreenUpItemView1.value;
                var sv2 = instance.mScreenUpItemView2.value;
                if (slc && sv1) { slotByViewId["" + sv1.getId()] = 1; sv1.setLongClickable(true); sv1.setOnLongClickListener(slc); }
                if (slc && sv2) { slotByViewId["" + sv2.getId()] = 2; sv2.setLongClickable(true); sv2.setOnLongClickListener(slc); }
            } catch (e) { Log.e(TAG, "[dock] slot long-press attach err: " + e); }
        } catch (e) { Log.e(TAG, "[dock] updateIcons err: " + e); }
    }

    // Первичный проход + reload: перерисовать иконки на всех живых инстансах NavigationBarMain.
    function updateAllNavbars() {
        try {
            Java.choose(NAV_MAIN, {
                onMatch: function (inst) {
                    Java.scheduleOnMainThread(function () {
                        try { updateIcons(inst); } catch (e) { Log.e(TAG, "[dock] updateAll err: " + e); }
                    });
                },
                onComplete: function () {}
            });
        } catch (e) { Log.e(TAG, "[dock] choose err: " + e); }
    }

    // Freeform-запуск приложения из слота дока ДЕЛЕГИРУЕМ Native (broadcast OPEN_FREEFORM): Native закроет
    // активный VD-сплит (иначе приложение-панель уехало бы с VD с глитчем — чёрное окно) и запустит
    // приложение ЧИСТО на display 0. Дальше системный хук vd_bypass (layoutWindowLw) ужмёт окно справа от
    // дока, ensureActivityConfiguration задаст DPI. Одиночный VD/SplitHost для одного приложения не нужен.
    function launchFreeform(pkg) {
        try {
            var i = Intent.$new("ru.big.town.anative.OPEN_FREEFORM");
            i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "pkg", "" + pkg);
            i.addFlags(0x00000020);   // FLAG_INCLUDE_STOPPED_PACKAGES — добудиться, даже если Native стоплен
            ctx().sendBroadcast(i);
            Log.i(TAG, "[dock] OPEN_FREEFORM -> " + pkg);
        } catch (e) { Log.e(TAG, "[dock] launchFreeform err: " + e); }
    }

    try {
        var NavigationBarMain = Java.use(NAV_MAIN);

        // 1) ИКОНКА: переустановка после каждой перекраски темы (иначе штатная тема затрёт наш фон).
        var origUpdateTheme = NavigationBarMain.updateTheme;
        NavigationBarMain.updateTheme.implementation = function () {
            origUpdateTheme.call(this);
            try { updateIcons(this); } catch (e) {}
        };

        // 1b) ИНИЦИАЛИЗАЦИЯ СЛОТОВ: навбар строит up-view'ы в initScreenUpViews — сразу после него
        //     слоты существуют, применяем иконки. Страховка от гонки: если инъекция прошла ДО создания
        //     навбара (первичный Java.choose ничего не нашёл), иконка всё равно встанет здесь.
        try {
            var origInitUp = NavigationBarMain.initScreenUpViews;
            NavigationBarMain.initScreenUpViews.implementation = function () {
                origInitUp.call(this);
                try { updateIcons(this); } catch (e) {}
            };
        } catch (e) { Log.e(TAG, "[dock] initScreenUpViews hook skip: " + e); }

        // 2) ПОДСВЕТКА (косметика): reverse-mapping нашего pkg слота → штатный pkg, чтобы родной код чекнул
        //    правильную кнопку. Для нашего VD-хоста (SplitHostActivity) чекаем слот 2 напрямую.
        var origUpdateSelectedApp = NavigationBarMain.updateSelectedApp;
        NavigationBarMain.updateSelectedApp.implementation = function (packageName, activityName) {
            // Запоминаем приложение переднего плана ДЛЯ СВОЕГО ЭКРАНА (см. dockKept/dismiss).
            try {
                var sid = screenIdOf(this);
                if (sid === 0 || sid === 1) {
                    fgByScreen[sid].pkg = "" + (packageName || "");
                    fgByScreen[sid].act = "" + (activityName || "");
                } else if (!SHARED_NAV) {   // ОД: этот класс — только водительский бар
                    fgByScreen[0].pkg = "" + (packageName || "");
                    fgByScreen[0].act = "" + (activityName || "");
                }
            } catch (e) {}
            // Пассажирский бар (общий класс на ПИ) — отдаём штатное поведение без изменений.
            if (!isDriverBar(this)) return origUpdateSelectedApp.call(this, packageName, activityName);
            try {
                // Наш VD-хост запущен по клику слота → чекнуть именно тот слот, что нажали (lastSlot).
                if (packageName === OUR_PKG && ("" + activityName).indexOf("SplitHostActivity") >= 0) {
                    var v = (lastSlot === 1) ? this.mScreenUpItemView1.value
                          : (lastSlot === 2) ? this.mScreenUpItemView2.value : null;
                    if (v) { v.setChecked(true); return; }
                }
                // Реверс-маппинг: наш pkg слота → штатный pkg, чтобы родной код подсветил правильную кнопку.
                if (cache.dock1 !== "none" && packageName === cache.dock1) packageName = STOCK_SLOT_PKG[1];
                else if (cache.dock2 !== "none" && packageName === cache.dock2) packageName = STOCK_SLOT_PKG[2];
            } catch (e) {}
            return origUpdateSelectedApp.call(this, packageName, activityName);
        };

        // 3) КЛИК: слот определяем сравнением view.getId() с getId() закэшированных полей (НЕ по индексу).
        //    Совпал + pkg установлен → launchFreeform (окно на display 0) и return; иначе штатный onClick.
        NavigationBarMain.onClick.implementation = function (view) {
            // Пассажирский бар: НЕ перехватываем клик. Иначе тап по пассажирскому доку уходил в
            // launchFreeform → Native запускал приложение на display 0, т.е. на водительском экране.
            if (!isDriverBar(this)) { this.onClick(view); return; }
            try {
                var viewId = view.getId();
                var id1 = this.mScreenUpItemView1.value.getId();
                var id2 = this.mScreenUpItemView2.value.getId();
                if (viewId === id1) {
                    var p1 = cfg("dock1");
                    if (isInstalled(p1)) { lastSlot = 1; launchFreeform(p1); return; }
                }
                if (viewId === id2) {
                    var p2 = cfg("dock2");
                    if (isInstalled(p2)) { lastSlot = 2; launchFreeform(p2); return; }
                }
            } catch (e) { Log.e(TAG, "[dock] onClick err: " + e); }
            this.onClick(view);
        };

        // 4) ДОК НЕ ДОЛЖЕН САМ УЕЗЖАТЬ ИЗ-ПОД СТОРОННЕГО ОКНА.
        //    При переносе приложения между экранами система вызывает dismiss() у навбара, и док
        //    анимированно скрывается. Для стороннего приложения это тупик: наш оконный режим оставляет
        //    полосу дока свободной, окно её не перекрывает — но самого дока уже нет, и свернуть
        //    приложение или уйти на главный экран нечем.
        //
        //    Гасим dismiss ТОЛЬКО когда впереди СТОРОННЕЕ приложение и оконный режим включён. Для
        //    штатных пакетов (их окна разворачиваются на весь экран, оконный режим их не ужимает)
        //    скрытие дока — правильное поведение, его не трогаем: иначе док завис бы поверх
        //    полноэкранного штатного плеера. Аварийно отключить целиком:
        //      settings put global voyahtune_dockpin 0
        //
        //    Хукаем ДВА класса: систему устраивает дёрнуть как сам бар, так и его контроллер.
        function pinDock(clsName, label) {
            try {
                var C = Java.use(clsName);
                var origDismiss = C.dismiss;
                C.dismiss.implementation = function () {
                    var sid = 0;
                    try { sid = (screenIdOf(this) === 1) ? 1 : 0; } catch (e) {}
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
        pinDock(NAV_MAIN, "bar");
        pinDock(NAV_MAIN.replace(/\.[^.]+$/, ".NavigationBarController"), "controller");

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
