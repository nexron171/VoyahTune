// Shared NavigationBar instances are distinguished by mScreenId. Preserve the OEM
// LauncherModel transfer lifecycle and passenger Home -> All Apps routing together.
VoyahLauncherAdapters["shared-navigation"] = function (adapter) {

    var STOCK_SLOT_PKG = { 1: "com.qinggan.bluetoothphone", 2: "com.qinggan.app.music" };
    var NAV_BAR = adapter.navigation;
    var MODEL = "com.qinggan.app.launcher.LauncherModel";
    var RELOAD_ACT = "ru.big.town.anative.DOCK_RELOAD";
    var LAUNCHER_PKG = "com.qinggan.app.launcher";

    var LAUNCHER_HOME_ACT = "com.qinggan.app.launcher.activity.MainActivity";
    var OUR_PKG = "ru.big.town.anative";
    var RESTORE_PKG = "ru.big.town.restoremode";
    var CARPLAY_TARGET = RESTORE_PKG + "/com.shilapi.xcertplay.DiPlayActivity";
    var CARPLAY_LAUNCHER = RESTORE_PKG + ".carplay.CarPlayLauncherActivity";

    var RESTORE_TASK_ACT = "ru.big.town.restoremode.TaskManagerActivity";
    var SECOND_HOME = "com.qinggan.secondlauncher.activity.SecondMainActivity";
    var SECOND_ALL_APPS = "com.qinggan.secondlauncher.activity.AllAppActivity";

    var TOP_SEPARATOR = "/";

    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var SystemClock = Java.use("android.os.SystemClock");
    var SystemProps = Java.use("android.os.SystemProperties");
    var Intent = Java.use("android.content.Intent");
    var IntentFilter = Java.use("android.content.IntentFilter");
    var Receiver = Java.use("android.content.BroadcastReceiver");
    var LongClick = Java.use("android.view.View$OnLongClickListener");
    var Bitmap = Java.use("android.graphics.Bitmap");
    var BitmapConfig = Java.use("android.graphics.Bitmap$Config");
    var BitmapDrawable = Java.use("android.graphics.drawable.BitmapDrawable");
    var Canvas = Java.use("android.graphics.Canvas");
    var Log = Java.use("android.util.Log");
    var LauncherAppUtils = Java.use("com.qinggan.launcher.base.utils.AppUtils");

    var TAG = "vt_launcherdock";
    var DLOG = "voyahdock";

    var JavaSystem = Java.use("java.lang.System");
    var AGENT_MARK = "ru.big.town.dock.agent";
    if (cleanJavaString(JavaSystem.getProperty(AGENT_MARK)) === "1") {
        Log.w(TAG, "[dock] agent already installed in this process — skipping second injection");
        return;
    }
    JavaSystem.setProperty(AGENT_MARK, "1");

    function cleanJavaString(value) {
        if (value === null || value === undefined)
            return "";
        var result = "" + value;
        return (result === "null" || result === "undefined") ? "" : result;
    }

    function field(instance, name) {
        try {
            return instance[name].value;
        } catch (e) {
            return null;
        }
    }

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

    var cache = { dock1: "none", dock2: "none", fullscreen: {} };

    var originalBg = {};

    var retained = [];
    var MAX_RETAINED_DRAWABLES = 64;

    var lastSlot = 0;

    var slotByViewId = {};

    var fgByScreen = { 0: { pkg: "", act: "" }, 1: { pkg: "", act: "" } };

    var moveDockGuards = { 0: { deadline: 0, pkg: "" }, 1: { deadline: 0, pkg: "" } };

    var moveBarSnapshot = { 0: null, 1: null };

    var launcherModel = null;

    function rememberModel(model) {
        if (launcherModel === null && model !== null)
            launcherModel = Java.retain(model);
        return launcherModel;
    }

    function findModel() {
        if (launcherModel !== null)
            return launcherModel;
        Java.choose(MODEL, {
            onMatch: function (inst) {
                rememberModel(inst);
                return "stop";
            },
            onComplete: function () { }
        });
        return launcherModel;
    }

    function barOf(model, sid) {
        if (model === null)
            return null;
        return field(model, sid === 0 ? "mMainScreenNavigationBar" : "mSecondScreenNavigationBar");
    }

    function dockPinDisabled() {
        return cfg("dockpin") === "0" || cfg("freeform") === "0";
    }

    function activeMoveDockGuard() {
        if (dockPinDisabled())
            return null;
        var now = Number(SystemClock.elapsedRealtime());
        for (var sid = 0; sid <= 1; sid++) {
            var guard = moveDockGuards[sid];
            if (guard.deadline > now && guard.deadline - now <= 10000) {
                return { screen: sid, pkg: guard.pkg, remaining: guard.deadline - now };
            }
        }
        return null;
    }

    function clearMoveDockGuards() {
        for (var sid = 0; sid <= 1; sid++) {
            moveDockGuards[sid].deadline = 0;
            moveDockGuards[sid].pkg = "";
        }
    }

    function snapshotMoveDockState(model) {
        for (var sid = 0; sid <= 1; sid++) {
            var shown = null;
            try {
                var bar = barOf(model, sid);
                if (bar !== null)
                    shown = !!bar.isShowing();
            } catch (e) {
                shown = null;
            }
            moveBarSnapshot[sid] = shown;
        }
        Log.i(DLOG, "move snapshot main=" + moveBarSnapshot[0] + " second=" + moveBarSnapshot[1]);
    }

    var STOCK_PREFIX = [
        "com.android", "com.qinggan", "com.pateo", "com.baidu", "com.huawei", "com.iflytek", "com.iland", "com.mega",
        "com.qti", "com.qualcomm", "com.tencent", "com.nng.igo.primong", "com.bz.CA08"
    ];
    function isStockPkg(pkg) {
        pkg = cleanJavaString(pkg);
        if (!pkg)
            return true;
        if (pkg === "com.android.settings" || pkg === "com.android.documentsui")
            return false;
        for (var i = 0; i < STOCK_PREFIX.length; i++)
            if (pkg.indexOf(STOCK_PREFIX[i]) === 0)
                return true;
        return false;
    }

    function fullscreenPackageSet(csv) {
        var out = {};
        if (csv && csv !== "none") {
            var packages = csv.split(",");
            for (var i = 0; i < packages.length; i++) {
                var pkg = cleanJavaString(packages[i]);
                if (pkg)
                    out[pkg] = true;
            }
        }
        return out;
    }

    function isUserFullscreen(pkg) {
        pkg = cleanJavaString(pkg);
        return !!pkg && cache.fullscreen[pkg] === true;
    }

    function ourInsetActivity(act) {
        act = cleanJavaString(act);
        return act.indexOf("SplitHostActivity") >= 0 || act.indexOf("restoremode.MainActivity") >= 0 ||
            act === "com.shilapi.xcertplay.DiPlayActivity" || act === "com.shilapi.xcertplay.CarPlayHostActivity" ||
            act === CARPLAY_LAUNCHER || act.indexOf("AdvanceActivity") >= 0 ||
            act.indexOf("TaskManagerActivity") >= 0 || act.indexOf("TripHistoryActivity") >= 0;
    }

    function dockKept(pkg, act) {
        pkg = cleanJavaString(pkg);
        act = cleanJavaString(act);
        if (dockPinDisabled())
            return false;
        if (!pkg)
            return false;
        if (isUserFullscreen(pkg))
            return false;
        if (pkg.indexOf("ru.big.town") === 0)
            return ourInsetActivity(act);
        return !isStockPkg(pkg);
    }

    function launcherHomeDock(screenId, pkg, act) {
        if (pkg !== LAUNCHER_PKG)
            return null;
        if (act.indexOf("SecondMainActivity") >= 0)
            return false;
        return screenId === 0 ? true : null;
    }

    function desiredDockVisible(screenId, pkg, act) {
        pkg = cleanJavaString(pkg);
        act = cleanJavaString(act);
        if (!pkg)
            return null;
        if (isUserFullscreen(pkg))
            return false;
        if (dockKept(pkg, act))
            return true;
        return launcherHomeDock(screenId, pkg, act);
    }

    function pendingDockLaunch(screenId) {
        if (dockPinDisabled())
            return null;
        var raw = cfg("dockLaunchGuard" + screenId);
        if (raw === "none")
            return null;
        var sep = raw.indexOf("|");
        if (sep <= 0 || sep >= raw.length - 1)
            return null;
        var deadline = parseInt(raw.substring(0, sep), 10);
        var remaining = deadline - Number(SystemClock.elapsedRealtime());

        if (isNaN(deadline) || remaining <= 0 || remaining > 10000)
            return null;
        var pkg = raw.substring(sep + 1);
        if (isUserFullscreen(pkg))
            return null;
        var keep = (pkg === OUR_PKG || pkg === RESTORE_PKG) || (pkg.indexOf("ru.big.town") !== 0 && !isStockPkg(pkg));
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

    function cfg(key) {
        try {
            if (resolver === null)
                resolver = Java.retain(ctx().getContentResolver());
            var v = SettingsGlobal.getString(resolver, "voyahtune_" + key);
            return (v === null || v === "") ? "none" : "" + v;
        } catch (e) {
            return "none";
        }
    }

    function parseTopActivity(top) {
        top = cleanJavaString(top);
        var at = top.indexOf(TOP_SEPARATOR);
        if (at < 0)
            return { pkg: top, act: "" };
        return { pkg: top.substring(0, at), act: top.substring(at + TOP_SEPARATOR.length) };
    }

    function topActivityForScreen(screenId) {
        var cached = fgByScreen[screenId];
        try {
            var parsed = parseTopActivity(LauncherAppUtils.getTopAppInfo(ctx(), screenId, 4));
            if (!parsed.pkg)
                return { pkg: cached.pkg, act: cached.act, live: false };
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
        Log.i(
            TAG,
            "[dock] cache: driver=" + cache.dock1 + "/" + cache.dock2 +
            " fullscreen=" + Object.keys(cache.fullscreen).join(","));
    }

    function dockPackage(slot, live) {
        if (live)
            return cfg("dock" + slot);
        return slot === 1 ? cache.dock1 : cache.dock2;
    }

    function isInstalled(target) {
        if (target === "none")
            return false;
        try {
            var packageManager = ctx().getPackageManager();
            if (target === CARPLAY_TARGET) {
                var component = Java.use("android.content.ComponentName").$new(RESTORE_PKG, CARPLAY_LAUNCHER);
                var activity = packageManager.getActivityInfo(component, 0);
                return !!activity.enabled.value && !!activity.exported.value;
            }
            return packageManager.getLaunchIntentForPackage(target) !== null;
        } catch (error) {
            return false;
        }
    }

    function retainDrawable(obj) {
        var r = Java.retain(obj);
        retained.push(r);

        while (retained.length > MAX_RETAINED_DRAWABLES) {
            try {
                retained.shift().$dispose();
            } catch (ignored) {
            }
        }
        return r;
    }

    function getDockDrawable(target, slot) {
        var style = cfg("dock" + slot + "Icon");
        var resourceName = null;
        if (style === "navigation") {
            resourceName = "dock_navigation_icon";
        } else if (style === "music") {
            resourceName = "dock_music_icon";
        }
        if (resourceName !== null) {
            try {
                var resources = ctx().getPackageManager().getResourcesForApplication(RESTORE_PKG);
                var resourceId = resources.getIdentifier(resourceName, "drawable", RESTORE_PKG);
                if (resourceId !== 0) {
                    return retainDrawable(resources.getDrawable(resourceId, null));
                }
            } catch (error) {
                Log.w(TAG, "[dock] icon override unavailable: " + error);
            }
        }
        return getAppDrawable(target);
    }

    function getAppDrawable(target) {
        try {
            var packageManager = ctx().getPackageManager();
            var icon;
            if (target === CARPLAY_TARGET) {
                var resources = packageManager.getResourcesForApplication(RESTORE_PKG);
                var resourceId = resources.getIdentifier("carplay_dock_logo", "drawable", RESTORE_PKG);
                if (resourceId !== 0) {
                    // Preserve the OEM dock canvas and transparent padding.
                    return retainDrawable(resources.getDrawable(resourceId, null));
                }
                icon = packageManager.getActivityIcon(
                    Java.use("android.content.ComponentName").$new(RESTORE_PKG, CARPLAY_LAUNCHER));
            } else {
                icon = packageManager.getApplicationIcon(target);
            }

            var bitmap =
                Bitmap.createBitmap(icon.getIntrinsicWidth(), icon.getIntrinsicHeight(), BitmapConfig.ARGB_8888.value);
            var canvas = Canvas.$new(bitmap);
            icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            icon.draw(canvas);

            var scaled = Bitmap.createScaledBitmap(bitmap, 50, 50, true);
            var drawable = BitmapDrawable.$new(ctx().getResources(), scaled);
            drawable.setGravity(17);  // Gravity.CENTER
            drawable.setBounds(0, 0, 50, 50);
            return retainDrawable(drawable);
        } catch (error) {
            Log.e(TAG, "[dock] getAppDrawable err " + target + ": " + error);
            return null;
        }
    }

    function sendNative(action, extras) {
        var i = Intent.$new(action);
        i.setClassName(OUR_PKG, "ru.big.town.anative.SetModesReceiverDynamic");
        extras(i);
        i.addFlags(0x00000020);
        ctx().sendBroadcast(i);
    }

    function openVoyahTune(reason) {
        try {
            var i = Intent.$new();
            i.setClassName(RESTORE_PKG, "ru.big.town.restoremode.MainActivity");
            i.addFlags(0x10000000 | 0x04000000 | 0x20000000);  // NEW_TASK | CLEAR_TOP | SINGLE_TOP
            ctx().startActivity(i);
            Log.i(DLOG, (reason || "home tap") + " -> VoyahTune");
        } catch (e) {
            Log.e(TAG, "[dock] openVoyahTune err: " + e);
        }
    }

    function openTaskManager() {
        try {
            var i = Intent.$new();
            i.setClassName(RESTORE_PKG, RESTORE_TASK_ACT);
            i.addFlags(0x10000000 | 0x04000000 | 0x20000000);  // NEW_TASK | CLEAR_TOP | SINGLE_TOP
            ctx().startActivity(i);
            Log.i(DLOG, "menu long-press -> task manager");
        } catch (e) {
            Log.e(TAG, "[dock] openTaskManager err: " + e);
        }
    }

    function stockLauncherHome() {
        if (startLauncherHome(true))
            return;
        if (startLauncherHome(false))
            return;
        Log.e(TAG, "[dock] stock home launch failed");
    }

    function startLauncherHome(explicit) {
        try {
            var i = Intent.$new();
            i.setAction(Intent.ACTION_MAIN.value);
            i.addCategory(Intent.CATEGORY_HOME.value);
            if (explicit)
                i.setClassName(LAUNCHER_PKG, LAUNCHER_HOME_ACT);
            i.addFlags(0x10000000 | 0x04000000 | 0x20000000);  // NEW_TASK | CLEAR_TOP | SINGLE_TOP
            ctx().startActivity(i);
            Log.i(DLOG, "home long-press -> stock home (explicit=" + explicit + ")");
            return true;
        } catch (e) {
            Log.w(TAG, "[dock] stock home explicit=" + explicit + " err: " + e);
            return false;
        }
    }

    function openDockSplit(slot) {
        try {
            sendNative("ru.big.town.anative.OPEN_DOCK_SPLIT", function (i) {
                i.putExtra.overload('java.lang.String', 'int').call(i, "slot", slot);
            });
            Log.i(DLOG, "OPEN_DOCK_SPLIT sent slot=" + slot);
        } catch (e) {
            Log.e(TAG, "[dock] openDockSplit err: " + e);
        }
    }

    function launchFreeform(pkg, displayId) {
        try {
            sendNative("ru.big.town.anative.OPEN_FREEFORM", function (i) {
                i.putExtra.overload('java.lang.String', 'java.lang.String').call(i, "pkg", "" + pkg);
                i.putExtra.overload('java.lang.String', 'int').call(i, "display", displayId);
            });
            Log.i(TAG, "[dock] OPEN_FREEFORM -> " + pkg + " display=" + displayId);
        } catch (e) {
            Log.e(TAG, "[dock] launchFreeform err: " + e);
        }
    }

    // Fullscreen launch must normalize an already existing mode-5 task before resume. Native applies
    // Android 11 ActivityOptions windowingMode=FULLSCREEN and independently validates the persisted
    // allowlist, so this exported launcher bridge cannot start an arbitrary package.
    function launchFullscreen(pkg, displayId) {
        if (displayId !== 0 && displayId !== 1)
            return false;
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

    function redirectPassengerHome(intent, screenId) {
        if (screenId !== 1 || intent === null)
            return;
        var component = intent.getComponent();
        if (component === null || cleanJavaString(component.getClassName()) !== SECOND_HOME)
            return;
        intent.setClassName(LAUNCHER_PKG, SECOND_ALL_APPS);
        Log.i(DLOG, "passenger home -> AllAppActivity");
    }

    function openPassengerAllApps(reason) {
        try {
            var intent = Intent.$new();
            intent.setClassName(LAUNCHER_PKG, SECOND_ALL_APPS);
            intent.addFlags(0x10000000);  // FLAG_ACTIVITY_NEW_TASK
            var AppLauncher = Java.use("com.qinggan.launcher.base.utils.AppLauncher");
            AppLauncher.startApp.overload('android.content.Context', 'android.content.Intent', 'int')
                .call(AppLauncher, ctx(), intent, 1);
            Log.i(DLOG, "passenger AllAppActivity opened: " + reason);
        } catch (e) {
            Log.e(TAG, "[dock] passenger AllAppActivity: " + e);
        }
    }

    var menuLC = Java.registerClass({
        name: "ru.big.town.dock.MenuLongClick",
        implements: [LongClick],
        methods: {
            onLongClick: {
                returnType: "boolean",
                argumentTypes: ["android.view.View"],
                implementation: function (view) {
                    openTaskManager();
                    return true;
                }
            }
        }
    })
        .$new();

    var homeLC = Java.registerClass({
        name: "ru.big.town.dock.HomeLongClick",
        implements: [LongClick],
        methods: {
            onLongClick: {
                returnType: "boolean",
                argumentTypes: ["android.view.View"],
                implementation: function (view) {
                    stockLauncherHome();
                    return true;
                }
            }
        }
    })
        .$new();

    var slotLC =
        Java.registerClass({
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
                            Log.i(
                                DLOG, "slot long-press id=" + view.getId() + " slot=" + slot + " hasSplit=" + has);
                            if (slot === 0 || has !== "1")
                                return false;
                            openDockSplit(slot);
                            return true;
                        } catch (e) {
                            Log.e(TAG, "slot long-press err: " + e);
                            return false;
                        }
                    }
                }
            }
        })
            .$new();

    function setDockViewVisibility(view, visibility, label) {
        if (!view)
            return;
        try {
            view.setVisibility(visibility);
        } catch (e) {
            Log.e(TAG, "[dock] visibility " + label + " err: " + e);
        }
    }

    function setDockViewHeight(view, height, label) {
        if (!view)
            return;
        try {
            var lp = view.getLayoutParams();
            if (lp === null)
                return;
            lp.height.value = height;
            view.setLayoutParams(lp);
        } catch (e) {
            Log.e(TAG, "[dock] height " + label + " err: " + e);
        }
    }

    // OEM dismiss() only starts a 100-ms x=-width animation and removes the Window from its end
    // callback. Another launcher lifecycle event can end/reuse that animator before removal. Cancelling
    // it with the OEM listener still attached invokes onAnimationEnd(), so a following explicit remove
    // can remove the same root twice and destabilize Launcher. Silence/cancel the animator first, move
    // the Window off-screen synchronously, then use ordinary removeView(). Keeping the Window attached
    // would leave its navigation-bar inset active and constrain fullscreen apps to the old dock width.
    // OEM show() can safely add the root again when Home becomes foreground.
    function forceHideDockBar(bar, label) {
        if (bar === null)
            return false;
        try {
            var animator = field(bar, "mMoveWindowAnimator");
            if (animator !== null && animator.isStarted()) {
                animator.removeAllListeners();
                animator.removeAllUpdateListeners();
                animator.cancel();
            }
        } catch (e) {
            Log.w(TAG, "[dock] cancel dismiss animator " + label + ": " + e);
        }
        try {
            var root = field(bar, "mRootView");
            var lp = field(bar, "mLp");
            var windowManager = field(bar, "mWindowManager");
            if (root === null || lp === null || windowManager === null)
                return false;
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
        if (screenOf(bar) !== 0)
            return;  // passenger bar remains completely OEM-controlled
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
        Log.i(
            TAG,
            "[dock] driver lift=" + type + " mode=" +
            (compact ? "compact(home" + (compactSlot1 ? "+1" : "") + (compactSlot2 ? "+2" : "") + ")" : "normal"));
    }

    function currentScreenLiftType() {
        try {
            var type = SystemProps.getInt("persist.qg.canbus.bcm_screenAutoLiftFdb", 2);
            if (type === 1 || type === 2)
                return type;
        } catch (e) {
        }
        return parseInt(cfg("screen_lift_type"), 10) === 1 ? 1 : 2;
    }

    // OEM theme and layout callbacks run on the main thread.
    function updateIcons(bar, skipLayout) {
        try {
            if (screenOf(bar) !== 0) {
                return;
            }
            var views = dockViews(bar);
            var slots = [
                { name: "slot1", view: views.slot1, pkg: dockPackage(1, false) },
                { name: "slot2", view: views.slot2, pkg: dockPackage(2, false) }
            ];
            for (var slotIndex = 0; slotIndex < slots.length; slotIndex++) {
                var view = slots[slotIndex].view;
                if (!view) {
                    continue;
                }
                var name = slots[slotIndex].name;
                if (!originalBg[name]) {
                    var background = view.getBackground();
                    if (background !== null) {
                        originalBg[name] = Java.retain(background);
                    }
                }
                if (slots[slotIndex].pkg === "none") {
                    view.setBackground(originalBg[name]);
                } else {
                    // Icons can resolve before launch intents during cold boot.
                    var drawable = getDockDrawable(slots[slotIndex].pkg, slotIndex + 1);
                    if (drawable) {
                        view.setBackground(drawable);
                    }
                }
                view.invalidate();
                slotByViewId["" + view.getId()] = slotIndex + 1;
                view.setLongClickable(true);
                view.setOnLongClickListener(slotLC);
            }
            if (views.allApps) {
                views.allApps.setLongClickable(true);
                views.allApps.setOnLongClickListener(menuLC);
            }
            if (views.home) {
                views.home.setLongClickable(true);
                views.home.setOnLongClickListener(homeLC);
            }
            if (!skipLayout) {
                applyScreenLiftDock(bar, currentScreenLiftType());
            }
        } catch (error) {
            Log.e(TAG, "[dock] updateIcons err: " + error);
        }
    }

    function updateAllNavbars() {
        Java.scheduleOnMainThread(function () {
            try {
                var model = findModel();
                if (model === null)
                    return;
                var bar = barOf(model, 0);
                if (bar !== null)
                    updateIcons(bar);

                schedulePhysicalDockRecovery(model, "navbar pass");
            } catch (e) {
                Log.e(TAG, "[dock] updateAll err: " + e);
            }
        });
    }

    function reconcilePhysicalDock(model, displayId, reason, afterMove) {
        if (displayId !== 0 && displayId !== 1)
            return;
        var foreground = topActivityForScreen(displayId);
        var visible = desiredDockVisible(displayId, foreground.pkg, foreground.act);
        if (visible === null && afterMove === true)
            visible = moveBarSnapshot[displayId];
        if (visible !== true && visible !== false)
            return;
        if (displayId === 0)
            model.handleUpdateMainNavigationBar(foreground.pkg, foreground.act, visible);
        else
            model.handleUpdateSecondNavigationBar(foreground.pkg, foreground.act, visible);
        Log.i(
            DLOG, reason + (visible ? " restored" : " hid") + " display=" + displayId + " dock for " + foreground.pkg);
    }

    function schedulePhysicalDockRecovery(model, reason, afterMove) {
        rememberModel(model);
        setTimeout(function () {
            Java.scheduleOnMainThread(function () {
                try {

                    if (afterMove === true)
                        clearMoveDockGuards();
                    reconcilePhysicalDock(launcherModel, 0, reason, afterMove);
                    reconcilePhysicalDock(launcherModel, 1, reason, afterMove);
                } catch (e) {
                    Log.e(TAG, "[dock] delayed transfer recovery: " + e);
                }
            });
        }, 300);
    }

    var MOVE_RECOVERY_PASSES = 8;
    var MOVE_RECOVERY_STEP = 400;
    function scheduleMoveDockRecovery(model, movedPackage, sourceDisplay, cancelled) {
        if (sourceDisplay !== 0 && sourceDisplay !== 1)
            return;
        rememberModel(model);
        var destination = cancelled ? sourceDisplay : (sourceDisplay === 0 ? 1 : 0);
        var attempt = 0;
        var pass = function () {
            Java.scheduleOnMainThread(function () {
                attempt++;
                var reason = "onMoveStop#" + attempt;
                var settled = true;
                try {

                    clearMoveDockGuards();
                    settled = !movedPackage || topActivityForScreen(destination).pkg === movedPackage;
                    if (destination !== sourceDisplay) {
                        reconcilePhysicalDock(launcherModel, sourceDisplay, reason, true);
                    }
                    if (settled || attempt >= MOVE_RECOVERY_PASSES) {
                        reconcilePhysicalDock(launcherModel, destination, reason, true);
                    }
                } catch (e) {
                    Log.e(TAG, "[dock] move recovery pass: " + e);
                }
                if (!settled && attempt < MOVE_RECOVERY_PASSES)
                    setTimeout(pass, MOVE_RECOVERY_STEP);
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

                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: onReceive
                }
            }
        });
        var receiver = Java.retain(cls.$new());
        receivers.push(receiver);
        var register =
            ctx().registerReceiver.overload('android.content.BroadcastReceiver', 'android.content.IntentFilter');
        filters.forEach(function (filter) {
            register.call(ctx(), receiver, filter);
        });
    }

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

            var ApplicationInfo = Java.use("android.content.pm.ApplicationInfo");
            var ComponentName = Java.use("android.content.ComponentName");
            var pm = ctx().getPackageManager();
            var installedSnapshot = null;
            var iconCache = {};
            var labelCache = {};
            var packageRefreshTimer = null;
            var FLAG_SYSTEM = 0x00000001;
            var SYNTHETIC_PREFIX = "__voyahtune_allapps__:";
            var PASSENGER_CLASS = "ru.big.town.restoremode.passenger.PassengerDashboardActivity";
            var PASSENGER_ENTRY = RESTORE_PKG + "/" + PASSENGER_CLASS;
            var resourceTemplate = null;

            function entryPackage(target) {
                return target === PASSENGER_ENTRY ? RESTORE_PKG : target;
            }

            function entryComponent(target) {
                if (target === PASSENGER_ENTRY) {
                    return ComponentName.$new(RESTORE_PKG, PASSENGER_CLASS);
                }
                return null;
            }

            function packageFromIntent(intent) {
                if (intent === null)
                    return "";
                try {
                    var component = intent.getComponent();
                    if (component !== null)
                        return cleanJavaString(component.getPackageName());
                    var explicitPackage = cleanJavaString(intent.getPackage());
                    if (explicitPackage)
                        return explicitPackage;
                    var resolved = pm.resolveActivity(intent, 0);
                    if (resolved !== null && resolved.activityInfo.value !== null) {
                        return cleanJavaString(resolved.activityInfo.value.packageName.value);
                    }
                } catch (ignored) {
                }
                return "";
            }

            var startAppIntent =
                AppLauncher.startApp.overload('android.content.Context', 'android.content.Intent', 'int');
            startAppIntent.implementation = function (context, intent, screenIdArg) {
                redirectPassengerHome(intent, Number(screenIdArg));
                var pkg = packageFromIntent(intent);
                if (isUserFullscreen(pkg) && launchFullscreen(pkg, Number(screenIdArg)))
                    return;
                return startAppIntent.call(this, context, intent, screenIdArg);
            };
            var startAppComponent =
                AppLauncher.startApp.overload('android.content.Context', 'java.lang.String', 'java.lang.String', 'int');
            startAppComponent.implementation = function (context, pkgArg, classArg, screenIdArg) {
                var pkg = cleanJavaString(pkgArg);
                if (isUserFullscreen(pkg) && launchFullscreen(pkg, Number(screenIdArg)))
                    return;
                return startAppComponent.call(this, context, pkgArg, classArg, screenIdArg);
            };

            function launchAllApp(target, screenId) {
                try {
                    var component = entryComponent(target);
                    if (component === null && isUserFullscreen(target)) {
                        return launchFullscreen(target, screenId);
                    }
                    var intent = component !== null
                        ? Intent.$new("android.intent.action.MAIN")
                            .addCategory("android.intent.category.LAUNCHER")
                            .setComponent(component)
                        : pm.getLaunchIntentForPackage(target);
                    if (intent === null) return false;
                    intent.addFlags(0x10000000); // FLAG_ACTIVITY_NEW_TASK
                    AppLauncher.startApp(ctx(), intent, screenId);
                    Log.i(TAG, "[allapps] launch " + target + " display=" + screenId);
                    return true;
                } catch (e) {
                    Log.e(TAG, "[allapps] launch " + target + ": " + e);
                    return false;
                }
            }

            function snapshotInstalled() {
                if (installedSnapshot !== null)
                    return installedSnapshot;
                var result = [];
                var installed = pm.getInstalledApplications(0);
                for (var i = 0; i < installed.size(); i++) {
                    try {
                        var ai = Java.cast(installed.get(i), ApplicationInfo);
                        var pkg = "" + ai.packageName.value;
                        if ((Number(ai.flags.value) & FLAG_SYSTEM) !== 0 || pkg === LAUNCHER_PKG)
                            continue;
                        if (pm.getLaunchIntentForPackage(pkg) === null)
                            continue;
                        result.push(pkg);
                    } catch (ignored) {
                    }
                }
                installedSnapshot = result;
                Log.i(TAG, "[allapps] cached launchable user apps=" + result.length);
                return installedSnapshot;
            }

            function findAppTemplate(list) {
                if (list === null)
                    return resourceTemplate;
                for (var i = 0; i < list.size(); i++) {
                    try {
                        var bean = Java.cast(list.get(i), AppBean);
                        if (Number(bean.getType()) === 1 && Number(bean.getIcon()) > 0 &&
                            Number(bean.getNameRes()) > 0) {
                            resourceTemplate = { icon: Number(bean.getIcon()), name: Number(bean.getNameRes()) };
                            return resourceTemplate;
                        }
                    } catch (ignored) {
                    }
                }
                return resourceTemplate;
            }

            var addingApps = false;

            function addMissingApps(list, screenId) {
                if (list === null || addingApps) return;
                addingApps = true;
                try { addMissingAppsImpl(list, screenId); }
                finally { addingApps = false; }
            }

            function addMissingAppsImpl(list, screenId) {
                // Driver keeps the normal VoyahTune entry; the passenger list gets the dedicated
                // activity instead, otherwise its stock icon would move the driver task to display 1.
                var screen = screenId === 1 ? 1 : 0;
                var existing = {};
                for (var i = list.size() - 1; i >= 0; i--) {
                    try {
                        var current = Java.cast(list.get(i), AppBean);
                        var existingTarget = syntheticPackage(current) || "" + current.getPackageName();
                        if ((existingTarget === PASSENGER_ENTRY && screen !== 1)
                            || (screen === 1 && existingTarget === RESTORE_PKG)) {
                            list.remove(i);
                            continue;
                        }
                        existing["pkg:" + existingTarget] = true;
                    } catch (ignored) { }
                }
                var apps = snapshotInstalled();
                var template = null;
                for (var j = 0; j < apps.length; j++) {
                    var target = apps[j];
                    if (screen === 1 && target === RESTORE_PKG) continue;
                    if (existing["pkg:" + target]) continue;
                    try {
                        if (template === null)
                            template = findAppTemplate(list);

                        if (template === null)
                            template = findAppTemplate(getAll.call(Data, 0));
                        if (template === null) {
                            Log.e(TAG, "[allapps] no valid OEM app template; cannot safely add " + target);
                            return;
                        }
                        var bean = AppBean.$new(template.icon, template.name, target);
                        bean.setSubType(SYNTHETIC_PREFIX + target);
                        list.add(bean);
                        existing["pkg:" + target] = true;
                    } catch (e) { Log.e(TAG, "[allapps] add " + target + ": " + e); }
                }
                if (screen === 1 && !existing["pkg:" + PASSENGER_ENTRY]) {
                    try {
                        if (template === null) template = findAppTemplate(list);
                        if (template === null) template = findAppTemplate(getAll.call(Data, 0));
                        if (template === null) {
                            Log.e(TAG, "[allapps] no template for passenger dashboard entry");
                            return;
                        }
                        var passengerBean = AppBean.$new(template.icon, template.name, RESTORE_PKG);
                        passengerBean.setSubType(SYNTHETIC_PREFIX + PASSENGER_ENTRY);
                        list.add(passengerBean);
                    } catch (e) { Log.e(TAG, "[allapps] add passenger dashboard: " + e); }
                }
            }

            function syntheticPackage(bean) {
                if (bean === null)
                    return null;
                try {

                    var subType = "" + bean.getSubType();
                    if (subType.lastIndexOf(SYNTHETIC_PREFIX, 0) !== 0)
                        return null;
                    return subType.substring(SYNTHETIC_PREFIX.length) || null;
                } catch (ignored) {
                    return null;
                }
            }

            function loadIcon(target) {
                var icon = iconCache[target];
                if (!icon) {
                    var component = entryComponent(target);
                    icon = Java.retain(component !== null
                        ? pm.getActivityIcon(component)
                        : pm.getApplicationIcon(target));
                    iconCache[target] = icon;
                }
                return icon;
            }

            function loadLabel(target) {
                var cached = labelCache[target];
                if (cached) return cached;
                var component = entryComponent(target);
                var value = Java.retain(component !== null
                    ? JavaString.$new("" + pm.getActivityInfo(component, 0).loadLabel(pm))
                    : JavaString.$new("" + pm.getApplicationLabel(pm.getApplicationInfo(target, 0))));
                labelCache[target] = value;
                return value;
            }

            function dropCache(store, packageName) {
                var keys = packageName
                    ? Object.keys(store).filter(function (key) {
                        return entryPackage(key) === packageName;
                    })
                    : Object.keys(store);
                for (var i = 0; i < keys.length; i++) {
                    var cached = store[keys[i]];
                    if (cached) {
                        try {
                            cached.$dispose();
                        } catch (ignored) {
                        }
                    }
                    delete store[keys[i]];
                }
            }

            var holderCache = {};
            var holderCacheSize = 0;
            var adapterLayouts = {};
            var HOLDER_CACHE_LIMIT = 256;

            function holderEntry(adapter, holder) {
                var key = holder.hashCode();
                var entry = holderCache[key];
                if (entry !== undefined)
                    return entry;
                if (holderCacheSize >= HOLDER_CACHE_LIMIT)
                    clearBindCaches();
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

            function adapterLayout(adapter) {
                var key = adapter.hashCode();
                var layout = adapterLayouts[key];
                if (layout !== undefined)
                    return layout;
                layout = describeLayout(field(adapter, "mAppBeans"));
                adapterLayouts[key] = layout;
                Log.i(
                    TAG,
                    "[allapps] bind layout adapter=" + key + " " +
                    (layout === null ? "unverified; exact path" :
                        "start=" + layout.start + " synthetic=" + layout.pkgs.length));
                return layout;
            }

            function clearBindCaches() {
                var keys = Object.keys(holderCache);
                for (var i = 0; i < keys.length; i++) {
                    var entry = holderCache[keys[i]];
                    try {
                        entry.holder.$dispose();
                    } catch (ignored) {
                    }
                    try {
                        if (entry.icon)
                            entry.icon.$dispose();
                    } catch (ignored) {
                    }
                    try {
                        if (entry.name)
                            entry.name.$dispose();
                    } catch (ignored) {
                    }
                }
                holderCache = {};
                holderCacheSize = 0;
                adapterLayouts = {};
            }

            function describeLayout(list) {
                if (list === null)
                    return null;
                var size = list.size();
                var start = -1;
                var pkgs = [];
                for (var i = 0; i < size; i++) {
                    var pkg = null;
                    try {
                        pkg = syntheticPackage(Java.cast(list.get(i), AppBean));
                    } catch (ignored) {
                        return null;
                    }
                    if (pkg === null) {
                        if (start >= 0)
                            return null;
                        continue;
                    }
                    if (start < 0)
                        start = i;
                    pkgs.push(pkg);
                }
                return start < 0 ? { start: size, pkgs: [] } : { start: start, pkgs: pkgs };
            }

            function beanAt(adapter, position) {
                var beans = field(adapter, "mAppBeans");
                if (beans === null || position < 0 || position >= beans.size())
                    return null;
                return Java.cast(beans.get(position), AppBean);
            }

            function finishBoundItem(adapter, holder, position) {
                var entry = holderEntry(adapter, holder);
                var layout = entry.layout;
                var pkg;
                if (layout !== null) {
                    if (position < layout.start)
                        return;
                    pkg = layout.pkgs[position - layout.start];
                    if (!pkg)
                        return;
                } else {
                    pkg = syntheticPackage(beanAt(adapter, position));
                    if (pkg === null)
                        return;
                }

                if (entry.setBackground !== null)
                    entry.setBackground.call(entry.icon, loadIcon(pkg));
                if (entry.setText !== null)
                    entry.setText.call(entry.name, loadLabel(pkg));
            }

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
                            try {
                                this.dismiss();
                            } catch (ignored) {
                            }
                        }
                        return;
                    }
                } catch (e) {
                    Log.e(TAG, "[allapps] owner click: " + e);
                }
                return allAppClick.call(this, view);
            };

            var bindPayload = Adapter.onBindViewHolder.overload(
                'com.qinggan.launcher.base.adapter.AllAppAdapter$AppViewHolder', 'int', 'java.util.List');
            bindPayload.implementation = function (holder, position, payloads) {
                bindPayload.call(this, holder, position, payloads);
                try {
                    finishBoundItem(this, holder, position);
                } catch (e) {
                    Log.e(TAG, "[allapps] payload bind: " + e);
                }
            };

            var secondBind = SecondAdapter.onBindViewHolder.overload(
                'com.qinggan.secondlauncher.adapter.SecondAllAppAdapter$ViewHolder', 'int');
            secondBind.implementation = function (holder, position) {
                secondBind.call(this, holder, position);
                try {
                    var list = field(this, "allAppList");
                    if (list === null || position < 0 || position >= list.size())
                        return;
                    var pkg = syntheticPackage(Java.cast(list.get(position), AppBean));
                    if (pkg === null)
                        return;
                    var iconView = field(holder, "iconView");
                    var nameView = field(holder, "nameView");
                    if (iconView !== null)
                        iconView.setImageDrawable(loadIcon(pkg));
                    if (nameView !== null)
                        nameView.setText(loadLabel(pkg));
                } catch (e) {
                    Log.e(TAG, "[allapps] passenger rail bind: " + e);
                }
            };
            var secondClick = SecondFragment.onItemClick.overload('com.qinggan.launcher.base.bean.AppBean');
            secondClick.implementation = function (bean) {
                try {
                    var pkg = syntheticPackage(bean);
                    if (pkg !== null) {
                        launchAllApp(pkg, 1);
                        return;
                    }
                } catch (e) {
                    Log.e(TAG, "[allapps] passenger rail click: " + e);
                }
                return secondClick.call(this, bean);
            };

            var getAll = Data.getAllApps.overload('int');
            getAll.implementation = function (screenId) {
                var list = getAll.call(this, screenId);
                if ((screenId === 0 || screenId === 1) && list !== null) {
                    addMissingApps(list, screenId);
                    clearBindCaches();
                }
                return list;
            };

            var dataSingleton = null;

            function injectAllScreens() {
                try {
                    if (dataSingleton === null) dataSingleton = Java.retain(Data.getInstance());
                    var fields = [
                        { name: "mMainAllApps", screen: 0 },
                        { name: "mSecondAllApps", screen: 1 }
                    ];
                    var total = 0;
                    for (var i = 0; i < fields.length; i++) {
                        var list = field(dataSingleton, fields[i].name);
                        if (list === null) continue;
                        var before = list.size();
                        addMissingApps(list, fields[i].screen);
                        var added = list.size() - before;
                        if (added > 0) {
                            Log.i(TAG, "[allapps] injectAllScreens " + fields[i].name + " += " + added);
                            total += added;
                        }
                    }
                    clearBindCaches();
                    if (total > 0)
                        refreshAllAppBars();
                } catch (e) {
                    Log.e(TAG, "[allapps] inject failed: " + e);
                }
            }

            var REFRESH_MAX_ATTEMPTS = 12;
            var REFRESH_RETRY_MS = 200;
            var REFRESH_INITIAL_DELAY_MS = 300;

            function refreshAllAppBars() {
                setTimeout(function () {
                    refreshAttempt(0);
                }, REFRESH_INITIAL_DELAY_MS);
            }

            function refreshAttempt(attempt) {
                Java.scheduleOnMainThread(function () {
                    var busy = false;
                    try {
                        var bars = [];
                        Java.choose("com.qinggan.launcher.base.allapp.AllAppBarView", {
                            onMatch: function (bar) {
                                bars.push(bar);
                            },
                            onComplete: function () { }
                        });
                        var refreshed = 0;
                        for (var i = 0; i < bars.length; i++) {
                            var adapter = field(bars[i], "mAllAppAdapter");
                            var lm = field(bars[i], "mLayoutManager");
                            if (adapter === null || lm === null)
                                continue;
                            var frames = field(lm, "mItemFrames");
                            if (frames !== null)
                                frames.clear();

                            adapter.notifyDataSetChanged();
                            var rv = field(lm, "mRecyclerView");
                            if (rv !== null)
                                rv.requestLayout();
                            refreshed++;
                        }

                        Java.choose("com.qinggan.secondlauncher.adapter.SecondAllAppAdapter", {
                            onMatch: function (adapter) {
                                adapter.notifyDataSetChanged();
                                refreshed++;
                            },
                            onComplete: function () { }
                        });
                        clearBindCaches();
                        if (refreshed > 0)
                            Log.i(TAG, "[allapps] grid refreshed views=" + refreshed);
                    } catch (e) {
                        if (/computing a layout|scrolling/.test("" + e))
                            busy = true;
                        else {
                            Log.e(TAG, "[allapps] grid refresh failed: " + e);
                            return;
                        }
                    }
                    if (busy && attempt + 1 < REFRESH_MAX_ATTEMPTS) {
                        setTimeout(function () {
                            refreshAttempt(attempt + 1);
                        }, REFRESH_RETRY_MS);
                    } else if (busy) {
                        Log.e(TAG, "[allapps] grid stayed busy, refresh skipped");
                    }
                });
            }

            function hookAppReload(cls) {
                var onAppReload = cls.onAppReload.overload();
                onAppReload.implementation = function () {
                    injectAllScreens();
                    return onAppReload.call(this);
                };
            }
            hookAppReload(AllAppBarView);
            hookAppReload(SecondFragment);

            function schedulePackageRefresh(action, packageName) {
                installedSnapshot = null;
                dropCache(iconCache, packageName);
                dropCache(labelCache, packageName);
                clearBindCaches();
                if (packageRefreshTimer !== null)
                    clearTimeout(packageRefreshTimer);
                packageRefreshTimer = setTimeout(function () {
                    packageRefreshTimer = null;
                    Java.scheduleOnMainThread(function () {
                        try {

                            Data.reload();
                            injectAllScreens();
                            Log.i(TAG, "[allapps] package refresh action=" + action + " package=" + packageName);
                        } catch (e) {
                            Log.e(TAG, "[allapps] package refresh failed: " + e);
                        }
                    });
                }, 300);
            }

            var packageFilter = IntentFilter.$new();
            packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
            packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
            packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
            packageFilter.addDataScheme("package");
            var localeFilter = IntentFilter.$new("android.intent.action.LOCALE_CHANGED");
            registerReceiver(
                "ru.big.town.dock.AllAppsPackageReceiver", [packageFilter, localeFilter], function (context, intent) {
                    try {
                        var action = "" + intent.getAction();

                        if (action === "android.intent.action.LOCALE_CHANGED") {
                            dropCache(labelCache, null);
                            Log.i(TAG, "[allapps] locale changed; label cache dropped");
                            return;
                        }
                        var data = intent.getData();
                        schedulePackageRefresh(action, data !== null ? "" + data.getSchemeSpecificPart() : "");
                    } catch (e) {
                        Log.e(TAG, "[allapps] package receiver: " + e);
                    }
                });

            injectAllScreens();
            Log.i(TAG, "[allapps] hooks installed");
        } catch (e) {

            Log.e(TAG, "[allapps] hooks unavailable: " + e);
        }
    }

    var NavigationBar = Java.use(NAV_BAR);
    var LauncherModel = Java.use(MODEL);

    var secondResume = Java.use(SECOND_HOME).onResume.overload();
    secondResume.implementation = function () {
        secondResume.call(this);
        try {
            if (this.getWindowManager().getDefaultDisplay().getDisplayId() === 1) {
                openPassengerAllApps("SecondMainActivity resumed");
            }
        } catch (e) {
            Log.e(TAG, "[dock] passenger home resume: " + e);
        }
    };

    var origUpdateTheme = NavigationBar.updateTheme.overload();
    origUpdateTheme.implementation = function () {
        origUpdateTheme.call(this);
        updateIcons(this);
    };

    var origInitUp = NavigationBar.initScreenUpViews.overload();
    origInitUp.implementation = function () {
        origInitUp.call(this);
        updateIcons(this);
    };

    var origScreenLift = NavigationBar.doScreenLift.overload('int');
    origScreenLift.implementation = function (type) {
        origScreenLift.call(this, type);
        try {
            if (screenOf(this) !== 0)
                return;
            if (isUserFullscreen(topActivityForScreen(0).pkg)) {
                forceHideDockBar(this, "screen-lift driver");
                return;
            }
            updateIcons(this, true);
            applyScreenLiftDock(this, type);
        } catch (e) {
            Log.e(TAG, "[dock] screen-lift layout err: " + e);
        }
    };

    var origUpdateSelectedApp = NavigationBar.updateSelectedApp.overload('java.lang.String', 'java.lang.String');
    origUpdateSelectedApp.implementation = function (packageName, activityName) {
        var sid = screenOf(this);

        if (sid >= 0) {
            fgByScreen[sid].pkg = cleanJavaString(packageName);
            fgByScreen[sid].act = cleanJavaString(activityName);
        }
        if (sid !== 0)
            return origUpdateSelectedApp.call(this, packageName, activityName);
        try {
            if (packageName === OUR_PKG && ("" + activityName).indexOf("SplitHostActivity") >= 0) {
                var v = lastSlot === 1 ? field(this, "mScreenUpItemView1") :
                    lastSlot === 2 ? field(this, "mScreenUpItemView2") :
                        null;
                if (v) {
                    v.setChecked(true);
                    return;
                }
            }
            var p1 = dockPackage(1, false);
            var p2 = dockPackage(2, false);
            if (p1 !== "none" && packageName === p1)
                packageName = STOCK_SLOT_PKG[1];
            else if (p2 !== "none" && packageName === p2)
                packageName = STOCK_SLOT_PKG[2];
        } catch (e) {
        }
        return origUpdateSelectedApp.call(this, packageName, activityName);
    };

    var mainOnClick = NavigationBar.onClick.overload('android.view.View');
    mainOnClick.implementation = function (view) {
        if (screenOf(this) !== 0)
            return mainOnClick.call(this, view);
        try {
            var viewId = view.getId();

            var homeView = field(this, "mScreenUpHomeView");
            if (homeView !== null && ((viewId !== -1 && viewId === homeView.getId()) || view.equals(homeView))) {
                openVoyahTune("home tap");
                return;
            }
            for (var slot = 1; slot <= 2; slot++) {
                var slotView = field(this, "mScreenUpItemView" + slot);
                if (slotView === null || viewId !== slotView.getId())
                    continue;
                var pkg = dockPackage(slot, true);
                if (isInstalled(pkg)) {
                    lastSlot = slot;
                    launchFreeform(pkg, 0);
                    return;
                }
            }
        } catch (e) {
            Log.e(TAG, "[dock] onClick err: " + e);
        }
        return mainOnClick.call(this, view);
    };

    var origDismiss = NavigationBar.dismiss.overload();
    origDismiss.implementation = function () {
        var sid = screenOf(this);
        if (sid < 0)
            return origDismiss.call(this);
        try {
            var fg = topActivityForScreen(sid);
            var moving = activeMoveDockGuard();
            var pending = pendingDockLaunch(sid);

            Log.i(
                DLOG,
                "dismiss ENTER screen=" + sid + " fg=" + fg.pkg + " act=" + fg.act +
                (moving ? " moving=" + moving.pkg + "/" + Math.ceil(moving.remaining) + "ms" : "") +
                (pending ? " pending=" + pending.pkg + "/" + Math.ceil(pending.remaining) + "ms" : ""));
            // Fullscreen policy wins over stale transfer/launch guards: hide and detach the root now.
            if (isUserFullscreen(fg.pkg)) {
                if (forceHideDockBar(this, "dismiss fullscreen display=" + sid))
                    return;
                return origDismiss.call(this);
            }
            var blocked = moving !== null ? "active transfer " + moving.pkg :
                pending !== null ? "pending launch " + pending.pkg :
                    dockKept(fg.pkg, fg.act) ? "kept" :
                        null;
            if (blocked !== null) {
                Log.i(DLOG, "dismiss BLOCKED screen=" + sid + " " + blocked);
                return;
            }
        } catch (e) {
            Log.e(TAG, "[dock] dismiss hook err: " + e);
        }
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
            if (!isUserFullscreen(decisionPkg))
                return original.call(this, pkgArg, actArg, visible);
            fgByScreen[displayId].pkg = decisionPkg;
            if (!foreground.live)
                fgByScreen[displayId].act = cleanJavaString(actArg);

            if (forceHideDockBar(barOf(this, displayId), "model gate " + methodName + " display=" + displayId)) {
                Log.i(
                    DLOG,
                    "model gate forced hidden display=" + displayId + " pkg=" + decisionPkg +
                    " requestedVisible=" + visible);
                return;
            }
            return original.call(this, pkgArg, actArg, false);
        };
    }
    installFullscreenVisibilityGate("handleUpdateMainNavigationBar", 0);
    installFullscreenVisibilityGate("handleUpdateSecondNavigationBar", 1);

    var topReceive = LauncherModel.onReceive.overload('android.content.Context', 'android.content.Intent');
    topReceive.implementation = function (context, intent) {
        var result = topReceive.call(this, context, intent);
        try {
            if (intent !== null && ("" + intent.getAction()) === "android.intent.action.TOP_ACTIVITY_CHANGED") {
                rememberModel(this);
                reconcilePhysicalDock(this, intent.getIntExtra("displayId", -1), "TOP_ACTIVITY_CHANGED");
            }
        } catch (e) {
            Log.e(TAG, "[dock] TOP_ACTIVITY_CHANGED recovery: " + e);
        }
        return result;
    };

    var origMoveStart =
        LauncherModel.onMoveStart.overload('java.lang.String', 'java.lang.String', 'int', 'int', 'int', 'int');
    origMoveStart.implementation = function (pkg, act, type, sourceDisplay, posX, extra) {
        try {
            Log.i(
                DLOG,
                "onMoveStart(" + pkg + ", " + act + ", " + type + ", " + sourceDisplay + ", " + posX + ", " + extra +
                ")");
            if (type === 1 && (sourceDisplay === 0 || sourceDisplay === 1)) {
                var now = Number(SystemClock.elapsedRealtime());

                if (moveDockGuards[sourceDisplay].deadline <= now)
                    snapshotMoveDockState(this);
                moveDockGuards[sourceDisplay] = { deadline: now + 5000, pkg: cleanJavaString(pkg) };
                Log.i(DLOG, "move guard START source=" + sourceDisplay + " pkg=" + pkg);
            }
        } catch (e) {
        }
        return origMoveStart.call(this, pkg, act, type, sourceDisplay, posX, extra);
    };
    var origMoveStop =
        LauncherModel.onMoveStop.overload('java.lang.String', 'java.lang.String', 'int', 'int', 'int', 'int');
    origMoveStop.implementation = function (pkg, act, type, sourceDisplay, posX, extra) {
        Log.i(
            DLOG,
            "onMoveStop(" + pkg + ", " + act + ", " + type + ", " + sourceDisplay + ", " + posX + ", " + extra + ")");
        var result = origMoveStop.call(this, pkg, act, type, sourceDisplay, posX, extra);
        try {
            if (type === 1 && (sourceDisplay === 0 || sourceDisplay === 1)) {
                var stopPackage = cleanJavaString(pkg);
                var guard = moveDockGuards[sourceDisplay];
                if (guard.pkg === stopPackage && guard.deadline > 0) {

                    guard.deadline = Math.min(guard.deadline, Number(SystemClock.elapsedRealtime()) + 750);
                    Log.i(DLOG, "move guard STOP source=" + sourceDisplay + " pkg=" + guard.pkg);
                }

                scheduleMoveDockRecovery(this, stopPackage, sourceDisplay, posX === 0);
            }
        } catch (e) {
            Log.e(TAG, "[dock] onMoveStop recovery: " + e);
        }
        return result;
    };

    registerReceiver("ru.big.town.dock.DockReloadReceiver", [IntentFilter.$new(RELOAD_ACT)], function (context, intent) {
        Log.i(DLOG, "onReceive DOCK_RELOAD");
        try {
            refreshCache();
            setTimeout(updateAllNavbars, 300);
        } catch (e) {
            Log.e(TAG, "[dock] onReceive err: " + e);
        }
    });

    refreshCache();
    installAllAppsHooks();
    if (topActivityForScreen(1).act.indexOf("SecondMainActivity") >= 0)
        openPassengerAllApps("inject");
    [0, 800, 2500, 5000, 8000, 12000, 15000].forEach(function (delay) {
        setTimeout(updateAllNavbars, delay);
    });

    rpc.exports.dispose = function () {
        Java.performNow(function () {
            receivers.forEach(function (r) {
                try {
                    ctx().unregisterReceiver(r);
                } catch (e) {
                }
            });
            try {
                var bar = barOf(launcherModel, 0);
                if (bar !== null) {
                    var views = dockViews(bar);
                    [views.slot1, views.slot2, views.allApps].forEach(function (v) {
                        if (v)
                            v.setOnLongClickListener(null);
                    });
                    if (views.slot1 && originalBg.slot1)
                        views.slot1.setBackground(originalBg.slot1);
                    if (views.slot2 && originalBg.slot2)
                        views.slot2.setBackground(originalBg.slot2);
                }
            } catch (e) {
            }
            JavaSystem.clearProperty(AGENT_MARK);
        });
        Log.i(TAG, "[dock] agent disposed");
    };

    Log.i(TAG, "[dock] PI NavigationBar/LauncherModel hooks installed");
    return true;
};
