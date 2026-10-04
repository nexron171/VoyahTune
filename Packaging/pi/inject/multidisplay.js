// multidisplay.js — перенос сторонних приложений между физическими экранами штатными средствами.
//
// Система уже умеет переносить task жестом/кнопкой, но MultiDisplayImpl.isWhiteListApp(String)
// разрешает это только пакетам из OEM whitelist. Подменяем только эту проверку и оставляем штатными
// reparent, анимацию, диалоги и activity-level mEnable.
//
// Штатная кнопка смены экранов (keycode 3115 → swapActivity) умеет только МЕНЯТЬ МЕСТАМИ два
// приложения. Если переносимое приложение открыто лишь на одном экране, а на другом лаунчер,
// swap отказывает с "No app on this screen support switch". В этом случае переносим одно
// приложение штатным moveActivity — тем же путём, что и перетаскивание тремя пальцами.
//
// Инжектится в com.qinggan.systemservice (injects.json). load.bin считает успехом только точный
// console-marker ниже.
//
// КОНФИГ: Settings.Global voyahtune_multidisplay (1 = вкл, деф 1). Аварийное отключение через adb:
//   settings put global voyahtune_multidisplay 0
//   am broadcast -a ru.big.town.anative.MD_RELOAD
Java.perform(function () {
    "use strict";

    var TAG = "vt_multidisplay";
    var RELOAD_ACT = "ru.big.town.anative.MD_RELOAD";
    var AGENT_MARK = "open_voyah.multidisplay.server_hook.v2";
    var READY_MARKER = "[multidisplay] hook ready v2";
    var FAILURE_MARKER = "[multidisplay] hook failed v2";

    // Лаунчер является home на обоих дисплеях; наши SplitHost живут на VirtualDisplay; SystemUI
    // не является переносимым приложением. Для них сохраняем явный deny.
    var NEVER = ["com.qinggan.app.launcher", "ru.big.town", "com.android.systemui"];

    var Log = Java.use("android.util.Log");
    var JavaSystem = Java.use("java.lang.System");
    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var enabled = true;
    var reloadReceiver = null;

    function marker(line) {
        Log.i(TAG, line);
        try { console.log(line); } catch (ignored) {}
    }

    function ctx() {
        var app = ActivityThread.currentApplication();
        if (app !== null) return app.getApplicationContext();
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    function refreshCfg() {
        try {
            var value = SettingsGlobal.getString(ctx().getContentResolver(), "voyahtune_multidisplay");
            enabled = value === null || value === "" || parseInt(value, 10) === 1;
            Log.i(TAG, "multidisplay enabled=" + enabled);
        } catch (e) {
            Log.e(TAG, "refreshCfg: " + e);
        }
    }

    function isNever(pkg) {
        if (!pkg) return true;
        for (var i = 0; i < NEVER.length; i++) {
            if (pkg.indexOf(NEVER[i]) === 0) return true;
        }
        return false;
    }

    // Receiver нужен только для live-reload аварийного флага; его ошибка не отменяет core hook.
    function installReloadReceiver() {
        try {
            var Receiver = Java.registerClass({
                name: "ru.big.town.md.MdReloadReceiverV2_" + Date.now(),
                superClass: Java.use("android.content.BroadcastReceiver"),
                methods: {
                    // onReceive абстрактный: только явная сигнатура даёт конкретный override.
                    onReceive: {
                        returnType: "void",
                        argumentTypes: ["android.content.Context", "android.content.Intent"],
                        implementation: function () { refreshCfg(); }
                    }
                }
            });
            var context = ctx();
            reloadReceiver = Java.retain(Receiver.$new());
            context.registerReceiver.overload("android.content.BroadcastReceiver",
                "android.content.IntentFilter").call(context, reloadReceiver,
                Java.use("android.content.IntentFilter").$new(RELOAD_ACT));
            return true;
        } catch (e) {
            Log.w(TAG, "reload receiver registration failed: " + e);
            return false;
        }
    }

    // Повторная инъекция в живой агент навесила бы хук поверх первого (метку снимает dispose).
    if (("" + JavaSystem.getProperty(AGENT_MARK, "")) === "installed") {
        marker(READY_MARKER + " state=already_installed");
        return;
    }

    try {
        refreshCfg();
        var MDI = Java.use("com.qinggan.systemservice.multidisplay.MultiDisplayImpl");
        var isWhiteListApp = MDI.isWhiteListApp.overload("java.lang.String");
        isWhiteListApp.implementation = function (packageName) {
            if (!enabled) return isWhiteListApp.call(this, packageName);
            return !isNever(packageName === null ? null : "" + packageName);
        };
        var ServiceManager = Java.use("com.qinggan.os.ServiceManager");
        var swapActivity = MDI.swapActivity.overload();
        swapActivity.implementation = function () {
            try {
                if (enabled) {
                    var context = ctx();
                    var pkg0 = "" + ServiceManager.getDpyTopAppInfo(context, 0, 1);
                    var pkg1 = "" + ServiceManager.getDpyTopAppInfo(context, 1, 1);
                    var movable0 = !isNever(pkg0), movable1 = !isNever(pkg1);
                    if (movable0 !== movable1) {
                        var display = movable0 ? 0 : 1;
                        var pkg = movable0 ? pkg0 : pkg1;
                        var act = "" + ServiceManager.getDpyTopAppInfo(context, display, 2);
                        if (this.moveActivity(pkg, act, false)) {
                            Log.i(TAG, "switch key: moved " + pkg + " from display " + display);
                            return true;
                        }
                    }
                }
            } catch (e) {
                Log.e(TAG, "switch key move: " + e);
            }
            return swapActivity.call(this);
        };
        JavaSystem.setProperty(AGENT_MARK, "installed");
        // При выгрузке хук откатывается сам; receiver с JS-телом отвязываем, метку снимаем.
        rpc.exports.dispose = function () {
            Java.performNow(function () {
                if (reloadReceiver !== null) {
                    try { ctx().unregisterReceiver(reloadReceiver); } catch (ignored) {}
                }
                JavaSystem.clearProperty(AGENT_MARK);
            });
        };
        var receiverReady = installReloadReceiver();
        marker(READY_MARKER + " receiver=" + (receiverReady ? "ready" : "failed"));
    } catch (e) {
        Log.e(TAG, "core install failed: " + e);
        marker(FAILURE_MARKER + " stage=core_install error=" + e);
    }
});
