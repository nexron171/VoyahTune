// multidisplay.js — перенос сторонних приложений между физическими экранами штатными средствами.
//
// Система уже умеет переносить task жестом/кнопкой, но серверный MultiDisplayImpl разрешает это
// только пакетам из OEM whitelist. Подменяем только эту проверку и оставляем штатными reparent,
// анимацию, диалоги и activity-level mEnable.
//
// Инжектится в com.qinggan.systemservice (load.bin). Успехом loader считает только точный
// console-marker ниже: успешный exit-code frida-inject сам по себе ничего не доказывает.
//
// КОНФИГ: Settings.Global voyahtune_multidisplay (1 = вкл, деф 1). Аварийное отключение через adb:
//   settings put global voyahtune_multidisplay 0
//   am broadcast -a ru.big.town.anative.MD_RELOAD
Java.perform(function () {
    "use strict";

    var TAG = "vt_multidisplay";
    var CLS = "com.qinggan.systemservice.multidisplay.MultiDisplayImpl";
    var RELOAD_ACT = "ru.big.town.anative.MD_RELOAD";
    var SENTINEL_KEY = "open_voyah.multidisplay.server_hook.v2";
    var READY_MARKER = "[multidisplay] hook ready v2";
    var FAILURE_MARKER = "[multidisplay] hook failed v2";

    // Ограниченные диагностические выборки: достаточно для одного воспроизведения, но hot path не
    // превращается в бесконечный logcat-spam. Whitelist пишется по уникальному решению+пакету.
    var QUERY_TRACE_MAX = 20;
    var DIALOG_TRACE_MAX = 8;
    var ENABLE_TRACE_MAX = 12;
    var queryTraceCount = 0;
    var dialogTraceCount = 0;
    var enableTrueTraceCount = 0;
    var enableFalseTraceCount = 0;
    var queryTraceSeen = {};

    var Log = null;
    var JavaSystem = null;
    var ActivityThread = null;
    var SettingsGlobal = null;
    var MDI = null;
    var installedCore = [];
    var enabled = true;

    // Лаунчер является home на обоих дисплеях; наши SplitHost живут на VirtualDisplay; SystemUI
    // не является переносимым приложением. Для них сохраняем явный deny.
    var NEVER = ["com.qinggan.app.launcher", "com.qinggan.mainlauncher",
                 "ru.big.town", "com.android.systemui"];

    function safeConsole(line) {
        try { console.log(line); } catch (ignored) {}
    }

    function safeLog(priority, line) {
        if (Log === null) return;
        try {
            if (priority === "e") Log.e(TAG, line);
            else if (priority === "w") Log.w(TAG, line);
            else Log.i(TAG, line);
        } catch (ignored) {}
    }

    function signalReady(details) {
        var line = READY_MARKER + " " + details;
        safeLog("i", line);
        safeConsole(line);
    }

    function signalFailure(stage, error) {
        var line = FAILURE_MARKER + " stage=" + stage + " error=" + safeValue(error, 180);
        safeLog("e", line);
        safeConsole(line);
    }

    function safeValue(value, maxLength) {
        var text;
        try { text = value === null || value === undefined ? "<null>" : ("" + value); }
        catch (ignored) { text = "<unprintable>"; }
        return text.length <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    function packageValue(value) {
        if (value === null || value === undefined) return null;
        try { return "" + value; } catch (ignored) { return null; }
    }

    function booleanValue(value) {
        if (value === true || value === false) return value;
        try { return !!value.booleanValue(); } catch (ignored) { return !!value; }
    }

    function ctx() {
        try {
            var app = ActivityThread.currentApplication();
            if (app !== null) return app.getApplicationContext();
        } catch (ignored) {}
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    function refreshCfg() {
        try {
            var value = SettingsGlobal.getString(ctx().getContentResolver(),
                "voyahtune_multidisplay");
            enabled = (value === null || value === "") ? true : (parseInt(value, 10) === 1);
            safeLog("i", "multidisplay enabled=" + enabled);
        } catch (e) {
            // Default-on остаётся безопасным прежним поведением; невозможность прочитать Settings
            // не означает, что core whitelist hook не установлен.
            safeLog("e", "refreshCfg: " + safeValue(e, 180));
        }
    }

    function isNever(pkg) {
        if (!pkg) return true;
        for (var i = 0; i < NEVER.length; i++) {
            if (pkg.indexOf(NEVER[i]) === 0) return true;
        }
        return false;
    }

    function callOriginal(overload, receiver, args) {
        // Важно для firmware-вариантов: не теряем неизвестные дополнительные аргументы.
        return overload.call.apply(overload, [receiver].concat(args));
    }

    function firstStringArgumentIndex(overload) {
        try {
            var found = -1;
            for (var i = 0; i < overload.argumentTypes.length; i++) {
                if (("" + overload.argumentTypes[i].className) === "java.lang.String") {
                    // With two String parameters we cannot prove which one is the package on an
                    // unknown firmware. Skip it instead of accidentally whitelisting by activity.
                    if (found >= 0) return -1;
                    found = i;
                }
            }
            return found;
        } catch (ignored) {}
        return -1;
    }

    function traceWhitelist(pkg, decision, reason) {
        try {
            var packageName = safeValue(pkg, 120);
            var key = "$" + reason + "|" + decision + "|" + packageName;
            if (queryTraceCount >= QUERY_TRACE_MAX || queryTraceSeen[key] === true) return;
            queryTraceSeen[key] = true;
            queryTraceCount++;
            safeLog("i", "[multidisplay] whitelist query pkg=" + packageName
                + " decision=" + (decision ? "allow" : "deny") + " reason=" + reason
                + " sample=" + queryTraceCount + "/" + QUERY_TRACE_MAX);
        } catch (ignored) {}
    }

    function installWhitelistCore() {
        var method = MDI.isWhiteListApp;
        if (!method || !method.overloads || method.overloads.length === 0) {
            throw new Error("isWhiteListApp has no overloads");
        }

        var installed = 0;
        for (var i = 0; i < method.overloads.length; i++) {
            (function (overload) {
                var packageIndex = firstStringArgumentIndex(overload);
                var returnType = "";
                try { returnType = "" + overload.returnType.className; } catch (ignored) {}
                if (packageIndex < 0 || (returnType !== "boolean" && returnType !== "java.lang.Boolean")) {
                    safeLog("w", "skip unsupported isWhiteListApp overload index=" + i
                        + " return=" + returnType + " packageArg=" + packageIndex);
                    return;
                }

                overload.implementation = function () {
                    var args = Array.prototype.slice.call(arguments);
                    var pkg = null;
                    try { pkg = packageValue(args[packageIndex]); } catch (ignoredPkg) {}
                    if (!enabled) {
                        // OEM exception must propagate exactly once; do not catch and call it twice.
                        var stock = callOriginal(overload, this, args);
                        traceWhitelist(pkg, booleanValue(stock), "disabled-stock");
                        return stock;
                    }
                    try {
                        if (isNever(pkg)) {
                            traceWhitelist(pkg, false, "never");
                            return false;
                        }
                        traceWhitelist(pkg, true, "server-override");
                        return true;
                    } catch (e) {
                        // Любая runtime-осечка остаётся fail-open только относительно OEM-кода:
                        // выполняем исходную реализацию с ПОЛНЫМ набором аргументов.
                        var fallback = callOriginal(overload, this, args);
                        traceWhitelist(pkg, booleanValue(fallback), "hook-error-stock");
                        return fallback;
                    }
                };
                installedCore.push(overload);
                installed++;
            })(method.overloads[i]);
        }
        if (installed < 1) throw new Error("no compatible isWhiteListApp overload installed");
        return installed;
    }

    function formatArgs(args, maxArgs) {
        var parts = [];
        for (var i = 0; i < args.length && i < maxArgs; i++) parts.push(safeValue(args[i], 100));
        if (args.length > maxArgs) parts.push("…+" + (args.length - maxArgs));
        return parts.join(",");
    }

    function installShowDelayDiagnostics() {
        try {
            var method = MDI.showDelayDialog;
            if (!method || !method.overloads) return 0;
            var installed = 0;
            for (var i = 0; i < method.overloads.length; i++) {
                (function (overload) {
                    overload.implementation = function () {
                        var args = Array.prototype.slice.call(arguments);
                        if (dialogTraceCount < DIALOG_TRACE_MAX) {
                            dialogTraceCount++;
                            safeLog("i", "[multidisplay] showDelayDialog args=" + formatArgs(args, 6)
                                + " sample=" + dialogTraceCount + "/" + DIALOG_TRACE_MAX);
                        }
                        return callOriginal(overload, this, args);
                    };
                    installed++;
                })(method.overloads[i]);
            }
            return installed;
        } catch (e) {
            safeLog("w", "optional showDelayDialog diagnostics unavailable: " + safeValue(e, 180));
            return 0;
        }
    }

    function installEnableDiagnostics() {
        try {
            var method = MDI.setEnableActivityAnimation;
            if (!method || !method.overloads) return 0;
            var installed = 0;
            for (var i = 0; i < method.overloads.length; i++) {
                (function (overload) {
                    var stringIndexes = [];
                    var booleanIndex = -1;
                    try {
                        for (var j = 0; j < overload.argumentTypes.length; j++) {
                            var type = "" + overload.argumentTypes[j].className;
                            if (type === "java.lang.String") stringIndexes.push(j);
                            else if (type === "boolean" || type === "java.lang.Boolean") {
                                booleanIndex = j;
                            }
                        }
                    } catch (ignoredTypes) {}
                    overload.implementation = function () {
                        var args = Array.prototype.slice.call(arguments);
                        var rawEnable = booleanIndex >= 0 ? args[booleanIndex] : null;
                        var isDisabled = booleanIndex >= 0 && !booleanValue(rawEnable);
                        // Половину бюджета резервируем для false: именно он объясняет отказ при
                        // whitelist=allow, даже если до него прошла серия enable=true.
                        var traceBucketCount = isDisabled
                            ? enableFalseTraceCount : enableTrueTraceCount;
                        var traceBucketMax = ENABLE_TRACE_MAX / 2;
                        if (traceBucketCount < traceBucketMax) {
                            if (isDisabled) enableFalseTraceCount++;
                            else enableTrueTraceCount++;
                            var packageName = stringIndexes.length > 0
                                ? safeValue(args[stringIndexes[0]], 100) : "<unknown>";
                            var activityName = stringIndexes.length > 1
                                ? safeValue(args[stringIndexes[1]], 140) : "<unknown>";
                            var enableValue = booleanIndex >= 0
                                ? safeValue(rawEnable, 16) : "<unknown>";
                            var sampleNumber = enableTrueTraceCount + enableFalseTraceCount;
                            safeLog("i", "[multidisplay] setEnableActivityAnimation package="
                                + packageName + " activity=" + activityName + " enabled=" + enableValue
                                + " sample=" + sampleNumber + "/" + ENABLE_TRACE_MAX);
                        }
                        return callOriginal(overload, this, args);
                    };
                    installed++;
                })(method.overloads[i]);
            }
            return installed;
        } catch (e) {
            safeLog("w", "optional setEnableActivityAnimation diagnostics unavailable: "
                + safeValue(e, 180));
            return 0;
        }
    }

    function installReloadReceiver() {
        try {
            var Receiver;
            try {
                Receiver = Java.use("ru.big.town.md.MdReloadReceiverV2");
            } catch (missingReceiverClass) {
                Receiver = Java.registerClass({
                    name: "ru.big.town.md.MdReloadReceiverV2",
                    superClass: Java.use("android.content.BroadcastReceiver"),
                    methods: {
                        onReceive: {
                            returnType: "void",
                            argumentTypes: ["android.content.Context", "android.content.Intent"],
                            implementation: function () { refreshCfg(); }
                        }
                    }
                });
            }
            var IntentFilter = Java.use("android.content.IntentFilter");
            var context = ctx();
            var sdk = Java.use("android.os.Build$VERSION").SDK_INT.value;
            if (sdk >= 33) {
                context.registerReceiver.overload("android.content.BroadcastReceiver",
                    "android.content.IntentFilter", "int").call(context, Receiver.$new(),
                    IntentFilter.$new(RELOAD_ACT), 0x2);
            } else {
                context.registerReceiver.overload("android.content.BroadcastReceiver",
                    "android.content.IntentFilter").call(context, Receiver.$new(),
                    IntentFilter.$new(RELOAD_ACT));
            }
            safeLog("i", "reload receiver registered: " + RELOAD_ACT);
            return true;
        } catch (e) {
            // Receiver нужен только для live-reload аварийного флага. Core hook уже работает с
            // прочитанным значением, поэтому отсутствие receiver не превращаем в ложный core fail.
            safeLog("w", "optional receiver registration failed: " + safeValue(e, 180));
            return false;
        }
    }

    try {
        Log = Java.use("android.util.Log");
        JavaSystem = Java.use("java.lang.System");
        ActivityThread = Java.use("android.app.ActivityThread");
        SettingsGlobal = Java.use("android.provider.Settings$Global");

        if (("" + JavaSystem.getProperty(SENTINEL_KEY, "")) === "installed") {
            signalReady("state=already_installed");
            return;
        }

        refreshCfg();
        MDI = Java.use(CLS);

        try {
            var methods = MDI.class.getDeclaredMethods(), names = [];
            for (var m = 0; m < methods.length; m++) names.push(methods[m].getName());
            safeLog("i", "MultiDisplayImpl methods: " + names.join(","));
        } catch (ignoredDump) {}

        var coreCount = installWhitelistCore();
        // Идемпотентность важнее optional diagnostics: если stdout ready-marker потеряется, второй
        // bounded inject только повторно напечатает marker и не наложит ещё один core wrapper.
        JavaSystem.setProperty(SENTINEL_KEY, "installed");
        var dialogCount = installShowDelayDiagnostics();
        var enableCount = installEnableDiagnostics();
        var receiverReady = installReloadReceiver();

        signalReady("core_overloads=" + coreCount + " receiver=" + (receiverReady ? "ready" : "failed")
            + " dialog_diag=" + dialogCount + " enable_diag=" + enableCount);
    } catch (e) {
        // Не оставляем частично установленный core: bounded loader retry должен начинать с OEM
        // поведения, а не наслаивать новый implementation поверх недособранного набора overloads.
        for (var i = installedCore.length - 1; i >= 0; i--) {
            try { installedCore[i].implementation = null; } catch (ignoredRollback) {}
        }
        signalFailure("core_install", e);
    }
});
