// Client-side geometry, RdsApp persistence, CarPlay chrome and navigation-source adapters.
// The OEM launcher may start an application with WindowManager.LayoutParams.width fixed to the
// old 1780 px work area. system_server can already give that window a 1920 px frame and Surface,
// but ViewRootImpl still measures the application's DecorView with EXACTLY(1780), leaving a black
// strip where the dock used to be. Server-side frame or Surface scaling cannot repair that client
// measurement without distorting the UI.
// This agent runs only in exact allowlisted application processes. New layout calls give
// ViewRootImpl a COPY of base-activity LayoutParams whose width is MATCH_PARENT. A late attach must
// also update an already measured DecorView, so its original width is remembered and restored when
// the package leaves the list. Dialogs, starting windows and VirtualDisplay windows are left alone.
// Yandex MapKit owns a second, renderer-side density value. A physical Task density override scales
// Android resources but does not necessarily update MapWindow, so the native Vulkan/GL map can stay
// at the 160-dpi scale. For the three known Yandex/Yango map applications this agent mirrors the
// selected per-app DPI to MapWindow.scaleFactor (physical pixels per independent point). The Surface
// keeps its native physical resolution; no VirtualDisplay or compositor upscaling is involved.

// frida-inject 16.2.1 awaits RPC init before -e eternalizes/detaches the script.
// Keep stdout connected until bootstrap and the main-thread OEM adapters finish.
var appClientBootstrapResolve;
var appClientBootstrap = new Promise(function (resolve) { appClientBootstrapResolve = resolve; });
var appClientRdsReady = Promise.resolve();
var appClientOemUiReady = Promise.resolve();
if (typeof rpc !== "undefined") {
    rpc.exports = {
        init: function () {
            return appClientBootstrap.then(function () {
                return Promise.all([appClientRdsReady, appClientOemUiReady]);
            });
        }
    };
}
function scheduleRdsStationRestore(application) {
    appClientRdsReady = new Promise(function (resolve) {
        Java.scheduleOnMainThread(function () {
            try { installRdsStationRestore(application); }
            finally { resolve(); }
        });
    });
}

