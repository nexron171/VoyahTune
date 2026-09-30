// Client-side per-application repairs for packages selected in VoyahTune.
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
//
// Yandex MapKit owns a second, renderer-side density value. A physical Task density override scales
// Android resources but does not necessarily update MapWindow, so the native Vulkan/GL map can stay
// at the 160-dpi scale. For the three known Yandex/Yango map applications this agent mirrors the
// selected per-app DPI to MapWindow.scaleFactor (physical pixels per independent point). The Surface
// keeps its native physical resolution; no VirtualDisplay or compositor upscaling is involved.

Java.perform(function () {
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
    if (packageName === "com.pateo.rdsapp") {
        // Finish Application.onCreate before resolving RdsManager's singleton. Initializing it
        // from the attach thread too early can see BaseApplication.mContext == null.
        Java.scheduleOnMainThread(function () { installRdsStationRestore(application); });
        return;
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
        try { className = "" + windowObject.$className; } catch (ignoredClass) {}
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
                    } catch (ignoredBaseline) {}
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
            } catch (ignoredSize) {}
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
        try { setView.implementation = null; } catch (ignoredSetView) {}
        try { setLayoutParams.implementation = null; } catch (ignoredSetLayout) {}
        try { application.unregisterReceiver(reloadReceiver); } catch (ignoredReceiver) {}
        Log.e(TAG, "ViewRootImpl hook installation failed: " + e);
        console.log("[app-client] hook failed v1: ViewRootImpl: " + e);
        return;
    }

    refreshPolicy("attach");
    Log.i(TAG, READY_MARKER + " package=" + packageName + " enabled=" + enabled
        + " mapkitDpi=" + mapkitDpi + " mapkitHooks=" + mapkitHooksInstalled);
    console.log(READY_MARKER + " package=" + packageName + " enabled=" + enabled
        + " mapkitDpi=" + mapkitDpi + " mapkitHooks=" + mapkitHooksInstalled);
});

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
        return valid(a) && valid(b) && a.fm === b.fm
            && (a.freq === b.freq || (a.fm && a.rds && b.rds && a.pi > 0 && a.pi === b.pi));
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
        select: function (station) {
            cancel("explicit station choice");
            scanRequested = false;
            selection = valid(station) ? {target: station, until: io.now() + 15000} : null;
        },
        pause: function () {
            cancel("playback paused", true);
            // Keep an outstanding explicit choice so its late confirmation can still be saved.
        },
        scan: function () {
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
                if (valid(station)) selection = {target: station, until: io.now() + 15000};
            }
            observe(io.current());
        },
        resume: function (fallback, alreadyPlaying) {
            if (closed || scanRequested || (selection && io.now() <= selection.until) || !saved) {
                fallback();
                return;
            }
            if (request) return; // Repeated OEM resume callbacks do not extend the deadline.
            candidateRevision++;
            candidateStation = null;
            var token = ++revision;
            request = {target: saved, until: io.now() + 10000, submitted: false, alreadyPlaying: !!alreadyPlaying};
            // Register the stock listener immediately: a switch to another media source while
            // waiting for the tuner must cancel restoration instead of stealing focus later.
            if (!io.acquireFocus()) { cancel("radio focus unavailable"); return; }
            step(token);
        },
        close: function () { closed = true; cancel("hook closed"); }
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
            return {fm: !!station.isFMStation(), freq: Number(station.getFreq()),
                rds: !!station.isRDS(), pi: Number(station.getPICode()),
                ta: !!station.isTAAssert()};
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
                return prefs.edit().putString("station", JSON.stringify({schema: 1, station: station})).commit();
            },
            playCurrent: function () { playbackCalls++; play.call(manager); },
            tune: function (station) {
                cancelOwned();
                owned = Java.retain(Station.$new(station.freq));
                owned.setIsFMStation(station.fm);
                owned.setIsRDS(station.rds && station.pi > 0);
                owned.setPICode(station.pi);
                dispatching = station;
                try { playbackCalls++; playStation.call(manager, owned); }
                finally {
                    dispatching = null;
                    if (cancelAfterDispatch) { cancelAfterDispatch = false; cancelOwned(); }
                }
            }
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
                requestResume(function () {}, true);
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
        Java.scheduleOnMainThread(function () {
            if (!stopped) safely(function () { controller.observe(current()); }); // Never tunes on attach.
        });
        log("ready; restore on requested or confirmed active radio playback");
        console.log("[rds-restore] hook ready v1");
        console.log("[app-client] hook ready v1");
    } catch (e) {
        stopped = true;
        if (controller !== null) {
            try { controller.close(); } catch (_) { /* Keep rolling back all installed replacements. */ }
        }
        for (var i = hooks.length - 1; i >= 0; i--) hooks[i].implementation = null;
        log("hook failed: " + e);
        console.log("[rds-restore] hook failed v1: " + e);
    }
}
