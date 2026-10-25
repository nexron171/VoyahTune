//   settings put global voyahtune_multidisplay 0
//   am broadcast -a ru.big.town.anative.MD_RELOAD
// Preserve OEM task transfer and animation; widen only the package whitelist.
// If just one physical screen has a movable app, use the OEM moveActivity ABI.
Java.perform(function () {
    "use strict";

    var TAG = "vt_multidisplay";
    var CLS = "com.qinggan.systemservice.multidisplay.MultiDisplayImpl";
    var RELOAD_ACT = "ru.big.town.anative.MD_RELOAD";
    var SENTINEL_KEY = "open_voyah.multidisplay.server_hook.v2";
    var READY_MARKER = "[multidisplay] hook ready v2";
    var FAILURE_MARKER = "[multidisplay] hook failed v2";

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

    var NEVER = ["com.qinggan.app.launcher", "com.qinggan.mainlauncher",
        //    "ru.big.town",
        "com.android.systemui"];

    function safeConsole(line) {
        try { console.log(line); } catch (ignored) { }
    }

    function safeLog(priority, line) {
        if (Log === null) return;
        try {
            if (priority === "e") Log.e(TAG, line);
            else if (priority === "w") Log.w(TAG, line);
            else Log.i(TAG, line);
        } catch (ignored) { }
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
        } catch (ignored) { }
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    function refreshCfg() {
        try {
            var value = SettingsGlobal.getString(ctx().getContentResolver(),
                "voyahtune_multidisplay");
            enabled = (value === null || value === "") ? true : (parseInt(value, 10) === 1);
            safeLog("i", "multidisplay enabled=" + enabled);
        } catch (e) {

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
        } catch (ignored) { }
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
        } catch (ignored) { }
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
                try { returnType = "" + overload.returnType.className; } catch (ignored) { }
                if (packageIndex < 0 || (returnType !== "boolean" && returnType !== "java.lang.Boolean")) {
                    safeLog("w", "skip unsupported isWhiteListApp overload index=" + i
                        + " return=" + returnType + " packageArg=" + packageIndex);
                    return;
                }

                overload.implementation = function () {
                    var args = Array.prototype.slice.call(arguments);
                    var pkg = null;
                    try { pkg = packageValue(args[packageIndex]); } catch (ignoredPkg) { }
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

    function installSingleAppTransfer(adapter) {
        if (adapter.swap === null || adapter.move === null) return;
        var service = VoyahAdapters.optionalClass("com.qinggan.os.ServiceManager");
        if (service === null) return;
        adapter.swap.implementation = function () {
            try {
                if (enabled) {
                    var context = ctx();
                    var first = packageValue(service.getDpyTopAppInfo(context, 0, 1));
                    var second = packageValue(service.getDpyTopAppInfo(context, 1, 1));
                    var firstMovable = !isNever(first);
                    var secondMovable = !isNever(second);
                    if (firstMovable !== secondMovable) {
                        var display = firstMovable ? 0 : 1;
                        var packageName = firstMovable ? first : second;
                        var activity = packageValue(service.getDpyTopAppInfo(context, display, 2));
                        if (activity && adapter.move.call(this, packageName, activity, false)) return true;
                    }
                }
            } catch (error) { safeLog("w", "single-app transfer unavailable: " + safeValue(error, 180)); }
            return adapter.swap.call(this);
        };
        installedCore.push(adapter.swap);
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
                    } catch (ignoredTypes) { }
                    overload.implementation = function () {
                        var args = Array.prototype.slice.call(arguments);
                        var rawEnable = booleanIndex >= 0 ? args[booleanIndex] : null;
                        var isDisabled = booleanIndex >= 0 && !booleanValue(rawEnable);

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
        var firmwareAdapter = VoyahAdapters.multidisplay();
        MDI = firmwareAdapter.type;

        try {
            var methods = MDI.class.getDeclaredMethods(), names = [];
            for (var m = 0; m < methods.length; m++) names.push(methods[m].getName());
            safeLog("i", "MultiDisplayImpl methods: " + names.join(","));
        } catch (ignoredDump) { }

        var coreCount = installWhitelistCore();
        installSingleAppTransfer(firmwareAdapter);

        JavaSystem.setProperty(SENTINEL_KEY, "installed");
        var dialogCount = installShowDelayDiagnostics();
        var enableCount = installEnableDiagnostics();
        var receiverReady = installReloadReceiver();

        signalReady("core_overloads=" + coreCount + " receiver=" + (receiverReady ? "ready" : "failed")
            + " dialog_diag=" + dialogCount + " enable_diag=" + enableCount);
    } catch (e) {

        for (var i = installedCore.length - 1; i >= 0; i--) {
            try { installedCore[i].implementation = null; } catch (ignoredRollback) { }
        }
        signalFailure("core_install", e);
    }
});
