// multidisplay.js — перенос ЛЮБОГО приложения между экранами штатными средствами системы.
//
// ЗАЧЕМ. Система сама умеет двигать окно приложения между водительским и пассажирским экраном
// (штатный жест + анимация), но только для приложений из вайтлиста
// /system/etc/qinggan/multidisplay_anim_app_white.list — там перечислены штатные приложения.
// Стороннее приложение в списке отсутствует, поэтому его нельзя ни нормально открыть на
// пассажирском экране, ни перетащить между экранами.
//
// ПОЧЕМУ НЕ ПРАВИМ ФАЙЛ. Список статический: он не покроет приложение, которое пользователь
// поставит завтра, а перезапись системного файла ещё и затирает штатные записи, состав которых
// на разных прошивках разный. Поэтому подменяем не данные, а саму проверку.
//
// ЧТО ДЕЛАЕМ. Хукаем com.qinggan.systemservice.multidisplay.MultiDisplayImpl.isWhiteListApp(pkg)
// и отвечаем «разрешено» для всего, кроме NEVER (см. ниже). Дальше всё делает штатный код —
// никаких своих жестов, окон и анимаций мы не рисуем.
//
// Инжектится в com.qinggan.systemservice (load.bin, цель 4).
//
// КОНФИГ: Settings.Global voyahtune_multidisplay (1 = вкл, деф 1). Аварийное отключение через adb:
//   settings put global voyahtune_multidisplay 0
//   am broadcast -a ru.big.town.anative.MD_RELOAD
Java.perform(function () {
    var TAG = "voyahmd";
    var CLS = "com.qinggan.systemservice.multidisplay.MultiDisplayImpl";
    var RELOAD_ACT = "ru.big.town.anative.MD_RELOAD";

    var Log = Java.use("android.util.Log");
    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");

    // Кому перенос между экранами НЕ разрешаем:
    //   • лаунчер — он сам является домашним экраном на обоих дисплеях, перенос ломает их раскладку;
    //   • наши пакеты — SplitHostActivity живёт на своём VirtualDisplay, штатный перенос конфликтует
    //     с нашей логикой сплита;
    //   • системный UI — статус-бар/навбар не являются переносимыми окнами.
    var NEVER = ["com.qinggan.app.launcher", "com.qinggan.mainlauncher",
                 "ru.big.town", "com.android.systemui"];

    // Кэш флага: isWhiteListApp зовётся системой часто, поэтому в самом хуке чтений Settings.Global нет.
    var enabled = true;

    function ctx() {
        try { var app = ActivityThread.currentApplication(); if (app !== null) return app.getApplicationContext(); } catch (e) {}
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    function refreshCfg() {
        try {
            var v = SettingsGlobal.getString(ctx().getContentResolver(), "voyahtune_multidisplay");
            enabled = (v === null || v === "") ? true : (parseInt(v, 10) === 1);   // нет значения → включено
            Log.i(TAG, "multidisplay enabled=" + enabled);
        } catch (e) { Log.e(TAG, "refreshCfg: " + e); }
    }

    function isNever(pkg) {
        if (!pkg) return true;
        for (var i = 0; i < NEVER.length; i++) if (pkg.indexOf(NEVER[i]) === 0) return true;
        return false;
    }

    refreshCfg();

    var MDI;
    try {
        MDI = Java.use(CLS);
    } catch (e) {
        // Не тот процесс либо другая прошивка — тихо выходим, ничего не ломая.
        Log.w(TAG, "MultiDisplayImpl не найден (не systemservice?): " + e);
        return;
    }

    // Разовый дамп методов класса — чтобы на живой голове подтвердить состав API, если поведение
    // окажется не таким, как ожидаем (имена версионно-хрупкие). Читать: logcat -s voyahmd
    try {
        var ms = MDI.class.getDeclaredMethods(), names = [];
        for (var i = 0; i < ms.length; i++) names.push(ms[i].getName());
        Log.i(TAG, "MultiDisplayImpl methods: " + names.join(","));
    } catch (e) {}

    // Основной хук. Перебираем перегрузки явно: на разных прошивках сигнатура может отличаться,
    // а обращение к .implementation при нескольких перегрузках бросает исключение.
    try {
        var ovl = MDI.isWhiteListApp.overloads;
        for (var i = 0; i < ovl.length; i++) {
            (function (o) {
                o.implementation = function (pkg) {
                    try {
                        if (!enabled) return o.call(this, pkg);      // выключено → штатное поведение
                        var p = "" + pkg;
                        if (isNever(p)) return false;
                        return true;
                    } catch (e) {
                        return o.call(this, pkg);                    // любая осечка → как было
                    }
                };
            })(ovl[i]);
        }
        Log.i(TAG, "isWhiteListApp hooked (" + ovl.length + " overload(s))");
    } catch (e) {
        Log.e(TAG, "isWhiteListApp hook fail: " + e);
    }

    // Приёмник перечитки конфига. ВАЖНО: BroadcastReceiver.onReceive — АБСТРАКТНЫЙ метод; краткая
    // форма registerClass (methods:{onReceive:function(){}}) на этой прошивке НЕ переопределяет
    // абстрактный слот vtable → AbstractMethodError при доставке → падение процесса-хоста. Объявляем
    // метод с ЯВНОЙ сигнатурой — это гарантирует конкретный override.
    try {
        var Receiver = Java.registerClass({
            name: "ru.big.town.md.MdReloadReceiver",
            superClass: Java.use("android.content.BroadcastReceiver"),
            methods: {
                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: function (c, i) { refreshCfg(); }
                }
            }
        });
        var IntentFilter = Java.use("android.content.IntentFilter");
        var c = ctx();
        var sdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
        if (sdk >= 33) {
            c.registerReceiver.overload('android.content.BroadcastReceiver',
                'android.content.IntentFilter', 'int').call(c, Receiver.$new(), IntentFilter.$new(RELOAD_ACT), 0x2);
        } else {
            c.registerReceiver.overload('android.content.BroadcastReceiver',
                'android.content.IntentFilter').call(c, Receiver.$new(), IntentFilter.$new(RELOAD_ACT));
        }
        Log.i(TAG, "reload receiver registered: " + RELOAD_ACT);
    } catch (e) { Log.e(TAG, "receiver reg fail: " + e); }
});
