// launcherdock.js — переопределение водительского дока и стабилизация доков обоих экранов Open Voyah.
// Только ПИ-прошивка: com.qinggan.app.launcher 1.0.6 (versionCode 14), Android 11. Оба экрана обслуживает
// один класс com.qinggan.mainlauncher.navigation.NavigationBar, экран различается полем mScreenId.
// NavigationBar одновременно и контроллер окна (mRootView/mLp/mWindowManager, show/dismiss/doScreenLift);
// оба инстанса живут в единственном LauncherModel (mMainScreenNavigationBar/mSecondScreenNavigationBar).
//
// Механика: хук навигационного бара и списка приложений штатного лаунчера:
//   • КОНФИГ — живьём из Settings.Global: voyahtune_dock1/2
//     (= packageName; "none" = слот не переопределён).
//     Пишет их Native (SetModesReceiverDynamic.mirrorDock), читаем как action() в steeringwheelkeys.js.
//   • ИКОНКА слота — через view.setBackground(drawable), НЕ setImageDrawable: картинка слота живёт в
//     background у RadioButton. Оригинал бэкапим один раз (getBackground), кастом строим из
//     pm.getApplicationIcon → Bitmap → 50x50 → BitmapDrawable + Java.retain. Всё на main-треде + invalidate.
//     Хук updateTheme переустанавливает иконки после каждой перекраски темы (иначе фон сбрасывается).
//   • КЛИК — на водительском onClick сравнивает view.getId() с mScreenUpItemView1/2. При совпадении и
//     если pkg установлен — делегируем Native. Пассажирский бар остаётся полностью штатным.
//     Native запускает обычную задачу целевого пакета на display 0, а vd_bypass ужимает её
//     WindowManager-рамку. VD — только для split-пресетов.
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
//     без polling. Клик идёт через OEM AppLauncher с mScreenId владельца All Apps,
//     поэтому top activity остаётся целевым package на соответствующем физическом display.
//   • ВОЗВРАТ ИЗ FULLSCREEN/ПЕРЕНОСА — TOP_ACTIVITY_CHANGED повторно просит штатный LauncherModel
//     показать navigation bar нужного физического экрана. Во время OEM transfer короткий deadline-guard
//     не даёт onMoveStart удалить оба бара до того, как foreground-кэш обновится на destination.
Java.perform(function () {
    // Слот → штатный pkg, который родной лаунчер умеет подсвечивать (главный экран).
    var STOCK_SLOT_PKG = { 1: "com.qinggan.bluetoothphone", 2: "com.qinggan.app.music" };
    var NAV_BAR      = "com.qinggan.mainlauncher.navigation.NavigationBar";
    var MODEL        = "com.qinggan.app.launcher.LauncherModel";
    var RELOAD_ACT   = "ru.big.town.anative.DOCK_RELOAD";
    var LAUNCHER_PKG = "com.qinggan.app.launcher";    // сам штатный лаунчер (Home обоих экранов)
    var OUR_PKG      = "ru.big.town.anative";         // наш VD-хост (SplitHostActivity) для подсветки
    var RESTORE_PKG  = "ru.big.town.restoremode";     // VoyahTune (UI) — открывается долгим тапом по «меню»
    // AccountConstantUtil.SEPARATOR на ПИ = "/": LauncherModel так же делит ответ AppUtils.getTopAppInfo.
    var TOP_SEPARATOR = "/";

    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var SystemClock    = Java.use("android.os.SystemClock");
    var SystemProps    = Java.use("android.os.SystemProperties");
    var Intent         = Java.use("android.content.Intent");
    var IntentFilter   = Java.use("android.content.IntentFilter");
    var Receiver       = Java.use("android.content.BroadcastReceiver");
    var LongClick      = Java.use("android.view.View$OnLongClickListener");
    var Bitmap         = Java.use("android.graphics.Bitmap");
    var BitmapConfig   = Java.use("android.graphics.Bitmap$Config");
    var BitmapDrawable = Java.use("android.graphics.drawable.BitmapDrawable");
    var Canvas         = Java.use("android.graphics.Canvas");
    var Log            = Java.use("android.util.Log");
    var LauncherAppUtils = Java.use("com.qinggan.launcher.base.utils.AppUtils");

    var TAG  = "vt_launcherdock";
    var DLOG = "voyahdock";   // тег живых разведочных логов (logcat -s voyahdock), общий с Native

    // ЗАЩИТА ОТ ПОВТОРНОЙ ИНЪЕКЦИИ В ЖИВОЙ АГЕНТ. Второй агент навесил бы хуки поверх первых: вызов
    // оригинала из второго хука уходит снова в первый хук, onMoveStart уходил в рекурсию.
    // Маркер — java.lang.System property (общая для процесса); снимается в rpc.exports.dispose, когда
    // скрипт выгружают (frida-inject умер/loaderFrida перезапущен) и хуки откатываются.
    var JavaSystem = Java.use("java.lang.System");
    var AGENT_MARK = "ru.big.town.dock.agent";
    if (cleanJavaString(JavaSystem.getProperty(AGENT_MARK)) === "1") {
        Log.w(TAG, "[dock] agent already installed in this process — skipping second injection");
        return;
    }
    JavaSystem.setProperty(AGENT_MARK, "1");

    function cleanJavaString(value) {
        if (value === null || value === undefined) return "";
        var result = "" + value;
        return (result === "null" || result === "undefined") ? "" : result;
    }

    // Все используемые поля объявлены прямо в классах ПИ-лаунчера, Frida отдаёт их типизированными.
    function field(instance, name) {
        try { return instance[name].value; } catch (e) { return null; }
    }

    // 0 = водительский бар (наш), 1 = пассажирский, -1 = не бар/не определён.
    function screenOf(bar) {
        var id = field(bar, "mScreenId");
        return (id === 0 || id === 1) ? id : -1;
    }

    function dockViews(bar) {
        return {
            up: field(bar, "mScreenUpView"),
            down: field(bar, "mScreenDownView"),
            group: field(bar, "mScreenUpRadioGroup"),
            home: field(bar, "mScreenUpHomeView"),
            allApps: field(bar, "mScreenUpAllAppView"),
            slot1: field(bar, "mScreenUpItemView1"),
            slot2: field(bar, "mScreenUpItemView2"),
            extra1: field(bar, "mScreenUpItemView3"),
            extra2: field(bar, "mScreenUpItemView4"),
            temperature: field(bar, "mScreenUpTemperatureContentView")
        };
    }

    // Кэш иконочного конфига водительского дока (для проактивной перерисовки).
    var cache = { dock1: "none", dock2: "none", fullscreen: {} };
    // Бэкап штатных фонов водительских слотов (один раз на поле).
    var originalBg = {};
    // Удержанные Drawable (иначе GC уберёт background).
    var retained = [];
    var MAX_RETAINED_DRAWABLES = 64;
    // Последний нажатый слот водительского дока (для подсветки нашего VD-хоста в updateSelectedApp).
    var lastSlot = 0;
    // viewId слота дока → номер слота (1/2). Заполняется в updateIcons, читается в долгом тапе слота.
    var slotByViewId = {};
    // Приложение переднего плана ПО ЭКРАНАМ: пассажирский бар не должен перетирать foreground водителя.
    var fgByScreen = { 0: { pkg: "", act: "" }, 1: { pkg: "", act: "" } };
    // onMoveStart ставит UI-runnable асинхронно и последовательно вызывает dismiss обоих баров.
    // Поэтому guard хранится по source display и не consume-ится первым dismiss.
    var moveDockGuards = {
        0: { deadline: 0, generation: 0, pkg: "" },
        1: { deadline: 0, generation: 0, pkg: "" }
    };
    var moveDockGeneration = 0;
    // Состояние баров НА МОМЕНТ НАЧАЛА переноса. Штатный лаунчер восстанавливает бар по
    // mMain/SecondNavigationBarLastShow только при ОТМЕНЕ переноса (posX == 0), поэтому снимок
    // держим сами и доигрываем его после onMoveStop.
    var moveBarSnapshot = { 0: null, 1: null };
    // Единственный LauncherModel (создаётся в LauncherApplication.onCreate) — владелец обоих баров.
    var launcherModel = null;

    function rememberModel(model) {
        if (launcherModel === null && model !== null) launcherModel = Java.retain(model);
        return launcherModel;
    }

    function findModel() {
        if (launcherModel !== null) return launcherModel;
        Java.choose(MODEL, {
            onMatch: function (inst) { rememberModel(inst); return "stop"; },
            onComplete: function () {}
        });
        return launcherModel;
    }

    function barOf(model, sid) {
        if (model === null) return null;
        return field(model, sid === 0 ? "mMainScreenNavigationBar" : "mSecondScreenNavigationBar");
    }

    function activeMoveDockGuard() {
        if (cfg("dockpin") === "0" || cfg("freeform") === "0") return null;
        var now = Number(SystemClock.elapsedRealtime());
        for (var sid = 0; sid <= 1; sid++) {
            var guard = moveDockGuards[sid];
            if (guard.deadline > now && guard.deadline - now <= 10000) {
                return { screen: sid, pkg: guard.pkg, remaining: guard.deadline - now };
            }
        }
        return null;
    }

    // Guard живёт только до момента, когда мы сами начинаем приводить бары в нужное состояние:
    // иначе наш же dismiss-хук заблокирует ЗАКОНОМЕРНОЕ скрытие бара на экране, с которого ушло приложение.
    function clearMoveDockGuards() {
        for (var sid = 0; sid <= 1; sid++) {
            moveDockGuards[sid].deadline = 0;
            moveDockGuards[sid].pkg = "";
        }
    }

    // Снимок видимости обоих баров перед переносом. Берём только на ПЕРВОМ onMoveStart переноса:
    // штатный лаунчер снимает бары из UI-runnable, и повторный снимок уже прочитал бы нули.
    function snapshotMoveDockState(model) {
        for (var sid = 0; sid <= 1; sid++) {
            var shown = null;
            try {
                var bar = barOf(model, sid);
                if (bar !== null) shown = !!bar.isShowing();
            } catch (e) { shown = null; }
            moveBarSnapshot[sid] = shown;
        }
        Log.i(DLOG, "move snapshot main=" + moveBarSnapshot[0] + " second=" + moveBarSnapshot[1]);
    }

    // Штатные пакеты, которым МОЖНО скрывать док: их окна оконный режим не ужимает.
    // ВАЖНО: список должен соответствовать блэклисту ffBlacklisted в vd_bypass.js.
    // ИСКЛЮЧЕНИЕ — ru.big.town: решение по ним принимает dockKept() по имени активити.
    var STOCK_PREFIX = ["com.android", "com.qinggan", "com.pateo", "com.baidu", "com.huawei",
                        "com.iflytek", "com.iland", "com.mega", "com.qti", "com.qualcomm",
                        "com.tencent", "com.nng.igo.primong", "com.bz.CA08"];
    function isStockPkg(pkg) {
        pkg = cleanJavaString(pkg);
        if (!pkg) return true;                                   // неизвестно → считаем штатным (не мешаем)
        if (pkg === "com.android.settings" || pkg === "com.android.documentsui") return false;
        for (var i = 0; i < STOCK_PREFIX.length; i++) if (pkg.indexOf(STOCK_PREFIX[i]) === 0) return true;
        return false;
    }

    function fullscreenPackageSet(csv) {
        var out = {};
        if (csv && csv !== "none") {
            var packages = csv.split(",");
            for (var i = 0; i < packages.length; i++) {
                var pkg = cleanJavaString(packages[i]);
                if (pkg) out[pkg] = true;
            }
        }
        return out;
    }

    function isUserFullscreen(pkg) {
        pkg = cleanJavaString(pkg);
        return !!pkg && cache.fullscreen[pkg] === true;
    }

    // Наши активити, которые САМИ отступают на полосу родного дока — под ними док обязан остаться.
    function ourInsetActivity(act) {
        act = cleanJavaString(act);
        return act.indexOf("SplitHostActivity") >= 0
            || act.indexOf("restoremode.MainActivity") >= 0
            || act.indexOf("AdvanceActivity") >= 0
            || act.indexOf("TripHistoryActivity") >= 0;
    }

    // ЕДИНОЕ условие «док должен остаться под этим окном».
    function dockKept(pkg, act) {
        pkg = cleanJavaString(pkg);
        act = cleanJavaString(act);
        if (cfg("dockpin") === "0" || cfg("freeform") === "0") return false;
        if (!pkg) return false;                                  // неизвестно → не мешаем штатному
        if (isUserFullscreen(pkg)) return false;                  // пользователь явно выбрал полный экран
        if (pkg.indexOf("ru.big.town") === 0) return ourInsetActivity(act);
        return !isStockPkg(pkg);
    }

    // Домашний экран самого лаунчера. Водительский Home (MainActivity) штатно живёт ВМЕСТЕ с баром,
    // а SecondMainActivity свой контент под бар не отступает — там бар обязан уйти.
    function launcherHomeDock(screenId, pkg, act) {
        if (pkg !== LAUNCHER_PKG) return null;
        if (act.indexOf("SecondMainActivity") >= 0) return false;
        return screenId === 0 ? true : null;
    }

    // ЕДИНАЯ политика видимости бара на экране: true — показать, false — скрыть, null — решение
    // остаётся за штатным лаунчером (обычные штатные приложения мы не трогаем).
    function desiredDockVisible(screenId, pkg, act) {
        pkg = cleanJavaString(pkg);
        act = cleanJavaString(act);
        if (!pkg) return null;
        if (isUserFullscreen(pkg)) return false;
        if (dockKept(pkg, act)) return true;
        return launcherHomeDock(screenId, pkg, act);
    }

    // Native публикует guard одной строкой "elapsedDeadline|package" непосредственно перед
    // startActivity. Это закрывает окно гонки dismiss → updateSelectedApp при запуске со звёздочки.
    function pendingDockLaunch(screenId) {
        if (cfg("dockpin") === "0" || cfg("freeform") === "0") return null;
        var raw = cfg("dockLaunchGuard" + screenId);
        if (raw === "none") return null;
        var sep = raw.indexOf("|");
        if (sep <= 0 || sep >= raw.length - 1) return null;
        var deadline = parseInt(raw.substring(0, sep), 10);
        var remaining = deadline - Number(SystemClock.elapsedRealtime());
        // Верхний предел делает persisted Settings-запись безопасной после reboot.
        if (isNaN(deadline) || remaining <= 0 || remaining > 10000) return null;
        var pkg = raw.substring(sep + 1);
        if (isUserFullscreen(pkg)) return null;
        var keep = (pkg === OUR_PKG || pkg === RESTORE_PKG)
                || (pkg.indexOf("ru.big.town") !== 0 && !isStockPkg(pkg));
        return keep ? { pkg: pkg, remaining: remaining } : null;
    }

    var appContext = null;
    var resolver = null;
    function ctx() {
        if (appContext === null) {
            appContext = Java.retain(ActivityThread.currentApplication().getApplicationContext());
        }
        return appContext;
    }

    // Значение ключа из Settings.Global; нет значения → "none".
    function cfg(key) {
        try {
            if (resolver === null) resolver = Java.retain(ctx().getContentResolver());
            var v = SettingsGlobal.getString(resolver, "voyahtune_" + key);
            return (v === null || v === "") ? "none" : "" + v;
        } catch (e) { return "none"; }
    }

    function parseTopActivity(top) {
        top = cleanJavaString(top);
        var at = top.indexOf(TOP_SEPARATOR);
        if (at < 0) return { pkg: top, act: "" };
        return {
            pkg: top.substring(0, at),
            act: top.substring(at + TOP_SEPARATOR.length)
        };
    }

    // Живой top экрана (тот же вызов, что в LauncherModel.handleUpdate*NavigationBar). Непустой ответ
    // авторитетен, включая Launcher/Home: устаревший fullscreen-кэш не должен держать док скрытым.
    function topActivityForScreen(screenId) {
        var cached = fgByScreen[screenId];
        try {
            var parsed = parseTopActivity(LauncherAppUtils.getTopAppInfo(ctx(), screenId, 4));
            if (!parsed.pkg) return { pkg: cached.pkg, act: cached.act, live: false };
            cached.pkg = parsed.pkg;
            cached.act = parsed.act;
            return { pkg: parsed.pkg, act: parsed.act, live: true };
        } catch (e) {
            return { pkg: cached.pkg, act: cached.act, live: false };
        }
    }

    function refreshCache() {
        cache.dock1 = cfg("dock1");
        cache.dock2 = cfg("dock2");
        cache.fullscreen = fullscreenPackageSet(cfg("fullscreen_apps"));
        Log.i(TAG, "[dock] cache: driver=" + cache.dock1 + "/" + cache.dock2
                + " fullscreen=" + Object.keys(cache.fullscreen).join(","));
    }

    function dockPackage(slot, live) {
        if (live) return cfg("dock" + slot);
        return slot === 1 ? cache.dock1 : cache.dock2;
    }

    // Проверка «pkg установлен и запускаем» — гейт перед перехватом клика.
    function isInstalled(pkg) {
        if (pkg === "none") return false;
        try { return ctx().getPackageManager().getLaunchIntentForPackage(pkg) !== null; }
        catch (e) { return false; }
    }

    function retainDrawable(obj) {
        var r = Java.retain(obj);
        retained.push(r);
        // updateTheme может вызываться много раз за жизнь launcher: старые background давно заменены.
        while (retained.length > MAX_RETAINED_DRAWABLES) {
            try { retained.shift().$dispose(); } catch (ignored) {}
        }
        return r;
    }

    // Drawable иконки приложения: pm.getApplicationIcon → рисуем на Bitmap → масштаб 50x50 → BitmapDrawable.
    function getAppDrawable(pkg) {
        try {
            var icon = ctx().getPackageManager().getApplicationIcon(pkg);
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

    function sendNative(action, extras) {
        var i = Intent.$new(action);
        i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
        extras(i);
        i.addFlags(0x00000020);   // FLAG_INCLUDE_STOPPED_PACKAGES — добудиться, даже если Native стоплен
        ctx().sendBroadcast(i);
    }

    // Открыть VoyahTune (UI RestoreMode) — по долгому тапу «меню».
    function openVoyahTune() {
        try {
            var i = Intent.$new();
            i.setClassName(RESTORE_PKG, "ru.big.town.restoremode.MainActivity");
            i.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
            ctx().startActivity(i);
            Log.i(DLOG, "menu long-press -> VoyahTune");
        } catch (e) { Log.e(TAG, "[dock] openVoyahTune err: " + e); }
    }

    // Открыть назначенный слоту сплит — broadcast OPEN_DOCK_SPLIT в Native (тот резолвит детали и стартует).
    function openDockSplit(slot) {
        try {
            sendNative("ru.big.town.anative.OPEN_DOCK_SPLIT", function (i) {
                i.putExtra.overload('java.lang.String', 'int').call(i, "slot", slot);
            });
            Log.i(DLOG, "OPEN_DOCK_SPLIT sent slot=" + slot);
        } catch (e) { Log.e(TAG, "[dock] openDockSplit err: " + e); }
    }

    // Freeform-запуск приложения из слота дока делегируем Native: Native закроет активный VD-сплит и
    // запустит обычную задачу целевого пакета на выбранном физическом display.
    function launchFreeform(pkg, displayId) {
        try {
            sendNative("ru.big.town.anative.OPEN_FREEFORM", function (i) {
                i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "pkg", "" + pkg);
                i.putExtra.overload('java.lang.String', 'int').call(i, "display", displayId);
            });
            Log.i(TAG, "[dock] OPEN_FREEFORM -> " + pkg + " display=" + displayId);
        } catch (e) { Log.e(TAG, "[dock] launchFreeform err: " + e); }
    }

    // Fullscreen launch must normalize an already existing mode-5 task before resume. Native applies
    // Android 11 ActivityOptions windowingMode=FULLSCREEN and independently validates the persisted
    // allowlist, so this exported launcher bridge cannot start an arbitrary package.
    function launchFullscreen(pkg, displayId) {
        if (displayId !== 0 && displayId !== 1) return false;
        try {
            sendNative("ru.big.town.anative.OPEN_FULLSCREEN", function (i) {
                i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "pkg", "" + pkg);
                i.putExtra.overload('java.lang.String', 'int').call(i, "display", displayId);
            });
            Log.i(TAG, "[dock] OPEN_FULLSCREEN -> " + pkg + " display=" + displayId);
            return true;
        } catch (e) {
            Log.e(TAG, "[dock] launchFullscreen err: " + e);
            return false;
        }
    }

    // Долгий тап «меню» (mScreenUpAllAppView) → VoyahTune + return true (гасим штатное долгое).
    // Короткий тап не трогаем — идёт штатно (открытие списка приложений).
    var menuLC = Java.registerClass({
        name: "ru.big.town.dock.MenuLongClick",
        implements: [LongClick],
        methods: {
            onLongClick: {
                returnType: "boolean",
                argumentTypes: ["android.view.View"],
                implementation: function (view) { openVoyahTune(); return true; }
            }
        }
    }).$new();

    // Долгий тап по слоту дока → назначенный слоту СПЛИТ. Слушатель один на оба слота; слот
    // определяем по view.getId() через slotByViewId. Сплит не назначен → false (штатное поведение).
    var slotLC = Java.registerClass({
        name: "ru.big.town.dock.SlotLongClick",
        implements: [LongClick],
        methods: {
            onLongClick: {
                returnType: "boolean",
                argumentTypes: ["android.view.View"],
                implementation: function (view) {
                    try {
                        var slot = slotByViewId["" + view.getId()] || 0;
                        var has = slot ? cfg("dock" + slot + "HasSplit") : "?";
                        Log.i(DLOG, "slot long-press id=" + view.getId() + " slot=" + slot + " hasSplit=" + has);
                        if (slot === 0 || has !== "1") return false;
                        openDockSplit(slot);
                        return true;
                    } catch (e) {
                        Log.e(TAG, "slot long-press err: " + e);
                        return false;
                    }
                }
            }
        }
    }).$new();

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

    // OEM dismiss() only starts a 100-ms x=-width animation and removes the Window from its end
    // callback. Another launcher lifecycle event can end/reuse that animator before removal. Cancelling
    // it with the OEM listener still attached invokes onAnimationEnd(), so a following explicit remove
    // can remove the same root twice and destabilize Launcher. Silence/cancel the animator first, move
    // the Window off-screen synchronously, then use ordinary removeView(). Keeping the Window attached
    // would leave its navigation-bar inset active and constrain fullscreen apps to the old dock width.
    // OEM show() can safely add the root again when Home becomes foreground.
    function forceHideDockBar(bar, label) {
        if (bar === null) return false;
        try {
            var animator = field(bar, "mMoveWindowAnimator");
            if (animator !== null && animator.isStarted()) {
                animator.removeAllListeners();
                animator.removeAllUpdateListeners();
                animator.cancel();
            }
        } catch (e) { Log.w(TAG, "[dock] cancel dismiss animator " + label + ": " + e); }
        try {
            var root = field(bar, "mRootView");
            var lp = field(bar, "mLp");
            var windowManager = field(bar, "mWindowManager");
            if (root === null || lp === null || windowManager === null) return false;
            lp.x.value = -Math.abs(Number(lp.width.value));
            var attached = root.getParent() !== null;
            if (attached) {
                windowManager.updateViewLayout(root, lp);
                windowManager.removeView(root);
            }
            Log.i(DLOG, "force hidden/detached " + label + " attached=" + attached);
            return true;
        } catch (e) {
            Log.e(TAG, "[dock] force hide " + label + " failed: " + e);
            return false;
        }
    }

    function applyScreenLiftDock(bar, type) {
        if (screenOf(bar) !== 0) return; // passenger bar remains completely OEM-controlled
        var views = dockViews(bar);
        if (isUserFullscreen(topActivityForScreen(0).pkg)) {
            // Boot/reload icon passes must never expose children of the detached fullscreen dock.
            setDockViewVisibility(views.up, 8, "fullscreen screenUp");
            setDockViewVisibility(views.down, 8, "fullscreen screenDown");
            return;
        }
        var compact = type === 1;
        // Visibility follows the persisted assignment, not early PackageManager readiness: during
        // cold boot getLaunchIntentForPackage() may still be null even though the app is installed.
        var compactSlot1 = dockPackage(1, false) !== "none";
        var compactSlot2 = dockPackage(2, false) !== "none";

        // OEM doScreenLift(1) перед нашим post-hook прячет screenUp (и показывает screenDown на H97).
        // Возвращаем driver screenUp. WRAP_CONTENT + штатный layout_gravity=center центрирует по высоте
        // Home и только назначенные пользовательские app-слоты.
        setDockViewVisibility(views.up, 0, "screenUp");
        setDockViewVisibility(views.down, 8, "screenDown");
        setDockViewHeight(views.up, compact ? 560 : 720, "screenUp");
        setDockViewHeight(views.group, compact ? -2 : -1, "radioGroup");
        setDockViewVisibility(views.home, 0, "home");
        setDockViewVisibility(views.slot1, compact && !compactSlot1 ? 8 : 0, "slot1");
        setDockViewVisibility(views.slot2, compact && !compactSlot2 ? 8 : 0, "slot2");
        setDockViewVisibility(views.allApps, compact ? 8 : 0, "allApps");
        setDockViewVisibility(views.extra1, compact ? 8 : 0, "slot3");
        setDockViewVisibility(views.extra2, compact ? 8 : 0, "slot4");
        setDockViewVisibility(views.temperature, compact ? 8 : 0, "driverTemperature");
        Log.i(TAG, "[dock] driver lift=" + type
                + " mode=" + (compact ? "compact(home"
                    + (compactSlot1 ? "+1" : "") + (compactSlot2 ? "+2" : "") + ")" : "normal"));
    }

    function currentScreenLiftType() {
        try {
            var type = SystemProps.getInt("persist.qg.canbus.bcm_screenAutoLiftFdb", 2);
            if (type === 1 || type === 2) return type;
        } catch (e) {}
        return parseInt(cfg("screen_lift_type"), 10) === 1 ? 1 : 2;
    }

    // Перерисовка иконок слотов 1/2 водительского бара. Строго на main-треде.
    function updateIcons(bar, skipLayout) {
        try {
            if (screenOf(bar) !== 0) return; // no icon/listener/layout writes to the passenger OEM bar
            var views = dockViews(bar);
            var slots = [
                { name: "slot1", view: views.slot1, pkg: dockPackage(1, false) },
                { name: "slot2", view: views.slot2, pkg: dockPackage(2, false) }
            ];
            for (var n = 0; n < slots.length; n++) {
                var view = slots[n].view;
                if (!view) continue;
                var name = slots[n].name;
                if (!originalBg[name]) {                                              // backup once
                    var bg = view.getBackground();
                    if (bg !== null) originalBg[name] = Java.retain(bg);
                }
                if (slots[n].pkg === "none") {
                    view.setBackground(originalBg[name]);                         // restore OEM icon
                } else {
                    // getApplicationIcon становится доступен на холодном буте раньше, чем
                    // getLaunchIntentForPackage, используемый гейтом клика.
                    var d = getAppDrawable(slots[n].pkg);
                    if (d) view.setBackground(d);
                }
                view.invalidate();
                // Долгий тап по слоту → назначенный сплит (идемпотентно, переживает перекраску темы).
                slotByViewId["" + view.getId()] = n + 1;
                view.setLongClickable(true);
                view.setOnLongClickListener(slotLC);
            }
            // Долгий тап по «меню» → VoyahTune. Навешиваем на каждом проходе (init/theme/reload).
            if (views.allApps) {
                views.allApps.setLongClickable(true);
                views.allApps.setOnLongClickListener(menuLC);
            }
            if (!skipLayout) applyScreenLiftDock(bar, currentScreenLiftType());
        } catch (e) { Log.e(TAG, "[dock] updateIcons err: " + e); }
    }

    // Первичный проход + reload: перерисовать водительский dock (passenger остаётся OEM-controlled).
    // Бар берём из LauncherModel (без повторного Java.choose по куче на каждом проходе).
    function updateAllNavbars() {
        Java.scheduleOnMainThread(function () {
            try {
                var model = findModel();
                if (model === null) return;
                var bar = barOf(model, 0);
                if (bar !== null) updateIcons(bar);
                // Если Launcher перезапустился при third-party top, OEM firstShow() показывает бар без
                // нового TOP_ACTIVITY_CHANGED — поэтому каждый ограниченный проход ещё и сверяет модель.
                schedulePhysicalDockRecovery(model, "navbar pass");
            } catch (e) { Log.e(TAG, "[dock] updateAll err: " + e); }
        });
    }

    // afterMove=true — проход сразу после состоявшегося переноса. Тогда для приложений, по
    // которым у нас своей политики нет (обычные штатные), возвращаем бару то состояние,
    // которое он имел до переноса: штатный лаунчер на этом пути не восстанавливает его сам.
    function reconcilePhysicalDock(model, displayId, reason, afterMove) {
        if (displayId !== 0 && displayId !== 1) return;
        var foreground = topActivityForScreen(displayId);
        var visible = desiredDockVisible(displayId, foreground.pkg, foreground.act);
        if (visible === null && afterMove === true) visible = moveBarSnapshot[displayId];
        if (visible !== true && visible !== false) return;
        if (displayId === 0) model.handleUpdateMainNavigationBar(foreground.pkg, foreground.act, visible);
        else model.handleUpdateSecondNavigationBar(foreground.pkg, foreground.act, visible);
        Log.i(DLOG, reason + (visible ? " restored" : " hid")
                + " display=" + displayId + " dock for " + foreground.pkg);
    }

    function schedulePhysicalDockRecovery(model, reason, afterMove) {
        rememberModel(model);
        setTimeout(function () {
            Java.scheduleOnMainThread(function () {
                try {
                    // Снимаем guard именно здесь: дальше решение принимает reconcile,
                    // и его скрытие не должно упереться в наш же dismiss-хук.
                    if (afterMove === true) clearMoveDockGuards();
                    reconcilePhysicalDock(launcherModel, 0, reason, afterMove);
                    reconcilePhysicalDock(launcherModel, 1, reason, afterMove);
                } catch (e) { Log.e(TAG, "[dock] delayed transfer recovery: " + e); }
            });
        }, 300);
    }

    // Разбор после СОСТОЯВШЕГОСЯ переноса. onMoveStop прилетает раньше, чем система переставит задачу:
    // на экране-приёмнике top ещё показывает домашний экран лаунчера. Поэтому экран-приёмник разбираем
    // только когда его top действительно стал перенесённым пакетом, с ограниченным числом повторов.
    var MOVE_RECOVERY_PASSES = 8;
    var MOVE_RECOVERY_STEP = 400;
    function scheduleMoveDockRecovery(model, movedPackage, sourceDisplay, cancelled) {
        if (sourceDisplay !== 0 && sourceDisplay !== 1) return;
        rememberModel(model);
        var destination = cancelled ? sourceDisplay : (sourceDisplay === 0 ? 1 : 0);
        var attempt = 0;
        var pass = function () {
            Java.scheduleOnMainThread(function () {
                attempt++;
                var reason = "onMoveStop#" + attempt;
                var settled = true;
                try {
                    // Guard жил ровно на время переноса; дальше решение за reconcile.
                    clearMoveDockGuards();
                    settled = !movedPackage || topActivityForScreen(destination).pkg === movedPackage;
                    if (destination !== sourceDisplay) {
                        reconcilePhysicalDock(launcherModel, sourceDisplay, reason, true);
                    }
                    if (settled || attempt >= MOVE_RECOVERY_PASSES) {
                        reconcilePhysicalDock(launcherModel, destination, reason, true);
                    }
                } catch (e) { Log.e(TAG, "[dock] move recovery pass: " + e); }
                if (!settled && attempt < MOVE_RECOVERY_PASSES) setTimeout(pass, MOVE_RECOVERY_STEP);
            });
        };
        setTimeout(pass, 300);
    }

    var receivers = [];
    function registerReceiver(name, filters, onReceive) {
        var cls = Java.registerClass({
            name: name,
            superClass: Receiver,
            methods: {
                // BroadcastReceiver.onReceive абстрактный: только явная сигнатура даёт конкретный
                // override (shorthand-форма давала AbstractMethodError → крэш лаунчера).
                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: onReceive
                }
            }
        });
        var receiver = Java.retain(cls.$new());
        receivers.push(receiver);
        var register = ctx().registerReceiver.overload('android.content.BroadcastReceiver',
                'android.content.IntentFilter');
        filters.forEach(function (filter) { register.call(ctx(), receiver, filter); });
    }

    // Штатный All Apps фильтрует почти все сторонние APK. Добавляем их в списки обоих физических экранов;
    // запуск делегируется OEM AppLauncher с mScreenId владельца All Apps (AllAppBarView).
    // Никакого периодического PackageManager polling: снимок живёт до ближайшего package-broadcast.
    function installAllAppsHooks() {
        try {
            var AppBean = Java.use("com.qinggan.launcher.base.bean.AppBean");
            var Data = Java.use("com.qinggan.launcher.base.allapp.AllAppDataManager");
            var Adapter = Java.use("com.qinggan.launcher.base.adapter.AllAppAdapter");
            var AllAppBarView = Java.use("com.qinggan.launcher.base.allapp.AllAppBarView");
            var SecondAdapter = Java.use("com.qinggan.secondlauncher.adapter.SecondAllAppAdapter");
            var SecondFragment = Java.use("com.qinggan.secondlauncher.fragment.SecondMainFragment");
            var AppLauncher = Java.use("com.qinggan.launcher.base.utils.AppLauncher");
            var JavaString = Java.use("java.lang.String");
            // List.get() возвращает обёртку java.lang.Object: без Java.cast поля не читаются.
            var ApplicationInfo = Java.use("android.content.pm.ApplicationInfo");
            var pm = ctx().getPackageManager();
            var installedSnapshot = null;
            var iconCache = {};
            var labelCache = {};
            var packageRefreshTimer = null;
            var FLAG_SYSTEM = 0x00000001;
            var SYNTHETIC_PREFIX = "__voyahtune_allapps__:";
            var resourceTemplate = null;

            function packageFromIntent(intent) {
                if (intent === null) return "";
                try {
                    var component = intent.getComponent();
                    if (component !== null) return cleanJavaString(component.getPackageName());
                    var explicitPackage = cleanJavaString(intent.getPackage());
                    if (explicitPackage) return explicitPackage;
                    var resolved = pm.resolveActivity(intent, 0);
                    if (resolved !== null && resolved.activityInfo.value !== null) {
                        return cleanJavaString(resolved.activityInfo.value.packageName.value);
                    }
                } catch (ignored) {}
                return "";
            }

            // Покрывает и синтетические, и штатные записи с пакетом из fullscreen-списка: иначе All Apps
            // обходит Native ActivityOptions и поднимает переиспользованную freeform-задачу.
            var startAppIntent = AppLauncher.startApp.overload(
                    'android.content.Context', 'android.content.Intent', 'int');
            startAppIntent.implementation = function (context, intent, screenIdArg) {
                var pkg = packageFromIntent(intent);
                if (isUserFullscreen(pkg) && launchFullscreen(pkg, Number(screenIdArg))) return;
                return startAppIntent.call(this, context, intent, screenIdArg);
            };
            var startAppComponent = AppLauncher.startApp.overload(
                    'android.content.Context', 'java.lang.String', 'java.lang.String', 'int');
            startAppComponent.implementation = function (context, pkgArg, classArg, screenIdArg) {
                var pkg = cleanJavaString(pkgArg);
                if (isUserFullscreen(pkg) && launchFullscreen(pkg, Number(screenIdArg))) return;
                return startAppComponent.call(this, context, pkgArg, classArg, screenIdArg);
            };

            function launchAllApp(pkg, screenId) {
                try {
                    if (isUserFullscreen(pkg)) return launchFullscreen(pkg, screenId);
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

            function snapshotInstalled() {
                if (installedSnapshot !== null) return installedSnapshot;
                var result = [];
                var installed = pm.getInstalledApplications(0);
                for (var i = 0; i < installed.size(); i++) {
                    try {
                        var ai = Java.cast(installed.get(i), ApplicationInfo);
                        var pkg = "" + ai.packageName.value;
                        if ((Number(ai.flags.value) & FLAG_SYSTEM) !== 0 || pkg === LAUNCHER_PKG) continue;
                        if (pm.getLaunchIntentForPackage(pkg) === null) continue;
                        result.push(pkg);
                    } catch (ignored) {}
                }
                installedSnapshot = result;
                Log.i(TAG, "[allapps] cached launchable user apps=" + result.length);
                return installedSnapshot;
            }

            // OEM bind безусловно вызывает Resources.getText(nameRes) и SkinResourceManager.getDrawable(icon),
            // поэтому AppBean(0, 0, pkg) падает ещё до нашего post-bind. Берём валидные placeholder-ресурсы
            // из первого штатного app-bean, а после OEM bind заменяем их настоящими label/icon пакета.
            function findAppTemplate(list) {
                if (list === null) return resourceTemplate;
                for (var i = 0; i < list.size(); i++) {
                    try {
                        var bean = Java.cast(list.get(i), AppBean);
                        if (Number(bean.getType()) === 1 && Number(bean.getIcon()) > 0
                                && Number(bean.getNameRes()) > 0) {
                            resourceTemplate = { icon: Number(bean.getIcon()), name: Number(bean.getNameRes()) };
                            return resourceTemplate;
                        }
                    } catch (ignored) {}
                }
                return resourceTemplate;
            }

            // Флаг от повторного входа: fallback-шаблон зовёт оригинальный getAllApps через наш хук.
            var addingApps = false;

            function addMissingApps(list) {
                if (list === null || addingApps) return;
                addingApps = true;
                try { addMissingAppsImpl(list); }
                finally { addingApps = false; }
            }

            function addMissingAppsImpl(list) {
                var existing = {};
                for (var i = 0; i < list.size(); i++) {
                    try { existing["pkg:" + Java.cast(list.get(i), AppBean).getPackageName()] = true; }
                    catch (ignored) {}
                }
                var apps = snapshotInstalled();
                var template = null;
                for (var j = 0; j < apps.length; j++) {
                    var pkg = apps[j];
                    if (existing["pkg:" + pkg]) continue;
                    try {
                        if (template === null) template = findAppTemplate(list);
                        // Пассажирский OEM-list может быть пустым: ресурсы обоих списков из одного APK,
                        // поэтому берём шаблон из main list через оригинальный getAllApps.
                        if (template === null) template = findAppTemplate(getAll.call(Data, 0));
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

            function syntheticPackage(bean) {
                if (bean === null) return null;
                try {
                    // subType синтетической записи = SYNTHETIC_PREFIX + packageName: один JNI-вызов на плитку.
                    var subType = "" + bean.getSubType();
                    if (subType.lastIndexOf(SYNTHETIC_PREFIX, 0) !== 0) return null;
                    return subType.substring(SYNTHETIC_PREFIX.length) || null;
                } catch (ignored) {
                    return null;
                }
            }

            function loadIcon(pkg) {
                var icon = iconCache[pkg];
                if (!icon) {
                    icon = Java.retain(pm.getApplicationIcon(pkg));
                    iconCache[pkg] = icon;
                }
                return icon;
            }

            // getApplicationInfo — binder-IPC, getApplicationLabel поднимает Resources чужого APK: на каждый
            // bind это десятки миллисекунд UI-потока. Кэшируем готовый java.lang.String до смены локали/пакета.
            function loadLabel(pkg) {
                var cached = labelCache[pkg];
                if (cached) return cached;
                var value = Java.retain(JavaString.$new("" + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))));
                labelCache[pkg] = value;
                return value;
            }

            function dropCache(store, packageName) {
                var keys = packageName ? [packageName] : Object.keys(store);
                for (var i = 0; i < keys.length; i++) {
                    var cached = store[keys[i]];
                    if (cached) {
                        try { cached.$dispose(); } catch (ignored) {}
                    }
                    delete store[keys[i]];
                }
            }

            // ---------- горячий путь bind ----------
            // PagerGridLayoutManager при каждом scrollHorizontallyBy заново привязывает все видимые плитки
            // (~220 onBindViewHolder в секунду при свайпе). Один java-вызов из агента стоит ~0.2 мс,
            // поэтому держим минимум java-вызовов на плитку: позиция решает синтетическая плитка или нет,
            // вьюхи холдера ищутся один раз, иконка и подпись берутся из кэшей.
            var holderCache = {};
            var holderCacheSize = 0;
            var adapterLayouts = {};
            var HOLDER_CACHE_LIMIT = 256;

            // Холдеры переиспользуются пулом RecyclerView: вьюхи и раскладку адаптера считаем один раз на
            // холдер. Ключ — identity-hash холдера; сам холдер удерживаем Java.retain, чтобы освободившийся
            // hash не достался другому объекту.
            function holderEntry(adapter, holder) {
                var key = holder.hashCode();
                var entry = holderCache[key];
                if (entry !== undefined) return entry;
                if (holderCacheSize >= HOLDER_CACHE_LIMIT) clearBindCaches();
                var iconView = field(holder, "iconView");
                var nameView = field(holder, "nameView");
                entry = {
                    holder: Java.retain(holder),
                    icon: iconView === null ? null : Java.retain(iconView),
                    name: nameView === null ? null : Java.retain(nameView),
                    setBackground: null,
                    setText: null,
                    layout: adapterLayout(adapter)
                };
                // Фиксируем перегрузки один раз, чтобы на прокрутке Frida не разбирала их заново.
                if (entry.icon !== null) {
                    entry.setBackground = entry.icon.setBackground.overload('android.graphics.drawable.Drawable');
                }
                if (entry.name !== null) {
                    entry.setText = entry.name.setText.overload('java.lang.CharSequence');
                }
                holderCache[key] = entry;
                holderCacheSize++;
                return entry;
            }

            // Раскладка своя у каждого экрана, поэтому кэшируем её по адаптеру.
            function adapterLayout(adapter) {
                var key = adapter.hashCode();
                var layout = adapterLayouts[key];
                if (layout !== undefined) return layout;
                layout = describeLayout(field(adapter, "mAppBeans"));
                adapterLayouts[key] = layout;
                Log.i(TAG, "[allapps] bind layout adapter=" + key + " "
                        + (layout === null ? "unverified; exact path"
                                : "start=" + layout.start + " synthetic=" + layout.pkgs.length));
                return layout;
            }

            function clearBindCaches() {
                var keys = Object.keys(holderCache);
                for (var i = 0; i < keys.length; i++) {
                    var entry = holderCache[keys[i]];
                    try { entry.holder.$dispose(); } catch (ignored) {}
                    try { if (entry.icon) entry.icon.$dispose(); } catch (ignored) {}
                    try { if (entry.name) entry.name.$dispose(); } catch (ignored) {}
                }
                holderCache = {};
                holderCacheSize = 0;
                adapterLayouts = {};
            }

            // Синтетические записи дописываются в хвост списка: раскладка = (первая синтетическая позиция,
            // пакеты по порядку). Если синтетика не непрерывным хвостом — null, bind читает бин честно.
            function describeLayout(list) {
                if (list === null) return null;
                var size = list.size();
                var start = -1;
                var pkgs = [];
                for (var i = 0; i < size; i++) {
                    var pkg = null;
                    try { pkg = syntheticPackage(Java.cast(list.get(i), AppBean)); }
                    catch (ignored) { return null; }
                    if (pkg === null) {
                        if (start >= 0) return null;
                        continue;
                    }
                    if (start < 0) start = i;
                    pkgs.push(pkg);
                }
                return start < 0 ? { start: size, pkgs: [] } : { start: start, pkgs: pkgs };
            }

            function beanAt(adapter, position) {
                var beans = field(adapter, "mAppBeans");
                if (beans === null || position < 0 || position >= beans.size()) return null;
                return Java.cast(beans.get(position), AppBean);
            }

            function finishBoundItem(adapter, holder, position) {
                var entry = holderEntry(adapter, holder);
                var layout = entry.layout;
                var pkg;
                if (layout !== null) {
                    if (position < layout.start) return;   // штатные плитки — дальше в Java не ходим
                    pkg = layout.pkgs[position - layout.start];
                    if (!pkg) return;
                } else {
                    pkg = syntheticPackage(beanAt(adapter, position));
                    if (pkg === null) return;
                }
                // Плитка рисует иконку в BACKGROUND у SimpleDraweeView (штатный bind: iconView.setBackground).
                if (entry.setBackground !== null) entry.setBackground.call(entry.icon, loadIcon(pkg));
                if (entry.setText !== null) entry.setText.call(entry.name, loadLabel(pkg));
            }

            // Владелец штатного listener — AllAppBarView, у него точный mScreenId. Перехватываем только
            // наши записи по tag, не меняя listener holder-а.
            var allAppClick = AllAppBarView.onClick.overload('android.view.View');
            allAppClick.implementation = function (view) {
                try {
                    var tagged = view !== null ? view.getTag() : null;
                    var pkg = syntheticPackage(tagged !== null ? Java.cast(tagged, AppBean) : null);
                    if (pkg !== null) {
                        var screenId = field(this, "mScreenId");
                        if (screenId !== 0 && screenId !== 1) {
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

            // RecyclerView входит через bridge onBindViewHolder(ViewHolder,int,List) в объявленную в
            // AllAppAdapter payload-перегрузку, а та сводится к двухаргументной. Хук один — на payload,
            // иначе переход в JS-рантайм оплачивался бы дважды на плитку.
            var bindPayload = Adapter.onBindViewHolder.overload(
                    'com.qinggan.launcher.base.adapter.AllAppAdapter$AppViewHolder', 'int', 'java.util.List');
            bindPayload.implementation = function (holder, position, payloads) {
                bindPayload.call(this, holder, position, payloads);
                try { finishBoundItem(this, holder, position); }
                catch (e) { Log.e(TAG, "[allapps] payload bind: " + e); }
            };

            // Пассажирская home-лента читает тот же mSecondAllApps через SecondAllAppAdapter.
            var secondBind = SecondAdapter.onBindViewHolder.overload(
                    'com.qinggan.secondlauncher.adapter.SecondAllAppAdapter$ViewHolder', 'int');
            secondBind.implementation = function (holder, position) {
                secondBind.call(this, holder, position);
                try {
                    var list = field(this, "allAppList");
                    if (list === null || position < 0 || position >= list.size()) return;
                    var pkg = syntheticPackage(Java.cast(list.get(position), AppBean));
                    if (pkg === null) return;
                    var iconView = field(holder, "iconView");
                    var nameView = field(holder, "nameView");
                    if (iconView !== null) iconView.setImageDrawable(loadIcon(pkg));
                    if (nameView !== null) nameView.setText(loadLabel(pkg));
                } catch (e) { Log.e(TAG, "[allapps] passenger rail bind: " + e); }
            };
            var secondClick = SecondFragment.onItemClick.overload('com.qinggan.launcher.base.bean.AppBean');
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

            // Data hook последним: если renderer/click-хуки выше не встали, synthetic entries не попадут в
            // разделяемый OEM list.
            var getAll = Data.getAllApps.overload('int');
            getAll.implementation = function (screenId) {
                var list = getAll.call(this, screenId);
                if ((screenId === 0 || screenId === 1) && list !== null) {
                    addMissingApps(list);
                    clearBindCaches();
                }
                return list;
            };

            // AllAppBarView.initApps() забирает список один раз и держит ссылку, поэтому после бута
            // getAllApps больше не зовётся. Дописываем synthetic entries прямо в mMainAllApps/mSecondAllApps —
            // те же List-объекты, что AllAppBarView.mAppBeans и AllAppAdapter.mAppBeans.
            var dataSingleton = null;

            function injectAllScreens() {
                try {
                    if (dataSingleton === null) dataSingleton = Java.retain(Data.getInstance());
                    var fields = ["mMainAllApps", "mSecondAllApps"];
                    var total = 0;
                    for (var i = 0; i < fields.length; i++) {
                        var list = field(dataSingleton, fields[i]);
                        if (list === null) continue;
                        var before = list.size();
                        addMissingApps(list);
                        var added = list.size() - before;
                        if (added > 0) {
                            Log.i(TAG, "[allapps] injectAllScreens " + fields[i] + " += " + added);
                            total += added;
                        }
                    }
                    clearBindCaches();
                    if (total > 0) refreshAllAppBars();
                } catch (e) { Log.e(TAG, "[allapps] inject failed: " + e); }
            }

            // PagerGridLayoutManager кэширует рамки в mItemFrames и считает страницы по item count: одного
            // notifyDataSetChanged() не хватает. Чистим mItemFrames, затем notify + requestLayout.
            var REFRESH_MAX_ATTEMPTS = 12;
            var REFRESH_RETRY_MS = 200;
            var REFRESH_INITIAL_DELAY_MS = 300;

            function refreshAllAppBars() {
                setTimeout(function () { refreshAttempt(0); }, REFRESH_INITIAL_DELAY_MS);
            }

            function refreshAttempt(attempt) {
                Java.scheduleOnMainThread(function () {
                    var busy = false;
                    try {
                        var bars = [];
                        Java.choose("com.qinggan.launcher.base.allapp.AllAppBarView", {
                            onMatch: function (bar) { bars.push(bar); },
                            onComplete: function () {}
                        });
                        var refreshed = 0;
                        for (var i = 0; i < bars.length; i++) {
                            var adapter = field(bars[i], "mAllAppAdapter");
                            var lm = field(bars[i], "mLayoutManager");
                            if (adapter === null || lm === null) continue;
                            var frames = field(lm, "mItemFrames");
                            if (frames !== null) frames.clear();
                            // Бросает IllegalStateException во время раскладки; catch ниже → повтор.
                            adapter.notifyDataSetChanged();
                            var rv = field(lm, "mRecyclerView");
                            if (rv !== null) rv.requestLayout();
                            refreshed++;
                        }
                        clearBindCaches();
                        if (refreshed > 0) Log.i(TAG, "[allapps] grid refreshed views=" + refreshed);
                    } catch (e) {
                        if (/computing a layout|scrolling/.test("" + e)) busy = true;
                        else {
                            Log.e(TAG, "[allapps] grid refresh failed: " + e);
                            return;
                        }
                    }
                    if (busy && attempt + 1 < REFRESH_MAX_ATTEMPTS) {
                        setTimeout(function () { refreshAttempt(attempt + 1); }, REFRESH_RETRY_MS);
                    } else if (busy) {
                        Log.e(TAG, "[allapps] grid stayed busy, refresh skipped");
                    }
                });
            }

            // reloadImpl() пересобирает оба списка через loadData(), и наши записи теряются. Дописываем
            // сразу после штатной пересборки, до onAppReload() открытых адаптеров.
            var loadData = Data.loadData.overload();
            loadData.implementation = function () {
                loadData.call(this);
                injectAllScreens();
            };

            // Инвалидация сразу, штатный reload через 300 ms (REMOVE+ADD при APK update схлопываются в один).
            // Data.reload() → loadData (хук выше дописывает synthetic entries) → onAppReload() адаптеров.
            function schedulePackageRefresh(action, packageName) {
                installedSnapshot = null;
                dropCache(iconCache, packageName);
                dropCache(labelCache, packageName);
                clearBindCaches();
                if (packageRefreshTimer !== null) clearTimeout(packageRefreshTimer);
                packageRefreshTimer = setTimeout(function () {
                    packageRefreshTimer = null;
                    Java.scheduleOnMainThread(function () {
                        try {
                            Data.reload();
                            Log.i(TAG, "[allapps] package refresh action=" + action + " package=" + packageName);
                        } catch (e) { Log.e(TAG, "[allapps] package refresh failed: " + e); }
                    });
                }, 300);
            }

            // Dynamic receiver в процессе OEM launcher: data-scheme "package" обязателен для package actions,
            // а LOCALE_CHANGED идёт без data — ему нужен отдельный фильтр.
            var packageFilter = IntentFilter.$new();
            packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
            packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
            packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
            packageFilter.addDataScheme("package");
            var localeFilter = IntentFilter.$new("android.intent.action.LOCALE_CHANGED");
            registerReceiver("ru.big.town.dock.AllAppsPackageReceiver", [packageFilter, localeFilter],
                function (context, intent) {
                    try {
                        var action = "" + intent.getAction();
                        // Подписи зависят от локали, иконки — нет.
                        if (action === "android.intent.action.LOCALE_CHANGED") {
                            dropCache(labelCache, null);
                            Log.i(TAG, "[allapps] locale changed; label cache dropped");
                            return;
                        }
                        var data = intent.getData();
                        schedulePackageRefresh(action, data !== null ? "" + data.getSchemeSpecificPart() : "");
                    } catch (e) { Log.e(TAG, "[allapps] package receiver: " + e); }
                });

            // Сетка уже собрана к моменту инъекции: дописываем приложения в живые списки сейчас.
            injectAllScreens();
            Log.i(TAG, "[allapps] hooks installed");
        } catch (e) {
            // All Apps не должен сорвать установку хуков дока.
            Log.e(TAG, "[allapps] hooks unavailable: " + e);
        }
    }

    var NavigationBar = Java.use(NAV_BAR);
    var LauncherModel = Java.use(MODEL);

    // 1) ИКОНКА: переустановка после каждой перекраски темы (иначе штатная тема затрёт наш фон).
    var origUpdateTheme = NavigationBar.updateTheme.overload();
    origUpdateTheme.implementation = function () {
        origUpdateTheme.call(this);
        updateIcons(this);
    };

    // 1b) ИНИЦИАЛИЗАЦИЯ СЛОТОВ: сразу после initScreenUpViews слоты существуют — страховка на случай,
    //     если инъекция прошла ДО создания навбара.
    var origInitUp = NavigationBar.initScreenUpViews.overload();
    origInitUp.implementation = function () {
        origInitUp.call(this);
        updateIcons(this);
    };

    // 1c) ПОДЪЁМ/ОПУСКАНИЕ ЭКРАНА: LauncherModel.doScreenLift на UI-потоке прямо зовёт
    //     NavigationBar.doScreenLift обоих баров. После штатного переключения меняем layout только водителю.
    var origScreenLift = NavigationBar.doScreenLift.overload('int');
    origScreenLift.implementation = function (type) {
        origScreenLift.call(this, type);
        try {
            if (screenOf(this) !== 0) return;
            if (isUserFullscreen(topActivityForScreen(0).pkg)) {
                forceHideDockBar(this, "screen-lift driver");
                return;
            }
            updateIcons(this, true);
            applyScreenLiftDock(this, type);
        } catch (e) { Log.e(TAG, "[dock] screen-lift layout err: " + e); }
    };

    // 2) ПОДСВЕТКА (косметика): reverse-mapping нашего pkg слота → штатный pkg, чтобы родной код чекнул
    //    правильную кнопку. Для нашего VD-хоста (SplitHostActivity) чекаем нажатый слот напрямую.
    var origUpdateSelectedApp = NavigationBar.updateSelectedApp.overload('java.lang.String', 'java.lang.String');
    origUpdateSelectedApp.implementation = function (packageName, activityName) {
        var sid = screenOf(this);
        // Запоминаем приложение переднего плана ДЛЯ СВОЕГО ЭКРАНА (см. dockKept/dismiss).
        if (sid >= 0) {
            fgByScreen[sid].pkg = cleanJavaString(packageName);
            fgByScreen[sid].act = cleanJavaString(activityName);
        }
        if (sid !== 0) return origUpdateSelectedApp.call(this, packageName, activityName);
        try {
            if (packageName === OUR_PKG && ("" + activityName).indexOf("SplitHostActivity") >= 0) {
                var v = lastSlot === 1 ? field(this, "mScreenUpItemView1")
                      : lastSlot === 2 ? field(this, "mScreenUpItemView2") : null;
                if (v) { v.setChecked(true); return; }
            }
            var p1 = dockPackage(1, false);
            var p2 = dockPackage(2, false);
            if (p1 !== "none" && packageName === p1) packageName = STOCK_SLOT_PKG[1];
            else if (p2 !== "none" && packageName === p2) packageName = STOCK_SLOT_PKG[2];
        } catch (e) {}
        return origUpdateSelectedApp.call(this, packageName, activityName);
    };

    // 3) КЛИК: слот определяем сравнением view.getId() с getId() полей (НЕ по индексу).
    //    Совпал + pkg установлен → обычная задача на display 0; иначе штатный onClick.
    var mainOnClick = NavigationBar.onClick.overload('android.view.View');
    mainOnClick.implementation = function (view) {
        if (screenOf(this) !== 0) return mainOnClick.call(this, view);
        try {
            var viewId = view.getId();
            for (var slot = 1; slot <= 2; slot++) {
                var slotView = field(this, "mScreenUpItemView" + slot);
                if (slotView === null || viewId !== slotView.getId()) continue;
                var pkg = dockPackage(slot, true);
                if (isInstalled(pkg)) { lastSlot = slot; launchFreeform(pkg, 0); return; }
            }
        } catch (e) { Log.e(TAG, "[dock] onClick err: " + e); }
        return mainOnClick.call(this, view);
    };

    // 4) ДОК НЕ ДОЛЖЕН САМ УЕЗЖАТЬ ИЗ-ПОД НАШЕГО FREEFORM-ОКНА/VD-СПЛИТА.
    //    Гасим dismiss для любого стороннего приложения: глобальный WindowManager hook оставляет под ним
    //    полосу дока. Аварийно отключить pinning: settings put global voyahtune_dockpin 0
    var origDismiss = NavigationBar.dismiss.overload();
    origDismiss.implementation = function () {
        var sid = screenOf(this);
        if (sid < 0) return origDismiss.call(this);
        try {
            var fg = topActivityForScreen(sid);
            var moving = activeMoveDockGuard();
            var pending = pendingDockLaunch(sid);
            // Разведочный лог ДО решения: без него «хук не встал» неотличимо от «условие не сработало».
            Log.i(DLOG, "dismiss ENTER screen=" + sid + " fg=" + fg.pkg + " act=" + fg.act
                    + (moving ? " moving=" + moving.pkg + "/" + Math.ceil(moving.remaining) + "ms" : "")
                    + (pending ? " pending=" + pending.pkg + "/" + Math.ceil(pending.remaining) + "ms" : ""));
            // Fullscreen policy wins over stale transfer/launch guards: hide and detach the root now.
            if (isUserFullscreen(fg.pkg)) {
                if (forceHideDockBar(this, "dismiss fullscreen display=" + sid)) return;
                return origDismiss.call(this);
            }
            var blocked = moving !== null ? "active transfer " + moving.pkg
                    : pending !== null ? "pending launch " + pending.pkg
                    : dockKept(fg.pkg, fg.act) ? "kept" : null;
            if (blocked !== null) {
                Log.i(DLOG, "dismiss BLOCKED screen=" + sid + " " + blocked);
                return;                      // док остаётся на месте
            }
        } catch (e) { Log.e(TAG, "[dock] dismiss hook err: " + e); }
        return origDismiss.call(this);
    };

    // 4b) QGBus navigation visibility requests are queued independently of TOP_ACTIVITY_CHANGED.
    //     A late visible=true was the repeat-launch resurrection path. Normalize every model request
    //     while the authoritative top is fullscreen.
    function installFullscreenVisibilityGate(methodName, displayId) {
        var original = LauncherModel[methodName].overload('java.lang.String', 'java.lang.String', 'boolean');
        original.implementation = function (pkgArg, actArg, visible) {
            var foreground = topActivityForScreen(displayId);
            // With no live helper answer, the request is fresher than updateSelectedApp cache.
            var decisionPkg = foreground.live ? foreground.pkg : (cleanJavaString(pkgArg) || foreground.pkg);
            if (!isUserFullscreen(decisionPkg)) return original.call(this, pkgArg, actArg, visible);
            fgByScreen[displayId].pkg = decisionPkg;
            if (!foreground.live) fgByScreen[displayId].act = cleanJavaString(actArg);
            // Повторный OEM dismiss() при x == -width сам делает removeView(root) — асинхронная гонка
            // detach/show. Держим Window отсоединённым; OEM false — только если поля недоступны.
            if (forceHideDockBar(barOf(this, displayId), "model gate " + methodName + " display=" + displayId)) {
                Log.i(DLOG, "model gate forced hidden display=" + displayId
                        + " pkg=" + decisionPkg + " requestedVisible=" + visible);
                return;
            }
            return original.call(this, pkgArg, actArg, false);
        };
    }
    installFullscreenVisibilityGate("handleUpdateMainNavigationBar", 0);
    installFullscreenVisibilityGate("handleUpdateSecondNavigationBar", 1);

    // 4c) LauncherModel получает авторитетный TOP_ACTIVITY_CHANGED: для обычного стороннего viewport
    //     повторно показываем dock нужного display, для fullscreen-пакета ЯВНО скрываем его.
    var topReceive = LauncherModel.onReceive.overload('android.content.Context', 'android.content.Intent');
    topReceive.implementation = function (context, intent) {
        var result = topReceive.call(this, context, intent);
        try {
            if (intent !== null && ("" + intent.getAction()) === "android.intent.action.TOP_ACTIVITY_CHANGED") {
                rememberModel(this);
                reconcilePhysicalDock(this, intent.getIntExtra("displayId", -1), "TOP_ACTIVITY_CHANGED");
            }
        } catch (e) { Log.e(TAG, "[dock] TOP_ACTIVITY_CHANGED recovery: " + e); }
        return result;
    };

    // 5) OEM onMoveStart асинхронно гасит ОБА бара, а вернуть их сам умеет только при отменённом
    //    переносе. Guard ставим на ЛЮБОЙ перенос до оригинала; оба dismiss видят его до onMoveStop/TTL.
    //    После stop сверяем реальные top обоих display. Аргументы: (pkg, act, type, displayId, posX, _).
    var origMoveStart = LauncherModel.onMoveStart.overload(
            'java.lang.String', 'java.lang.String', 'int', 'int', 'int', 'int');
    origMoveStart.implementation = function (pkg, act, type, sourceDisplay, posX, extra) {
        try {
            Log.i(DLOG, "onMoveStart(" + pkg + ", " + act + ", " + type + ", " + sourceDisplay
                    + ", " + posX + ", " + extra + ")");
            if (type === 1 && (sourceDisplay === 0 || sourceDisplay === 1)) {
                var now = Number(SystemClock.elapsedRealtime());
                // Снимок — только на первом onMoveStart переноса.
                if (moveDockGuards[sourceDisplay].deadline <= now) snapshotMoveDockState(this);
                var generation = ++moveDockGeneration;
                moveDockGuards[sourceDisplay] = {
                    deadline: now + 5000,
                    generation: generation,
                    pkg: cleanJavaString(pkg)
                };
                Log.i(DLOG, "move guard START source=" + sourceDisplay + " gen=" + generation + " pkg=" + pkg);
            }
        } catch (e) {}
        return origMoveStart.call(this, pkg, act, type, sourceDisplay, posX, extra);
    };
    var origMoveStop = LauncherModel.onMoveStop.overload(
            'java.lang.String', 'java.lang.String', 'int', 'int', 'int', 'int');
    origMoveStop.implementation = function (pkg, act, type, sourceDisplay, posX, extra) {
        Log.i(DLOG, "onMoveStop(" + pkg + ", " + act + ", " + type + ", " + sourceDisplay
                + ", " + posX + ", " + extra + ")");
        var result = origMoveStop.call(this, pkg, act, type, sourceDisplay, posX, extra);
        try {
            if (type === 1 && (sourceDisplay === 0 || sourceDisplay === 1)) {
                var stopPackage = cleanJavaString(pkg);
                var guard = moveDockGuards[sourceDisplay];
                if (guard.pkg === stopPackage && guard.deadline > 0) {
                    // Короткий grace: OEM stop сам лишь ставит UI-runnable.
                    guard.deadline = Math.min(guard.deadline, Number(SystemClock.elapsedRealtime()) + 750);
                    Log.i(DLOG, "move guard STOP source=" + sourceDisplay + " gen=" + guard.generation + " pkg=" + guard.pkg);
                }
                // posX == 0 — перенос отменён, приложение осталось на своём экране.
                scheduleMoveDockRecovery(this, stopPackage, sourceDisplay, posX === 0);
            }
        } catch (e) { Log.e(TAG, "[dock] onMoveStop recovery: " + e); }
        return result;
    };

    // Приёмник reload: Native шлёт DOCK_RELOAD после записи voyahtune_dock* → перечитать + перерисовать.
    registerReceiver("ru.big.town.dock.DockReloadReceiver", [IntentFilter.$new(RELOAD_ACT)],
        function (context, intent) {
            Log.i(DLOG, "onReceive DOCK_RELOAD");
            try {
                refreshCache();
                setTimeout(updateAllNavbars, 300);   // дать навбару стабилизироваться
            } catch (e) { Log.e(TAG, "[dock] onReceive err: " + e); }
        });

    // Первичная загрузка конфига + отрисовка иконок на уже живых навбарах. Повторы ограничены:
    // PackageManager и конструирование навбара на холодном буте завершаются в разные моменты.
    refreshCache();
    installAllAppsHooks();
    [0, 800, 2500, 5000, 8000, 12000, 15000].forEach(function (delay) {
        setTimeout(updateAllNavbars, delay);
    });

    // Выгрузка скрипта откатывает хуки, но Java-объекты с JS-реализацией (receivers, long-click
    // listeners) остались бы в лаунчере без тела. Отвязываем их и снимаем маркер для следующей инъекции.
    rpc.exports.dispose = function () {
        Java.performNow(function () {
            receivers.forEach(function (r) {
                try { ctx().unregisterReceiver(r); } catch (e) {}
            });
            try {
                var bar = barOf(launcherModel, 0);
                if (bar !== null) {
                    var views = dockViews(bar);
                    [views.slot1, views.slot2, views.allApps].forEach(function (v) {
                        if (v) v.setOnLongClickListener(null);
                    });
                    if (views.slot1 && originalBg.slot1) views.slot1.setBackground(originalBg.slot1);
                    if (views.slot2 && originalBg.slot2) views.slot2.setBackground(originalBg.slot2);
                }
            } catch (e) {}
            JavaSystem.clearProperty(AGENT_MARK);
        });
        Log.i(TAG, "[dock] agent disposed");
    };

    Log.i(TAG, "[dock] PI NavigationBar/LauncherModel hooks installed");
});
