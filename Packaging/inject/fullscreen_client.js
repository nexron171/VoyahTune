// Client-side fullscreen geometry repair for packages selected in VoyahTune's
// "Fullscreen applications" list.
//
// The OEM launcher may start an application with WindowManager.LayoutParams.width fixed to the
// old 1780 px work area. system_server can already give that window a 1920 px frame and Surface,
// but ViewRootImpl still measures the application's DecorView with EXACTLY(1780), leaving a black
// strip where the dock used to be. Server-side frame or Surface scaling cannot repair that client
// measurement without distorting the UI.
//
// This agent runs only in exact allowlisted application processes. New layout calls give
// ViewRootImpl a COPY of base-activity LayoutParams whose width is MATCH_PARENT. A late attach must
// also update an already measured DecorView, so its original width is remembered and restored when
// the package leaves the list. Dialogs, starting windows and VirtualDisplay windows are left alone.

Java.perform(function () {
    var TAG = "vt_fullscreen_client";
    var READY_MARKER = "[fullscreen-client] hook ready v1";
    var SETTING = "voyahtune_fullscreen_apps";
    var RELOAD_ACTION = "ru.big.town.anative.WIN_RELOAD";
    var RELOAD_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    var TYPE_BASE_APPLICATION = 1;
    var MATCH_PARENT = -1;

    var Log = Java.use("android.util.Log");
    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var LayoutParams = Java.use("android.view.WindowManager$LayoutParams");
    var View = Java.use("android.view.View");
    var ViewRootImpl = Java.use("android.view.ViewRootImpl");
    var WindowManagerGlobal = Java.use("android.view.WindowManagerGlobal");
    var BroadcastReceiver = Java.use("android.content.BroadcastReceiver");
    var IntentFilter = Java.use("android.content.IntentFilter");
    var Handler = Java.use("android.os.Handler");
    var Looper = Java.use("android.os.Looper");
    var System = Java.use("java.lang.System");
    var Thread = Java.use("java.lang.Thread");

    var application = ActivityThread.currentApplication();
    // pidof may observe a cold process a few milliseconds before ActivityThread publishes its
    // Application. Wait only on Frida's attached worker thread; the app main looper is not blocked.
    for (var bootstrapAttempt = 0; application === null && bootstrapAttempt < 30;
            bootstrapAttempt++) {
        Thread.sleep(100);
        application = ActivityThread.currentApplication();
    }
    if (application === null) {
        console.log("[fullscreen-client] hook failed v1: currentApplication is null");
        return;
    }

    var packageName = "" + application.getPackageName();
    var enabled = false;
    var reloadReceiver = null;
    var mainHandler = Handler.$new(Looper.getMainLooper());
    var originalWidths = {};
    var replayApplying = false;
    var replayScheduled = false;

    function packageIsAllowlisted(csv) {
        if (csv === null) return false;
        var packages = ("" + csv).split(",");
        for (var i = 0; i < packages.length; i++) {
            if (packages[i].trim() === packageName) return true;
        }
        return false;
    }

    function readEnabled() {
        try {
            return packageIsAllowlisted(SettingsGlobal.getString(
                application.getContentResolver(), SETTING));
        } catch (e) {
            Log.e(TAG, "fullscreen setting read failed for " + packageName + ": " + e);
            return false;
        }
    }

    function displayIdOf(root) {
        try { return Number(root.getDisplayId()); }
        catch (e) { return -1; }
    }

    function isBaseWindowOnPhysicalDisplay(root, attrs) {
        if (attrs === null) return false;
        var displayId = displayIdOf(root);
        return Number(attrs.type.value) === TYPE_BASE_APPLICATION
            && (displayId === 0 || displayId === 1);
    }

    function rootKey(root) {
        return "root:" + Number(System.identityHashCode(root));
    }

    function rememberOriginalWidth(root, attrs) {
        if (replayApplying) return;
        var key = rootKey(root);
        // Keep the pre-hook baseline. A framework update may later echo the MATCH_PARENT params
        // installed by our late replay; overwriting here would make disable unable to restore 1780.
        if (typeof originalWidths[key] !== "number") {
            originalWidths[key] = Number(attrs.width.value);
        }
    }

    function normalizedCopy(root, attrs) {
        if (!enabled || !isBaseWindowOnPhysicalDisplay(root, attrs)) {
            return attrs;
        }
        rememberOriginalWidth(root, attrs);
        if (Number(attrs.width.value) === MATCH_PARENT) return attrs;
        try {
            var copy = LayoutParams.$new();
            copy.copyFrom(attrs);
            copy.width.value = MATCH_PARENT;
            return copy;
        } catch (e) {
            Log.e(TAG, "LayoutParams clone failed for " + packageName + ": " + e);
            return attrs;
        }
    }

    var setView = ViewRootImpl.setView.overload(
        "android.view.View",
        "android.view.WindowManager$LayoutParams",
        "android.view.View",
        "int");
    var setLayoutParams = ViewRootImpl.setLayoutParams.overload(
        "android.view.WindowManager$LayoutParams", "boolean");

    function replayAttachedRoots(reason) {
        if (replayScheduled) return;
        replayScheduled = true;
        Java.scheduleOnMainThread(function () {
            replayScheduled = false;
            var changed = 0;
            try {
                var windowManager = WindowManagerGlobal.getInstance();
                var views = windowManager.getWindowViews();
                for (var i = 0; i < views.size(); i++) {
                    try {
                        // ArrayList.get() is typed as Object in Frida; cast before calling hidden
                        // View methods such as getViewRootImpl().
                        var view = Java.cast(views.get(i), View);
                        var rawAttrs = view.getLayoutParams();
                        if (rawAttrs === null) continue;
                        var attrs = Java.cast(rawAttrs, LayoutParams);
                        var root = view.getViewRootImpl();
                        if (root === null || !isBaseWindowOnPhysicalDisplay(root, attrs)) continue;
                        var key = rootKey(root);
                        var currentWidth = Number(attrs.width.value);
                        var hasOriginalWidth = typeof originalWidths[key] === "number";
                        if (enabled && !hasOriginalWidth) {
                            originalWidths[key] = currentWidth;
                            hasOriginalWidth = true;
                        }
                        var targetWidth = enabled ? MATCH_PARENT
                            : (hasOriginalWidth
                                ? originalWidths[key] : currentWidth);
                        // On disable, replay even when DecorView already has the original width.
                        // A queued enable replay may have normalized ViewRootImpl only; sending the
                        // original params through WindowManagerGlobal restores both copies.
                        var mustRestoreRoot = !enabled && hasOriginalWidth;
                        if (currentWidth === targetWidth && !mustRestoreRoot) {
                            if (!enabled) delete originalWidths[key];
                            continue;
                        }
                        var copy = LayoutParams.$new();
                        copy.copyFrom(attrs);
                        copy.width.value = targetWidth;
                        // updateViewLayout updates both DecorView's LayoutParams and ViewRootImpl.
                        // A direct ViewRootImpl call updates WMS but does not force an existing
                        // DecorView with fixed root params to remeasure on this Android 11 build.
                        replayApplying = true;
                        try {
                            windowManager.updateViewLayout(view, copy);
                        } finally {
                            replayApplying = false;
                        }
                        if (!enabled) delete originalWidths[key];
                        changed++;
                    } catch (windowError) {
                        Log.w(TAG, "root replay skipped: " + windowError);
                    }
                }
                Log.i(TAG, "root replay " + reason + " package=" + packageName
                    + " enabled=" + enabled + " roots=" + changed);
            } catch (e) {
                Log.e(TAG, "root replay failed " + reason + ": " + e);
            }
        });
    }

    function refreshEnabled(reason) {
        var previous = enabled;
        enabled = readEnabled();
        Log.i(TAG, "policy " + reason + " package=" + packageName
            + " enabled=" + enabled + " changed=" + (previous !== enabled));
        replayAttachedRoots(reason);
    }

    try {
        var Receiver = Java.registerClass({
            name: "ru.big.town.voyahtune.FullscreenClientReloadReceiver",
            superClass: BroadcastReceiver,
            methods: {
                // BroadcastReceiver.onReceive is abstract. This OEM ART needs an explicit method
                // signature; Frida's shorthand function form leaves the vtable slot abstract and
                // crashes the target with AbstractMethodError on the first broadcast.
                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: function (context, intent) {
                        try {
                            if (intent !== null && ("" + intent.getAction()) === RELOAD_ACTION) {
                                refreshEnabled("WIN_RELOAD");
                            }
                        } catch (e) {
                            Log.e(TAG, "WIN_RELOAD failed: " + e);
                        }
                    }
                }
            }
        });
        reloadReceiver = Java.retain(Receiver.$new());
        application.registerReceiver.overload(
            "android.content.BroadcastReceiver",
            "android.content.IntentFilter",
            "java.lang.String",
            "android.os.Handler"
        ).call(application, reloadReceiver, IntentFilter.$new(RELOAD_ACTION),
            RELOAD_PERMISSION, mainHandler);
    } catch (e) {
        Log.e(TAG, "WIN_RELOAD receiver registration failed: " + e);
        console.log("[fullscreen-client] hook failed v1: receiver registration: " + e);
        return;
    }

    try {
        setView.implementation = function (view, attrs, panelParentView, userId) {
            var shouldReplay = enabled && isBaseWindowOnPhysicalDisplay(this, attrs);
            var result = setView.call(this, view, normalizedCopy(this, attrs),
                panelParentView, userId);
            // WindowManagerGlobal assigns the app-owned attrs to DecorView before setView(). Replay
            // on the next main-loop turn so the attached View also receives MATCH_PARENT.
            if (shouldReplay) replayAttachedRoots("setView");
            return result;
        };
        setLayoutParams.implementation = function (attrs, newView) {
            var shouldReplay = enabled && !replayApplying
                && isBaseWindowOnPhysicalDisplay(this, attrs)
                && Number(attrs.width.value) !== MATCH_PARENT;
            var result = setLayoutParams.call(this, normalizedCopy(this, attrs), newView);
            // WindowManagerGlobal has already copied app attrs onto DecorView before this hook.
            // Repair that client-owned copy on the next UI-loop turn as well.
            if (shouldReplay) replayAttachedRoots("setLayoutParams");
            return result;
        };
    } catch (e) {
        try { setView.implementation = null; } catch (ignoredSetView) {}
        try { setLayoutParams.implementation = null; } catch (ignoredSetLayout) {}
        try { application.unregisterReceiver(reloadReceiver); } catch (ignoredReceiver) {}
        Log.e(TAG, "ViewRootImpl hook installation failed: " + e);
        console.log("[fullscreen-client] hook failed v1: ViewRootImpl: " + e);
        return;
    }

    enabled = readEnabled();
    replayAttachedRoots("attach");
    Log.i(TAG, READY_MARKER + " package=" + packageName + " enabled=" + enabled);
    console.log(READY_MARKER + " package=" + packageName + " enabled=" + enabled);
});