Java.perform(function () {
    try {
        var TAG = "vt_app_client";
        var READY_MARKER = "[app-client] hook ready v1";
        var MAPKIT_READY_MARKER = "[mapkit-dpi] hook ready v1";
        var SETTING = "voyahtune_fullscreen_apps";
        var DPI_SETTING_PREFIX = "voyahtune_dpi_";
        var RELOAD_ACTION = "ru.big.town.anative.WIN_RELOAD";
        var RELOAD_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
        var TYPE_BASE_APPLICATION = 1;
        var MATCH_PARENT = -1;
        var DEFAULT_DENSITY_DPI = 160;
        var MAP_WINDOW_INTERFACE = "com.yandex.mapkit.map.MapWindow";
        var MAP_VIEW = "com.yandex.mapkit.mapview.MapView";
        var MAPKIT_BINDING = "com.yandex.mapkit.internal.MapKitBinding";

        var Log = Java.use("android.util.Log");
        var ActivityThread = Java.use("android.app.ActivityThread");
        var SettingsGlobal = Java.use("android.provider.Settings$Global");
        var LayoutParams = Java.use("android.view.WindowManager$LayoutParams");
        var View = Java.use("android.view.View");
        var ViewGroup = Java.use("android.view.ViewGroup");
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
            console.log("[app-client] hook failed v1: currentApplication is null");
            return;
        }

        var packageName = "" + application.getPackageName();
        if (packageName === "com.qinggan.systemui") {
            appClientOemUiReady = new Promise(function (resolve) {
                Java.scheduleOnMainThread(function () {
                    try {
                        var installed = installCarPlayStatusBar(application);
                        if (installed) console.log(READY_MARKER);
                    } finally { resolve(); }
                });
            });
            return;
        }
        if (packageName === "ru.yandex.yandexnavi" || packageName === "ru.dublgis.dgismobile") {
            installNavigationSource(application);
        }
        if (packageName === "com.qinggan.cluster") return;
        if (packageName === "com.pateo.rdsapp") {
            // Finish Application.onCreate before resolving RdsManager's singleton. Initializing it
            // from the attach thread too early can see BaseApplication.mContext == null.
            scheduleRdsStationRestore(application);
            // Continue the existing opt-in geometry handler, including future WIN_RELOAD changes.
        }
        var enabled = false;
        var mapkitPackage = packageName === "ru.yandex.yandexnavi"
            || packageName === "ru.yandex.yandexmaps"
            || packageName === "com.yango.maps.android";
        var mapkitDpi = 0;
        var reloadReceiver = null;
        var mainHandler = Handler.$new(Looper.getMainLooper());
        var originalWidths = {};
        var replayApplying = false;
        var replayScheduled = false;
        var MapWindow = null;
        var MapViewClass = null;
        var mapViewGetter = null;
        var mapkitBindingHookInstalled = false;
        var mapkitHooksInstalled = false;
        var mapkitReadyAnnounced = false;
        var mapkitReplayScheduled = false;
        var mapkitApplying = 0;
        var mapWindowBaselines = Object.create(null);
        var hookedMapWindowClasses = Object.create(null);

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

        function readMapkitDpi() {
            if (!mapkitPackage) return 0;
            try {
                var raw = SettingsGlobal.getString(application.getContentResolver(),
                    DPI_SETTING_PREFIX + packageName);
                if (raw === null) return 0;
                var dpi = parseInt(("" + raw).trim(), 10);
                return dpi >= 100 && dpi <= 640 ? dpi : 0;
            } catch (e) {
                Log.e(TAG, "MapKit DPI setting read failed for " + packageName + ": " + e);
                return 0;
            }
        }

        function mapkitScaleForDpi(dpi) {
            return dpi / DEFAULT_DENSITY_DPI;
        }

        function mapWindowKey(windowObject) {
            var className = "unknown";
            try { className = "" + windowObject.$className; } catch (ignoredClass) { }
            return className + ":" + Number(System.identityHashCode(windowObject));
        }

        function rememberMapWindowBaseline(windowObject, typedWindow) {
            var key = mapWindowKey(windowObject);
            if (typeof mapWindowBaselines[key] !== "number") {
                var current = Number(typedWindow.getScaleFactor());
                if (isFinite(current) && current > 0) mapWindowBaselines[key] = current;
            }
            return typeof mapWindowBaselines[key] === "number"
                ? mapWindowBaselines[key] : 1.0;
        }

        function hookMapWindowClass(windowObject) {
            var className = "" + windowObject.$className;
            if (hookedMapWindowClasses[className]) return true;
            try {
                var WindowClass = Java.use(className);
                var originalSetScale = WindowClass.setScaleFactor.overload("float");
                originalSetScale.implementation = function (requestedScale) {
                    var requested = Number(requestedScale);
                    var key = mapWindowKey(this);
                    if (mapkitApplying > 0) {
                        return originalSetScale.call(this, requestedScale);
                    }
                    if (!(mapkitDpi > 0)) {
                        if (isFinite(requested) && requested > 0) {
                            mapWindowBaselines[key] = requested;
                        }
                        return originalSetScale.call(this, requestedScale);
                    }
                    if (typeof mapWindowBaselines[key] !== "number") {
                        try {
                            var current = Number(Java.cast(this, MapWindow).getScaleFactor());
                            if (isFinite(current) && current > 0) mapWindowBaselines[key] = current;
                        } catch (ignoredBaseline) { }
                    }
                    return originalSetScale.call(this, mapkitScaleForDpi(mapkitDpi));
                };
                hookedMapWindowClasses[className] = true;
                Log.i(TAG, "MapKit setScaleFactor guard installed class=" + className);
                return true;
            } catch (e) {
                Log.w(TAG, "MapKit setScaleFactor guard unavailable class=" + className + ": " + e);
                return false;
            }
        }

        function applyMapWindow(windowObject, reason) {
            if (!mapkitPackage || windowObject === null || MapWindow === null) return false;
            try {
                var typedWindow = Java.cast(windowObject, MapWindow);
                if (!typedWindow.isValid()) return false;
                hookMapWindowClass(windowObject);
                var baseline = rememberMapWindowBaseline(windowObject, typedWindow);
                var target = mapkitDpi > 0 ? mapkitScaleForDpi(mapkitDpi) : baseline;
                var current = Number(typedWindow.getScaleFactor());
                if (Math.abs(current - target) < 0.0001) return false;
                mapkitApplying++;
                try {
                    typedWindow.setScaleFactor(target);
                } finally {
                    mapkitApplying--;
                }
                var mapSize = "unknown";
                try {
                    mapSize = Number(typedWindow.width()) + "x" + Number(typedWindow.height());
                } catch (ignoredSize) { }
                Log.i(TAG, "MapKit scale " + reason + " package=" + packageName
                    + " dpi=" + mapkitDpi + " old=" + current + " target=" + target
                    + " size=" + mapSize);
                return true;
            } catch (e) {
                Log.e(TAG, "MapKit scale apply failed " + reason + ": " + e);
                return false;
            }
        }

        function hookMapView() {
            if (mapViewGetter !== null) return true;
            try {
                MapViewClass = Java.use(MAP_VIEW);
                mapViewGetter = MapViewClass.getMapWindow.overload();
                mapViewGetter.implementation = function () {
                    var windowObject = mapViewGetter.call(this);
                    applyMapWindow(windowObject, "MapView.getMapWindow");
                    return windowObject;
                };
                Log.i(TAG, "MapKit MapView factory hook installed");
                return true;
            } catch (e) {
                MapViewClass = null;
                mapViewGetter = null;
                Log.w(TAG, "MapKit MapView hook unavailable: " + e);
                return false;
            }
        }

        function hookMapKitBinding() {
            if (mapkitBindingHookInstalled) return true;
            try {
                var MapKitBinding = Java.use(MAPKIT_BINDING);
                if (typeof MapKitBinding.createMapWindow === "undefined") return false;
                var overloads = MapKitBinding.createMapWindow.overloads;
                for (var i = 0; i < overloads.length; i++) {
                    (function (originalCreate) {
                        originalCreate.implementation = function () {
                            var windowObject = originalCreate.apply(this, arguments);
                            applyMapWindow(windowObject, "MapKitBinding.createMapWindow");
                            return windowObject;
                        };
                    })(overloads[i]);
                }
                mapkitBindingHookInstalled = overloads.length > 0;
                Log.i(TAG, "MapKit binding factory hooks=" + overloads.length);
                return mapkitBindingHookInstalled;
            } catch (e) {
                Log.w(TAG, "MapKit binding factory hook unavailable: " + e);
                return false;
            }
        }

        function ensureMapkitHooks(reason) {
            if (!mapkitPackage) return true;
            if (mapkitHooksInstalled) return true;
            try {
                Java.classFactory.loader = application.getClassLoader();
                MapWindow = Java.use(MAP_WINDOW_INTERFACE);
                // Install both when available. The factory catches new windows; MapView also gives us
                // a stable path to already-created windows after a late Frida attach.
                var mapViewReady = hookMapView();
                var bindingReady = hookMapKitBinding();
                mapkitHooksInstalled = mapViewReady || bindingReady;
            } catch (e) {
                Log.e(TAG, "MapKit hook installation failed " + reason + ": " + e);
                mapkitHooksInstalled = false;
            }
            if (mapkitHooksInstalled && !mapkitReadyAnnounced) {
                mapkitReadyAnnounced = true;
                Log.i(TAG, MAPKIT_READY_MARKER + " package=" + packageName);
                console.log(MAPKIT_READY_MARKER + " package=" + packageName);
            }
            return mapkitHooksInstalled;
        }

        function replayMapWindows(reason) {
            if (!mapkitPackage || !mapkitHooksInstalled || mapkitReplayScheduled) return;
            mapkitReplayScheduled = true;
            Java.scheduleOnMainThread(function () {
                mapkitReplayScheduled = false;
                var matched = 0;
                function visit(view) {
                    if (view === null) return;
                    try {
                        if (MapViewClass !== null && MapViewClass.class.isInstance(view)) {
                            var mapView = Java.cast(view, MapViewClass);
                            if (applyMapWindow(mapViewGetter.call(mapView), reason)) matched++;
                        }
                        if (!ViewGroup.class.isInstance(view)) return;
                        var group = Java.cast(view, ViewGroup);
                        for (var childIndex = 0; childIndex < group.getChildCount(); childIndex++) {
                            visit(group.getChildAt(childIndex));
                        }
                    } catch (viewError) {
                        Log.w(TAG, "MapKit view-tree replay skipped: " + viewError);
                    }
                }
                try {
                    var views = WindowManagerGlobal.getInstance().getWindowViews();
                    for (var i = 0; i < views.size(); i++) {
                        visit(Java.cast(views.get(i), View));
                    }
                } catch (rootError) {
                    Log.e(TAG, "MapKit root replay failed: " + rootError);
                }
                Log.i(TAG, "MapKit replay " + reason + " package=" + packageName
                    + " dpi=" + mapkitDpi + " changed=" + matched);
            });
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

        function refreshPolicy(reason) {
            var previous = enabled;
            var previousMapkitDpi = mapkitDpi;
            enabled = readEnabled();
            mapkitDpi = readMapkitDpi();
            Log.i(TAG, "policy " + reason + " package=" + packageName
                + " enabled=" + enabled + " changed=" + (previous !== enabled)
                + " mapkitDpi=" + mapkitDpi
                + " mapkitChanged=" + (previousMapkitDpi !== mapkitDpi));
            if (ensureMapkitHooks(reason)) replayMapWindows(reason);
            replayAttachedRoots(reason);
        }

        try {
            var Receiver = Java.registerClass({
                name: "ru.big.town.voyahtune.AppClientReloadReceiver",
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
                                    refreshPolicy("WIN_RELOAD");
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
            console.log("[app-client] hook failed v1: receiver registration: " + e);
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
            try { setView.implementation = null; } catch (ignoredSetView) { }
            try { setLayoutParams.implementation = null; } catch (ignoredSetLayout) { }
            try { application.unregisterReceiver(reloadReceiver); } catch (ignoredReceiver) { }
            Log.e(TAG, "ViewRootImpl hook installation failed: " + e);
            console.log("[app-client] hook failed v1: ViewRootImpl: " + e);
            return;
        }

        refreshPolicy("attach");
        Log.i(TAG, READY_MARKER + " package=" + packageName + " enabled=" + enabled
            + " mapkitDpi=" + mapkitDpi + " mapkitHooks=" + mapkitHooksInstalled);
        console.log(READY_MARKER + " package=" + packageName + " enabled=" + enabled
            + " mapkitDpi=" + mapkitDpi + " mapkitHooks=" + mapkitHooksInstalled);
    } finally { appClientBootstrapResolve(); }
});

function isCarPlayStatusBarTarget(activity, displayId) {
    if (displayId !== 0 && displayId !== 1) return false;
    var component = String(activity || "");
    return component === "ru.big.town.anative.CarPlayFullscreenActivity"
        || component === "ru.big.town.anative/.CarPlayFullscreenActivity"
        || component === "ru.big.town.anative/ru.big.town.anative.CarPlayFullscreenActivity";
}

function createCarPlayStatusBarController(io) {
    var owned = [false, false];
    var timerPending = false;

    function top(displayId) {
        try { return io.top(displayId) || ""; }
        catch (error) { io.error(error); return ""; }
    }

    function schedule() {
        if (timerPending || !owned.some(function (hidden) { return hidden; })) return;
        timerPending = true;
        var delay = Math.min.apply(Math, [1000].concat([0, 1].map(function (displayId) {
            return io.revealRemaining(displayId);
        }).filter(function (remaining) { return remaining > 0; })));
        io.schedule(function () {
            timerPending = false;
            reconcile();
        }, delay);
    }

    function apply(displayId, visible, carPlay, receiver) {
        if (carPlay) owned[displayId] = true;
        try {
            io.apply(displayId, visible, receiver);
            io.reportHidden(displayId, carPlay && !visible);
            owned[displayId] = carPlay;
        } catch (error) {
            io.error(error);
        }
        schedule();
    }

    function request(displayId, visible, receiver) {
        var host = isCarPlayStatusBarTarget(top(displayId), displayId);
        var carPlay = host && io.hideSystemBars();
        apply(displayId, host ? !carPlay || io.revealRemaining(displayId) > 0 : visible, carPlay, receiver);
    }

    function reconcile() {
        for (var displayId = 0; displayId <= 1; displayId++) {
            var activity = top(displayId);
            var host = isCarPlayStatusBarTarget(activity, displayId);
            var carPlay = host && io.hideSystemBars();
            if (!host && !owned[displayId]) continue;
            try {
                var visible = host ? !carPlay || io.revealRemaining(displayId) > 0 : !io.stockHidden(activity, displayId);
                apply(displayId, visible, carPlay, null);
            } catch (error) {
                io.error(error);
            }
        }
        schedule();
    }

    return { request: request, reconcile: reconcile };
}

function installCarPlayStatusBar(application) {
    var installed = [];
    var observer = null;
    try {
        var Log = Java.use("android.util.Log");
        var ServiceManager = Java.use("com.qinggan.os.ServiceManager");
        var SystemApplication = Java.use("com.qinggan.systemui.SystemUIApplication");
        var SystemBars = Java.use("com.qinggan.systemui.statusbar.SystemBars");
        var PhoneStatusBar = Java.use("com.qinggan.systemui.statusbar.phone.PhoneStatusBar");
        var InvisibleItem = Java.use("com.qinggan.systemui.statusbar.utils.StatusBarInvisibleItem");
        var originalHit = InvisibleItem.isHit.overload("java.lang.String", "int");
        var originals = [
            PhoneStatusBar.addStatusBarWindow.overload("boolean"),
            PhoneStatusBar.addStatusBarWindowSecond.overload("boolean")
        ];
        var statusBarApplication = Java.cast(application, SystemApplication);
        var lastVisible = [null, null];
        var SettingsGlobal = Java.use("android.provider.Settings$Global");
        var SystemClock = Java.use("android.os.SystemClock");
        var revealSetting = "voyahtune_carplay_chrome_reveal_until_";
        var resolver = application.getContentResolver();

        function hideSystemBars() {
            return Number(SettingsGlobal.getInt(resolver, "voyahtune_carplay_hide_system_bars", 0)) === 1;
        }

        function revealRemaining(displayId) {
            try {
                var deadline = Number(SettingsGlobal.getLong(resolver, revealSetting + displayId, 0));
                var remaining = deadline - Number(SystemClock.elapsedRealtime());
                return remaining > 0 && remaining <= 10000 ? remaining : 0;
            } catch (error) { return 0; }
        }

        function statusBar() {
            var services = statusBarApplication.getServices();
            for (var index = 0; index < services.length; index++) {
                var service = services[index];
                if (service !== null && SystemBars.class.isInstance(service)) {
                    var bar = Java.cast(service, SystemBars).mStatusBar.value;
                    if (bar !== null && PhoneStatusBar.class.isInstance(bar)) {
                        return Java.cast(bar, PhoneStatusBar);
                    }
                }
            }
            throw new Error("PhoneStatusBar is not initialized");
        }

        var controller = createCarPlayStatusBarController({
            hideSystemBars: hideSystemBars,
            revealRemaining: revealRemaining,
            top: function (displayId) {
                var activity = ServiceManager.getDpyTopAppInfo(application, displayId, 2);
                return activity === null ? "" : String(activity);
            },
            stockHidden: function (activity, displayId) {
                return originalHit.call(InvisibleItem, activity, displayId)
                    && (!activity.endsWith("PictureMainActivity") || PhoneStatusBar.pictureFullScreen.value);
            },
            reportHidden: function (displayId, hidden) {
                var key = "voyahtune_carplay_statusbar_hidden_" + displayId;
                var value = hidden ? 1 : 0;
                if (Number(SettingsGlobal.getInt(resolver, key, -1)) !== value
                    && !SettingsGlobal.putInt(resolver, key, value)) {
                    throw new Error("Settings.Global rejected status-bar visibility");
                }
            },
            apply: function (displayId, visible, receiver) {
                originals[displayId].call(receiver || statusBar(), visible);
                if (lastVisible[displayId] !== visible) {
                    lastVisible[displayId] = visible;
                    Log.i("vt_carplay_statusbar", "visible=" + visible + " display=" + displayId);
                }
            },
            schedule: function (callback, delay) {
                setTimeout(function () { Java.scheduleOnMainThread(callback); }, delay);
            },
            error: function (error) { Log.e("vt_carplay_statusbar", String(error)); }
        });

        originalHit.implementation = function (activity, displayId) {
            if (isCarPlayStatusBarTarget(activity, displayId)) return hideSystemBars() && revealRemaining(displayId) === 0;
            return originalHit.call(this, activity, displayId);
        };
        installed.push(originalHit);
        originals.forEach(function (original, displayId) {
            original.implementation = function (visible) {
                controller.request(displayId, visible, this);
            };
            installed.push(original);
        });

        // Launcher requests only trigger reconciliation; the live top Activity owns visibility.
        var Handler = Java.registerClass({
            name: "ru.big.town.voyahtune.CarPlayStatusBarEvents",
            implements: [Java.use("com.qinggan.bus.QGBusEventHandler")],
            methods: {
                onHandleEvent: function (event) {
                    if (String(event.getSource()) !== "com.qinggan.app.launcher") return;
                    Java.scheduleOnMainThread(function () { controller.reconcile(); });
                }
            }
        });
        var filter = Java.use("com.qinggan.bus.QGBusEventFilter").$new();
        filter.addEventType("VOYAHTUNE_CARPLAY_STATUS_BAR_SYNC");
        Java.use("com.qinggan.bus.QGBus").$new(application).subscribe(filter, Handler.$new());
        var Observer = Java.registerClass({
            name: "ru.big.town.voyahtune.CarPlayChromeObserver",
            superClass: Java.use("android.database.ContentObserver"),
            methods: {
                $init: [{
                    returnType: "void",
                    argumentTypes: ["android.os.Handler"],
                    implementation: function (handler) { this.$super.$init(handler); }
                }],
                onChange: [{
                    returnType: "void",
                    argumentTypes: ["boolean"],
                    implementation: function () { controller.reconcile(); }
                }]
            }
        });
        observer = Observer.$new(Java.use("android.os.Handler").$new(
            Java.use("android.os.Looper").getMainLooper()));
        [0, 1].forEach(function (displayId) {
            resolver.registerContentObserver(SettingsGlobal.getUriFor(revealSetting + displayId), false, observer);
        });
        resolver.registerContentObserver(SettingsGlobal.getUriFor("voyahtune_carplay_hide_system_bars"), false, observer);
        installCarPlayAirQuality(application);
        controller.reconcile();
        console.log("[carplay-statusbar] hook ready v1");
        return true;
    } catch (error) {
        if (observer !== null) {
            try { application.getContentResolver().unregisterContentObserver(observer); } catch (ignored) { }
        }
        installed.forEach(function (method) { method.implementation = null; });
        console.log("[carplay-statusbar] hook failed v1: " + error);
        return false;
    }
}

function publishCarPlayAirQuality(io, value) {
    var measured = Number(value);
    var sample = io.bootCount() + ":" + (Number.isInteger(measured) && measured >= 0 ? measured : -1);
    if (io.read() !== sample) io.write(sample);
}

function installCarPlayAirQuality(application) {
    try {
        var SettingsGlobal = Java.use("android.provider.Settings$Global");
        var resolver = application.getContentResolver();
        var key = "voyahtune_carplay_air_quality";
        var io = {
            bootCount: function () { return Number(SettingsGlobal.getInt(resolver, "boot_count", -1)); },
            read: function () { return String(SettingsGlobal.getString(resolver, key)); },
            write: function (value) { SettingsGlobal.putString(resolver, key, value); }
        };
        var StatusBarView = Java.use("com.qinggan.systemui.statusbar.views.StatusBarView");
        var original = StatusBarView.setPm25Incar.overload("int");
        original.implementation = function (value) {
            original.call(this, value);
            try { publishCarPlayAirQuality(io, value); }
            catch (error) { console.log("[carplay-air-quality] update failed: " + error); }
        };
        // The adapter can attach after the first CAN update; seed the actual displayed value.
        Java.choose("com.qinggan.systemui.statusbar.views.StatusBarPm25View", {
            onMatch: function (view) {
                var text = String(view.pm25Value.value.getText());
                var visible = Number(Java.cast(view.getParent(), Java.use("android.view.View")).getVisibility()) === 0
                    && Number(view.getVisibility()) === 0;
                if (visible && /^\d+$/.test(text)) publishCarPlayAirQuality(io, Number(text));
            },
            onComplete: function () {}
        });
    } catch (error) {
        console.log("[carplay-air-quality] unavailable: " + error);
    }
}

// RDS persistence is intentionally in the RdsApp process: keep its OEM audio-focus and
// asynchronous source-switch protocol instead of reimplementing them in another Binder client.
// Pure controller below is also executed by test_rds_restore.js with a deterministic clock.
function createRdsRestoreController(io, initial) {
    function valid(s) {
        return s && typeof s.fm === "boolean" && Number.isInteger(s.freq)
            && (s.fm ? s.freq >= 6500 && s.freq <= 10800 : s.freq >= 150 && s.freq <= 30000)
            && typeof s.rds === "boolean" && Number.isInteger(s.pi) && s.pi >= 0 && s.pi <= 65535;
    }
    function same(a, b) {
        // This OEM can report the reset frequency with the previous station's PI/PS
        // after wake. PI alone proves neither a tune nor a genuine AF transition.
        return valid(a) && valid(b) && a.fm === b.fm && a.freq === b.freq;
    }
    function exact(a, b) {
        return a && b && a.fm === b.fm && a.freq === b.freq && a.rds === b.rds && a.pi === b.pi;
    }
    var saved = valid(initial) && !initial.ta ? initial : null;
    var selection = null;
    var scanRequested = false;
    var scanRevision = 0;
    var request = null;
    var revision = 0;
    var candidateRevision = 0;
    var candidateStation = null;
    var closed = false;
    var silentCycle = null;

    function cancel(reason, keepCandidate) {
        revision++;
        if (!keepCandidate) { candidateRevision++; candidateStation = null; }
        request = null;
        io.cancelOwned();
        io.log(reason);
    }
    function observe(station) {
        if (closed || !valid(station) || station.ta || !io.ready()) return;
        if (request && same(station, request.target)) {
            request = null;
            revision++;
            io.releaseOwned();
            io.log("restore confirmed " + station.freq);
        }
        if (scanRequested) return;
        if (selection && io.now() > selection.until) selection = null;
        if (selection ? !same(station, selection.target) : saved && !same(station, saved)) return;
        if (exact(candidateStation, station)) return;
        candidateStation = station;
        var candidate = ++candidateRevision;
        // A transient scan/TA/seek observation must not replace the durable station.
        io.later(function () {
            if (closed || candidate !== candidateRevision) return;
            candidateStation = null;
            if (scanRequested || !io.ready()) return;
            var current = io.current();
            if (!valid(current) || current.ta || !same(current, station)) return;
            if (selection && !same(current, selection.target)) return;
            if (exact(saved, current)) return;
            if (io.save(current)) {
                saved = current;
                selection = null;
                io.log("station saved " + current.freq);
            } else {
                io.log("station save failed; previous durable record retained");
            }
        }, 750);
    }
    function step(token) {
        if (closed || token !== revision || !request) return;
        if (io.now() >= request.until) {
            cancel("restore timed out without confirmation");
            return;
        }
        if (!io.hasFocus()) { cancel("radio focus lost"); return; }
        if (io.ready()) {
            var current = io.current();
            if (same(current, request.target) && !current.ta) {
                // A matching snapshot proves station state, but a resume still needs audioPlay.
                if (!request.submitted && !request.alreadyPlaying) io.playCurrent();
                observe(current);
                return;
            }
            if (!request.submitted) {
                request.submitted = true;
                io.log("restore submitted " + request.target.freq);
                try { io.tune(request.target); }
                catch (e) { cancel("restore dispatch failed: " + e); return; }
                // playStation may synchronously cause a callback, pause or another selection.
                if (!request || token !== revision) return;
            }
        }
        io.later(function () { step(token); }, 250);
    }
    return {
        observe: observe,
        restoreSilent: function (cycle) {
            if (closed || !Number.isInteger(cycle) || cycle <= 0 || cycle === silentCycle) return false;
            silentCycle = cycle;
            // A later Native pass must never undo a new user choice or an active playback request.
            if (!saved || scanRequested || request || (selection && io.now() <= selection.until)
                || io.isPlaying()) return false;
            io.stageSelected(saved);
            io.log("silent selection restored " + saved.freq + " cycle=" + cycle + "; tuner unchanged");
            return true;
        },
        select: function (station) {
            if (io.clearSelected) io.clearSelected();
            cancel("explicit station choice");
            scanRequested = false;
            selection = valid(station) ? { target: station, until: io.now() + 15000 } : null;
        },
        pause: function () {
            cancel("playback paused", true);
            // Keep an outstanding explicit choice so its late confirmation can still be saved.
        },
        scan: function () {
            if (io.clearSelected) io.clearSelected();
            cancel("explicit scan");
            selection = null;
            scanRequested = true;
            var token = ++scanRevision;
            // fullScan is void and may be denied by audio focus without any FINISH callback.
            // A lost callback must not permanently disable later playback requests.
            io.later(function () {
                if (!closed && token === scanRevision && scanRequested) {
                    scanRequested = false;
                    io.log("scan observation expired; durable station retained");
                }
            }, 60000);
        },
        scanFinished: function () {
            if (scanRequested) {
                scanRequested = false;
                var station = io.current();
                if (valid(station)) selection = { target: station, until: io.now() + 15000 };
            }
            observe(io.current());
        },
        resume: function (fallback, alreadyPlaying) {
            if (io.clearSelected) io.clearSelected();
            if (closed || scanRequested || (selection && io.now() <= selection.until) || !saved) {
                fallback();
                return;
            }
            if (request) {
                if (io.now() < request.until) return; // Repeated callbacks never extend a live deadline.
                cancel("previous restore expired before new playback request");
            }
            candidateRevision++;
            candidateStation = null;
            var token = ++revision;
            request = { target: saved, until: io.now() + 10000, submitted: false, alreadyPlaying: !!alreadyPlaying };
            // Register the stock listener immediately: a switch to another media source while
            // waiting for the tuner must cancel restoration instead of stealing focus later.
            if (!io.acquireFocus()) { cancel("radio focus unavailable"); return; }
            step(token);
        },
        close: function () { closed = true; if (io.clearSelected) io.clearSelected(); cancel("hook closed"); }
    };
}

function createRdsWidgetController(io) {
    function valid(station) {
        return station && typeof station.fm === "boolean" && Number.isInteger(station.freq)
            && (station.fm ? station.freq >= 6500 && station.freq <= 10800
                : station.freq >= 150 && station.freq <= 30000) && !station.ta;
    }
    function same(first, second) {
        return valid(first) && valid(second) && first.fm === second.fm && first.freq === second.freq;
    }
    function stations(station) {
        return (io.list() || []).filter(function (item) { return valid(item) && item.fm === station.fm; });
    }
    function snapshot() {
        var saved = io.saved();
        var current = io.current();
        var available = io.ready();
        var name = valid(saved) ? String(saved.name || "") : "";
        if (same(saved, current) && current.name) name = String(current.name);
        return {
            schema: 1, fm: valid(saved) ? saved.fm : true, freq: valid(saved) ? saved.freq : -1,
            name: name.trim().slice(0, 160), ready: available, playing: !!io.playing(),
            canSkip: available && valid(saved) && stations(saved).length > 1,
            message: available ? "" : "Радио не готово"
        };
    }
    return function (action, requested) {
        var response = snapshot();
        if (action === "snapshot") return response;
        response.accepted = false;
        if (action === "pause") {
            io.pause();
            response.accepted = true;
            return response;
        }
        if (!response.ready || !valid(requested)) return response;
        var target = { fm: requested.fm, freq: requested.freq, rds: false, pi: 0, ta: false };
        if (action === "next" || action === "previous") {
            var list = stations(target);
            if (list.length < 2) { response.message = "Нет списка станций"; return response; }
            var index = list.findIndex(function (station) { return same(station, target); });
            if (index >= 0) {
                target = list[(index + (action === "next" ? 1 : list.length - 1)) % list.length];
            } else {
                list = list.slice().sort(function (first, second) { return first.freq - second.freq; });
                if (action === "next") {
                    target = list.find(function (station) { return station.freq > requested.freq; }) || list[0];
                } else {
                    target = list.slice().reverse().find(function (station) { return station.freq < requested.freq; }) || list[list.length - 1];
                }
            }
        } else if (action !== "play") {
            return response;
        }
        // The displayed frequency is explicit intent, never an index or the tuner's wake default.
        io.tune(target);
        response.accepted = true;
        response.requestedFreq = target.freq;
        response.requestedFm = target.fm;
        return response;
    };
}

function installRdsStationRestore(application) {
    var TAG = "VoyahRdsRestore";
    var hooks = [];
    var controller = null;
    var owned = null;
    var dispatching = null;
    var cancelAfterDispatch = false;
    var ownsResumeDelay = false;
    var playbackCalls = 0;
    var stopped = false;
    var silentReceiver = null;
    var selectedStation = null;
    var Log = Java.use("android.util.Log");
    function log(text) { Log.i(TAG, text); }
    try {
        var Rds = Java.use("com.pateo.overSideRadio.base.dab.RdsManager");
        var Model = Java.use("com.pateo.rdsapp.main.model.RdsDataModel");
        var Info = Java.use("com.pateo.overSideRadio.base.dab.RdsManager$3");
        var SystemInfo = Java.use("com.pateo.overSideRadio.base.dab.RdsManager$4");
        var Station = Java.use("com.adayo.proxy.dab.aidl.beans.RadioStation");
        var Band = Java.use("com.adayo.proxy.dab.DABConstants$BAND_DEF");
        var Clock = Java.use("android.os.SystemClock");
        var AudioHelper = Java.use("com.qinggan.media.helper.AudioPolicyHelper");
        var MediaEnum = Java.use("com.qinggan.media.helper.MediaEnum");
        var prefs = application.getSharedPreferences("voyahtune_rds_restore", 0);
        // Validate every required signature before replacing anything. Unknown OEM builds fail open.
        var playStation = Rds.playStation.overload("com.adayo.proxy.dab.aidl.beans.RadioStation");
        var focus = Rds.requestAudioFocus.overload();
        var play = Rds.play.overload();
        var resume = Rds.resumePlay.overload();
        var modelPlay = Model.play.overload();
        var modelCurrent = Model.getCurRadioStation.overload();
        var modelPrivateCurrent = Model.getCurStation.overload();
        var ModelListener = Java.use("com.pateo.rdsapp.main.model.RdsDataModel$1");
        var modelUpdate = ModelListener.onCurrentStationUpdate.overload("com.adayo.proxy.dab.aidl.beans.RadioStation");
        var pause = Rds.pause.overload();
        var pauseNoAbandon = Rds.pauseNoAbandon.overload();
        var scan = Rds.fullScan.overload();
        var scanBand = Rds.fullScan.overload("com.adayo.proxy.dab.DABConstants$BAND_DEF");
        var switchBand = Rds.switchRadioSource.overload("com.adayo.proxy.dab.DABConstants$BAND_DEF");
        var update = Info.onCurrentStationUpdate.overload("com.adayo.proxy.dab.aidl.beans.RadioStation");
        var scanState = Info.onFreqSeekStateChanged.overload("com.adayo.proxy.dab.DABConstants$ScanState");
        var init = Rds.initData.overload();
        var audioStatus = SystemInfo.onAudioPlayStatus.overload("boolean");
        // Resolve only the existing singleton; never enumerate the ART heap (32-bit OEM crash).
        var manager = Rds.getInstance();
        function snapshot(station) {
            if (station === null) return null;
            var value = {
                fm: !!station.isFMStation(), freq: Number(station.getFreq()),
                rds: !!station.isRDS(), pi: Number(station.getPICode()), ta: !!station.isTAAssert()
            };
            try {
                var name = station.getPSName();
                if (name !== null && String(name).trim()) value.name = String(name).trim();
            } catch (_) { /* Station names are optional OEM metadata. */ }
            return value;
        }
        function current() { return snapshot(manager.getCurStation()); }
        function ready() {
            var s = current();
            return manager.isInit() && String(manager.getScanState().name()) === "FINISH" && !(s && s.ta);
        }
        var audio = AudioHelper.getInstance(application);
        function hasFocus() { return !audio.isCall() && audio.isCurMediaFocus(MediaEnum.RDS.value); }
        function releaseResumeDelay() {
            if (ownsResumeDelay) { manager.delayPlay.value = false; ownsResumeDelay = false; }
        }
        function releaseOwned() {
            releaseResumeDelay();
            if (owned !== null) { owned.$dispose(); owned = null; }
        }
        function cancelOwned() {
            releaseResumeDelay();
            if (owned === null) return;
            if (dispatching !== null) { cancelAfterDispatch = true; return; }
            // Only remove our own deferred tune; a later OEM/user command is never cleared.
            var delayed = manager.delayPlayStation.value;
            if (delayed !== null && delayed.equals(owned)) {
                manager.delayPlayStation.value = null;
                manager.delayPlay.value = false;
            }
            var pending = manager.tempPlayStation.value;
            if (pending !== null && pending.equals(owned)) {
                manager.tempPlayStation.value = null;
                if (Number(manager.actionCode.value) === 1002) manager.actionCode.value = -1;
                manager.handler.value.removeMessages(1002);
            }
            releaseOwned();
        }
        function tuneOwned(station) {
            cancelOwned();
            owned = Java.retain(Station.$new(station.freq));
            owned.setIsFMStation(station.fm);
            // Tune by frequency; the OEM PI can be stale after wake.
            owned.setIsRDS(false);
            owned.setPICode(0);
            dispatching = station;
            try { playbackCalls++; playStation.call(manager, owned); }
            finally {
                dispatching = null;
                if (cancelAfterDispatch) { cancelAfterDispatch = false; cancelOwned(); }
            }
        }
        // Touch the cancellation fields up front, before installing any hooks.
        manager.delayPlayStation.value;
        manager.delayPlay.value;
        manager.tempPlayStation.value;
        manager.actionCode.value;
        manager.handler.value;
        current(); ready();
        var record = null;
        var unknownSchema = false;
        try {
            var raw = prefs.getString("station", null);
            var parsed = raw === null ? null : JSON.parse(String(raw));
            if (parsed && parsed.schema === 1) record = parsed.station;
            else if (parsed !== null) unknownSchema = true;
        } catch (e) { log("saved station ignored: " + e); }
        if (unknownSchema) throw new Error("unsupported saved station schema; keeping OEM behavior");
        controller = createRdsRestoreController({
            now: function () { return Number(Clock.elapsedRealtime()); },
            later: function (fn, ms) {
                setTimeout(function () {
                    if (!stopped) Java.perform(function () {
                        Java.scheduleOnMainThread(function () {
                            if (!stopped) {
                                try { fn(); } catch (e) {
                                    log("deferred operation failed: " + e);
                                    try { controller.pause(); } catch (_) { /* request is already invalidated */ }
                                }
                            }
                        });
                    });
                }, ms);
            },
            current: current, ready: ready, log: log,
            hasFocus: hasFocus,
            isPlaying: function () { return hasFocus() && manager.isRdsSource() && manager.isPlay(); },
            clearSelected: function () {
                if (selectedStation !== null) { selectedStation.$dispose(); selectedStation = null; }
            },
            stageSelected: function (station) {
                if (selectedStation !== null) selectedStation.$dispose();
                selectedStation = Java.retain(Station.$new(station.freq));
                selectedStation.setIsFMStation(station.fm);
                selectedStation.setIsRDS(false); selectedStation.setPICode(0);
                // Presentation state only. Never manufacture tuner feedback or change its cache.
                var listener = Java.cast(Model.getInstance().rdsInfoListener.value, ModelListener);
                modelUpdate.call(listener, selectedStation);
            },
            acquireFocus: function () {
                // requestAudioFocus also sets delayPlay if the radio is not initialized yet.
                ownsResumeDelay = !manager.isInit()
                    || String(manager.getScanState().name()) !== "FINISH";
                focus.call(manager);
                return hasFocus();
            },
            cancelOwned: cancelOwned, releaseOwned: releaseOwned,
            save: function (station) {
                // One atomic record, independent of SP_PLAY_DATA. commit acknowledges the disk write;
                // writes occur only after a settled choice and never on every frequency callback.
                var committed = prefs.edit().putString("station", JSON.stringify({ schema: 1, station: station })).commit();
                if (committed) record = station;
                return committed;
            },
            playCurrent: function () { playbackCalls++; play.call(manager); },
            tune: tuneOwned
        }, record);
        function hook(method, replacement) {
            method.implementation = replacement;
            hooks.push(method);
        }
        function safely(fn) {
            try { fn(); return true; }
            catch (e) { log("restore operation skipped: " + e); return false; }
        }
        function requestResume(fallback, automatic) {
            var called = false;
            var callsBefore = playbackCalls;
            // Never repeat an original call if the OEM implementation itself throws.
            try {
                controller.resume(function () { called = true; fallback(); }, automatic);
            } catch (e) {
                safely(function () { controller.pause(); });
                if (called || playbackCalls !== callsBefore) throw e;
                log("restore skipped: " + e);
                fallback();
            }
        }
        hook(modelCurrent, function () {
            return selectedStation !== null ? selectedStation : modelCurrent.call(this);
        });
        hook(modelPrivateCurrent, function () {
            return selectedStation !== null ? selectedStation : modelPrivateCurrent.call(this);
        });
        hook(modelUpdate, function (station) {
            return modelUpdate.call(this, selectedStation !== null ? selectedStation : station);
        });
        hook(playStation, function (station) {
            // AudioPolicy.onResume can replay our delayed RadioStation through this public method.
            if (owned !== null && station !== null && station.equals(owned)) {
                dispatching = snapshot(station);
                try { return playStation.call(this, station); }
                finally {
                    dispatching = null;
                    if (cancelAfterDispatch) { cancelAfterDispatch = false; cancelOwned(); }
                }
            }
            safely(function () { controller.select(snapshot(station)); });
            var result = playStation.call(this, station);
            safely(function () { controller.observe(current()); });
            return result;
        });
        hook(play, function () { requestResume(function () { play.call(manager); }); });
        hook(resume, function () { requestResume(function () { resume.call(manager); }); });
        // The UI's play button calls Model.play -> playStation(current), not RdsManager.play.
        // Intercept it as resume so a reset default is not mistaken for a new explicit choice.
        hook(modelPlay, function () {
            var self = this;
            requestResume(function () { modelPlay.call(self); });
        });
        hook(pause, function () { safely(function () { controller.pause(); }); return pause.call(this); });
        hook(pauseNoAbandon, function () { safely(function () { controller.pause(); }); return pauseNoAbandon.call(this); });
        hook(scan, function () { safely(function () { controller.scan(); }); return scan.call(this); });
        hook(scanBand, function (band) { safely(function () { controller.scan(); }); return scanBand.call(this, band); });
        hook(switchBand, function (band) {
            // RdsApp 1.0's AM branch mistakenly requests FM. Correct only our restore call.
            return switchBand.call(this, dispatching ? (dispatching.fm ? Band.FM.value : Band.MW.value) : band);
        });
        hook(update, function (station) {
            var result = update.call(this, station);
            safely(function () { controller.observe(snapshot(station)); });
            return result;
        });
        hook(scanState, function (state) {
            var result = scanState.call(this, state);
            if (String(state.name()) === "FINISH") safely(function () { controller.scanFinished(); });
            return result;
        });
        var automaticPlaybackHandled = false;
        function resumeActiveRadio() {
            // Covers OEM automatic playback after wake/reconnection, without starting radio
            // on attach, SCREEN_ON, another media source or a paused tuner.
            if (!automaticPlaybackHandled && hasFocus() && manager.isRdsSource() && manager.isPlay()) {
                automaticPlaybackHandled = true;
                requestResume(function () { }, true);
            }
        }
        hook(init, function () {
            var result = init.call(this);
            safely(function () {
                controller.observe(current());
                automaticPlaybackHandled = false;
                resumeActiveRadio();
            });
            return result;
        });
        hook(audioStatus, function (paused) {
            var result = audioStatus.call(this, paused);
            safely(function () {
                if (!paused) resumeActiveRadio();
                else {
                    automaticPlaybackHandled = false;
                    // A band switch can report a transient pause while our tune is queued.
                    // Explicit pauses are intercepted above; lost focus always cancels the tune.
                    if (owned === null || !hasFocus()) controller.pause();
                }
            });
            return result;
        });
        // The provider owns the durable ACC cycle. Read it on attach as well, so a radio
        // process started after both broadcasts still recovers the selected station silently.
        function restoreAccSelection() {
            var Uri = Java.use("android.net.Uri");
            var call = Java.use("android.content.ContentResolver").call.overload(
                "android.net.Uri", "java.lang.String", "java.lang.String", "android.os.Bundle");
            var snapshot = call.call(application.getContentResolver(),
                Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                "driveHookV2", "snapshot", null);
            if (snapshot !== null && snapshot.getInt("protocol", -1) === 2 && snapshot.getInt("acc", -1) === 2) {
                controller.restoreSilent(Number(snapshot.getLong("cycle", -1)));
            }
        }
        var Receiver = Java.registerClass({
            name: "ru.big.town.voyahtune.RadioSelectionRestoreReceiver",
            superClass: Java.use("android.content.BroadcastReceiver"),
            methods: {
                onReceive: [{
                    returnType: "void", argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: function () { if (!stopped) safely(restoreAccSelection); }
                }]
            }
        });
        silentReceiver = Java.retain(Receiver.$new());
        application.registerReceiver.overload("android.content.BroadcastReceiver", "android.content.IntentFilter",
            "java.lang.String", "android.os.Handler").call(application, silentReceiver,
                Java.use("android.content.IntentFilter").$new("ru.big.town.anative.RESTORE_RADIO_SELECTION"),
                "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE",
                Java.use("android.os.Handler").$new(Java.use("android.os.Looper").getMainLooper()));
        Java.scheduleOnMainThread(function () {
            if (!stopped) {
                safely(restoreAccSelection);
                safely(function () { controller.observe(current()); });
            }
        });
        // Use the existing MediaSession transport; failures here do not disable station restoration.
        try {
            var Player = Java.use("com.pateo.midrds.media.RdsPlayer");
            var command = Player.onCommand.overload("java.lang.String", "android.os.Bundle", "android.os.ResultReceiver");
            var Bundle = Java.use("android.os.Bundle");
            var widget = createRdsWidgetController({
                saved: function () { return record; }, current: current,
                ready: function () { return ready() && !audio.isCall(); },
                playing: function () { return hasFocus() && manager.isRdsSource() && manager.isPlay(); },
                list: function () {
                    var result = [];
                    var list = manager.getPlayList();
                    if (list !== null) {
                        for (var index = 0; index < list.size(); index++) result.push(snapshot(Java.cast(list.get(index), Station)));
                    }
                    return result;
                },
                pause: function () { manager.pause(); },
                tune: function (target) {
                    controller.select(target);
                    tuneOwned(target);
                    var submitted = owned;
                    setTimeout(function () {
                        if (!stopped) Java.perform(function () {
                            Java.scheduleOnMainThread(function () {
                                if (!stopped && owned === submitted) safely(cancelOwned);
                            });
                        });
                    }, 6000);
                    controller.observe(current());
                }
            });
            hook(command, function (name, extras, receiver) {
                if (String(name) !== "ru.big.town.voyahtune.RADIO_WIDGET") return command.call(this, name, extras, receiver);
                if (receiver === null) return;
                var response = Bundle.$new();
                try {
                    var caller = String(this.getCallingPackage());
                    if (caller !== "ru.big.town.restoremode" && caller !== "ru.big.town.anative") throw new Error("untrusted radio controller");
                    if (extras === null || extras.getInt("schema", -1) !== 1) throw new Error("unsupported widget protocol");
                    var result = widget(String(extras.getString("action", "")), {
                        fm: !!extras.getBoolean("fm", true), freq: Number(extras.getInt("freq", -1))
                    });
                    Object.keys(result).forEach(function (key) {
                        if (typeof result[key] === "boolean") response.putBoolean(key, result[key]);
                        else if (typeof result[key] === "number") response.putInt(key, result[key]);
                        else response.putString(key, result[key]);
                    });
                    receiver.send(1, response);
                } catch (error) {
                    log("widget command failed: " + error);
                    receiver.send(0, response);
                }
            });
        } catch (error) { log("widget bridge unavailable: " + error); }
        // RdsApp never activates its session, so Android cannot select it for wheel commands.
        try {
            var RadioService = Java.use("com.pateo.midrds.media.RdsMediaBrowserService");
            var PlayerListener = Java.use("com.pateo.midrds.media.RdsPlayer$1");
            var serviceCreate = RadioService.onCreate.overload();
            var serviceDestroy = Java.use("com.pateo.overSideRadio.base.media.BaseBrowserService").onDestroy.overload();
            var notification = Java.use("com.pateo.overSideRadio.base.media.MediaNotificationManager").getNotification.overload(
                "android.support.v4.media.MediaMetadataCompat", "android.support.v4.media.session.PlaybackStateCompat",
                "android.support.v4.media.session.MediaSessionCompat$Token");
            var StateBuilder = Java.use("android.support.v4.media.session.PlaybackStateCompat$Builder");
            hook(notification, function (metadata, state, token) {
                // The OEM compact layout unconditionally references three actions, even at startup.
                var complete = StateBuilder.$new(state).setActions(Number(state.getActions()) | 16 | 32).build();
                return notification.call(this, metadata, complete, token);
            });
            function publishRadioSession(player) {
                var playing = hasFocus() && manager.isRdsSource() && manager.isPlay();
                player.setPlaybackState(player.createPlaybackStateCompat(playing));
                // Keep the paused session discoverable so the next toggle can resume it.
                player.mMediaSession.value.setActive(true);
            }
            hook(serviceCreate, function () {
                serviceCreate.call(this);
                var service = this;
                safely(function () { publishRadioSession(service.rdsPlayer.value); });
            });
            hook(serviceDestroy, function () {
                if (String(this.getClass().getName()) === "com.pateo.midrds.media.RdsMediaBrowserService") {
                    var service = Java.cast(this, RadioService);
                    safely(function () { service.rdsPlayer.value.mMediaSession.value.setActive(false); });
                }
                return serviceDestroy.call(this);
            });
            Java.scheduleOnMainThread(function () {
                if (stopped) return;
                safely(function () {
                    // Resolve existing players through the OEM listener registry, never ART heap scans.
                    var listeners = manager.infoListeners.value.iterator();
                    var found = false;
                    while (listeners.hasNext()) {
                        var listener = listeners.next();
                        if (String(listener.getClass().getName()) === "com.pateo.midrds.media.RdsPlayer$1") {
                            publishRadioSession(Java.cast(listener, PlayerListener).this$0.value);
                            found = true;
                        }
                    }
                    if (!found) {
                        // This stock service only publishes metadata; creation does not request audio.
                        var intent = Java.use("android.content.Intent").$new();
                        intent.setClassName("com.pateo.rdsapp", "com.pateo.midrds.media.RdsMediaBrowserService");
                        application.startForegroundService(intent);
                    }
                });
            });
        } catch (error) { log("radio media session unavailable: " + error); }
        log("ready; silent ACC selection, tuning on requested or confirmed active radio playback");
        console.log("[rds-restore] hook ready v1");
    } catch (e) {
        stopped = true;
        if (silentReceiver !== null) { try { application.unregisterReceiver(silentReceiver); } catch (_) { } }
        if (controller !== null) {
            try { controller.close(); } catch (_) { /* Keep rolling back all installed replacements. */ }
        }
        for (var i = hooks.length - 1; i >= 0; i--) hooks[i].implementation = null;
        log("hook failed: " + e);
        console.log("[rds-restore] hook failed v1: " + e);
    }
}
function createNavigationSourcePolicy() {
    var yandexIcons = {
        STRAIGHT: 1, SLIGHT_LEFT: 8, SLIGHT_RIGHT: 2, LEFT: 7, RIGHT: 3,
        HARD_LEFT: 6, HARD_RIGHT: 4, FORK_LEFT: 8, FORK_RIGHT: 2,
        UTURN_LEFT: 5, UTURN_RIGHT: 0, ENTER_ROUNDABOUT: 9, LEAVE_ROUNDABOUT: 9,
        EXIT_LEFT: 8, EXIT_RIGHT: 2
    };
    var dgisIcons = {
        from: 1, startA: 1, start: 1, crossroad_straight: 1,
        crossroad_left: 7, crossroad_right: 3,
        crossroad_keep_left: 8, crossroad_keep_right: 2,
        crossroad_slightly_left: 8, crossroad_slightly_right: 2,
        crossroad_sharply_left: 6, crossroad_sharply_right: 4,
        crossroad_uturn: 5, turn_over_right_hand: 5,
        turn_over_left_hand: 0, turn_over: 0, left_ring_exit: 9,
        right_ring_exit: 0, ringroad_exit: 0,
        ringroad_backward: 9, ringroad_forward: 9,
        ringroad_left_135: 9, ringroad_left_180: 9,
        ringroad_left_45: 9, ringroad_left_90: 9,
        ringroad_right_135: 9, ringroad_right_45: 9, ringroad_right_90: 9
    };

    function icon(table, type) {
        return Object.prototype.hasOwnProperty.call(table, type) ? table[type] : 0;
    }

    return {
        yandexIcon: function (action) { return icon(yandexIcons, action); },
        dgisIcon: function (name) {
            return icon(dgisIcons, name.split("/").pop().replace(/\.svg$/, ""));
        },
        driving: function (mode) { return mode === "car"; },
        meters: function (text) {
            var match = /^\s*([0-9]+(?:[ \u00a0\u202f][0-9]{3})*(?:[.,][0-9]{1,2})?)\s*(km|км|m|м)\s*$/i.exec(text);
            if (match === null) return -1;
            var number = Number(match[1].replace(/[ \u00a0\u202f]/g, "").replace(",", "."));
            var distance = number * (/^(km|км)$/i.test(match[2]) ? 1000 : 1);
            return Number.isFinite(distance) && distance <= 2147483647 ? Math.round(distance) : -1;
        }
    };
}

function createYandexGuidanceListenerAdapter(listenerMethods) {
    var listener = Java.use("com.yandex.navikit.guidance.GuidanceListener");
    var methods = Object.assign({}, listenerMethods);
    ["onJamForecastUpdated", "onGuidanceResumedChanged"].forEach(function (name) {
        if (!listener[name]) return;
        listener[name].overload();
        // These notifications do not establish a fresh route position.
        methods[name] = function () { };
    });
    return { type: listener, methods: methods };
}

function installNavigationSource(application) {
    try {
        var packageName = String(application.getPackageName());
        if (packageName !== "ru.yandex.yandexnavi" && packageName !== "ru.dublgis.dgismobile") {
            return false;
        }
        var policy = createNavigationSourcePolicy();
        var SystemClock = Java.use("android.os.SystemClock");
        var SettingsGlobal = Java.use("android.provider.Settings$Global");
        var Bundle = Java.use("android.os.Bundle");
        var Uri = Java.use("android.net.Uri");
        var resolver = application.getContentResolver();
        var endpoint = Uri.parse("content://ru.big.town.anative.cluster.navigation");
        var callProvider = resolver.call.overload("android.net.Uri", "java.lang.String",
            "java.lang.String", "android.os.Bundle");
        var setting = "voyahtune_cluster_navigation_enabled";
        var freshnessMs = 30000;
        var mainPending = false;
        var sending = false;
        var pending = null;
        var enabled = true;
        var lastError = "";
        var readSnapshot;

        function now() { return Number(SystemClock.elapsedRealtime()); }

        function warn(error) {
            var message = String(error);
            if (message === lastError) return;
            lastError = message;
            console.log("[navigation-source] " + packageName + ": " + message);
        }

        function queue(snapshot) {
            pending = { snapshot: snapshot, sampled: now() };
            if (sending) return;
            sending = true;
            setTimeout(function deliver() {
                Java.perform(function () {
                    var delivery = pending;
                    pending = null;
                    try {
                        var value = delivery.snapshot;
                        var data = Bundle.$new();
                        data.putBoolean("navigationActive", value !== null && enabled);
                        data.putInt("navigationIcon", value === null ? 0 : value.icon);
                        data.putInt("navigationDistance", value === null ? 0 : value.distance);
                        data.putString("navigationRoad", value === null ? "" : value.road);
                        data.putLong("navigationTime", delivery.sampled);
                        callProvider.call(resolver, endpoint, "maneuver", null, data);
                    } catch (error) {
                        warn(error);
                    } finally {
                        sending = false;
                        if (pending !== null) {
                            sending = true;
                            setTimeout(deliver, 0);
                        }
                    }
                });
            }, 0);
        }

        if (packageName === "ru.yandex.yandexnavi") {
            var Guidance = Java.use("com.yandex.navikit.guidance.internal.GuidanceBinding");
            var UpcomingManoeuvre = Java.use("com.yandex.mapkit.navigation.automotive.UpcomingManoeuvre");
            var WeakReference = Java.use("java.lang.ref.WeakReference");
            var currentGuidance = null;
            var candidateGuidance = null;
            var listener = null;
            var listenerReference = null;
            var changedAt = -1;
            var finished = false;
            var listenerMethods = {};
            ["onBackgroundGuidanceTaskRemoved", "onFasterAlternativeAnnotated",
                "onFasterAlternativeUpdated", "onFreeDriveRouteChanged", "onManeuverAnnotated",
                "onReachedWayPoint", "onStandingStatusUpdated"].forEach(function (name) {
                    listenerMethods[name] = function () { };
                });
            listenerMethods.onBackgroundGuidanceWillBeSuspended = function (reason) {
                changedAt = -1;
                queue(null);
            };
            listenerMethods.onParkingRouteBuilt = function (type) { };
            listenerMethods.onLocationUpdated = function () { changedAt = now(); };
            listenerMethods.onRoutePositionUpdated = function () { changedAt = now(); };
            listenerMethods.onRouteChanged = function () {
                finished = false;
                changedAt = now();
                queue(null);
            };
            listenerMethods.onFinishedRoute = function () {
                finished = true;
                queue(null);
            };
            var listenerAdapter = createYandexGuidanceListenerAdapter(listenerMethods);
            var Listener = Java.registerClass({
                name: "ru.big.town.navigation.YandexGuidanceListener",
                implements: [listenerAdapter.type],
                methods: listenerAdapter.methods
            });
            var getter = Guidance.getNextManeuver.overload();
            var routeGetter = Guidance.route.overload();
            var navigationGetter = Guidance.navigation.overload();
            var onStart = Guidance.onStart.overload();
            var constructor = Guidance.$init.overload("com.yandex.runtime.NativeObject");

            function capture(candidate) {
                if (currentGuidance !== null && currentGuidance.equals(candidate)) return;
                if (candidateGuidance !== null) return;
                candidateGuidance = Java.retain(candidate);
                Java.scheduleOnMainThread(function () {
                    var next = candidateGuidance;
                    try {
                        if (!next.isValid() || (currentGuidance !== null && next.route() === null)) return;
                        var nextListener = Listener.$new();
                        var nextReference = WeakReference.$new(nextListener);
                        next.addGuidanceListener(nextReference);
                        if (currentGuidance !== null) {
                            try {
                                if (currentGuidance.isValid()) {
                                    currentGuidance.removeGuidanceListener(listenerReference);
                                }
                            } catch (error) { warn(error); }
                            currentGuidance.$dispose();
                            listenerReference.$dispose();
                            listener.$dispose();
                        }
                        currentGuidance = next;
                        candidateGuidance = null;
                        listener = nextListener;
                        listenerReference = nextReference;
                        changedAt = now();
                        finished = false;
                    } catch (error) {
                        warn(error);
                    } finally {
                        if (candidateGuidance !== null) {
                            candidateGuidance.$dispose();
                            candidateGuidance = null;
                        }
                    }
                });
            }

            [getter, routeGetter, navigationGetter, onStart].forEach(function (method) {
                method.implementation = function () {
                    var result = method.call(this);
                    try { capture(this); } catch (error) { warn(error); }
                    return result;
                };
            });
            // Constructors catch a cold start; getter hooks discover a route after late attach.
            constructor.implementation = function (nativeObject) {
                constructor.call(this, nativeObject);
                try { capture(this); } catch (error) { warn(error); }
            };

            readSnapshot = function () {
                if (currentGuidance === null || !currentGuidance.isValid() || finished
                    || changedAt < 0 || now() - changedAt >= freshnessMs
                    || currentGuidance.route() === null) return null;
                var next = currentGuidance.getNextManeuver();
                if (next === null) return null;
                var guidance = currentGuidance.navigation().getGuidance();
                var status = String(guidance.getRouteStatus().name());
                if (guidance.getCurrentRoute() === null || (status !== "ON_ROUTE"
                    && status !== "RETURNED_TO_ROUTE" && status !== "WAY_POINT_REACHED")) return null;
                var point = next.getPoint();
                var maneuvers = guidance.getWindshield().getManoeuvres();
                for (var index = 0; index < maneuvers.size(); index++) {
                    var maneuver = Java.cast(maneuvers.get(index), UpcomingManoeuvre);
                    var position = maneuver.getPosition().getPoint();
                    // Match the numeric distance to its own annotation, including chained turns.
                    if (Math.abs(point.getLatitude() - position.getLatitude()) > 0.00001
                        || Math.abs(point.getLongitude() - position.getLongitude()) > 0.00001) continue;
                    var distance = Number(next.getDistance());
                    if (!Number.isFinite(distance) || distance < 0 || distance > 2147483647) return null;
                    var annotation = maneuver.getAnnotation();
                    var road = annotation.getToponym();
                    return {
                        icon: policy.yandexIcon(String(annotation.getAction().name())),
                        distance: Math.round(distance), road: road === null ? "" : String(road)
                    };
                }
                return null;
            };
        } else {
            var Dispatcher = Java.use("ru.dublgis.qsdk.IntegrationApplicationStateDispatcher");
            var stateChanged = Dispatcher.setNavigationState.overload(
                "java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String",
                "java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String", "int");
            var modeChanged = Dispatcher.setActiveNavigationMode.overload("java.lang.String");
            var stateAt = -1;
            var discovered = false;
            var previousMode = "";
            stateChanged.implementation = function (arrival, remaining, total, distance,
                icon, description, barrier, barrierDescription, progress) {
                stateChanged.call(this, arrival, remaining, total, distance,
                    icon, description, barrier, barrierDescription, progress);
                try { stateAt = now(); } catch (error) { warn(error); }
            };
            modeChanged.implementation = function (mode) {
                modeChanged.call(this, mode);
                try {
                    discovered = true;
                    if (previousMode !== String(mode)) {
                        stateAt = -1;
                        previousMode = String(mode);
                        queue(null);
                    }
                } catch (error) { warn(error); }
            };
            readSnapshot = function () {
                var dispatcher = Dispatcher.sInstance.value;
                if (dispatcher === null) return null;
                if (!discovered) {
                    discovered = true;
                    previousMode = String(dispatcher.getActiveNavigationMode());
                    stateAt = now();
                }
                if (!policy.driving(String(dispatcher.getActiveNavigationMode()))
                    || stateAt < 0 || now() - stateAt >= freshnessMs
                    || dispatcher.getSpeedInfo().mBadLocation.value) return null;
                var state = dispatcher.getNavigationState();
                var distance = policy.meters(String(state.mManeuverDistance.value));
                if (distance < 0 || String(state.mManeuverIcon.value) === "") return null;
                return {
                    icon: policy.dgisIcon(String(state.mManeuverIcon.value)),
                    distance: distance, road: String(state.mManeuverDescription.value || "")
                };
            };
        }

        function tick() {
            Java.perform(function () {
                try {
                    enabled = String(SettingsGlobal.getString(resolver, setting)) !== "0";
                    if (!enabled) {
                        queue(null);
                        return;
                    }
                    if (mainPending) return;
                    mainPending = true;
                    Java.scheduleOnMainThread(function () {
                        try { queue(readSnapshot()); }
                        catch (error) { queue(null); warn(error); }
                        finally { mainPending = false; }
                    });
                } catch (error) { warn(error); }
            });
        }
        setInterval(tick, 1000);
        tick();
        console.log("[navigation-source] hook ready v1");
        return true;
    } catch (error) {
        console.log("[navigation-source] hook unavailable: " + error);
        return false;
    }
}
