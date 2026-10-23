(function () {
  Java.perform(function () {
    "use strict";

    var application = Java.use("android.app.ActivityThread").currentApplication();
    for (var attempt = 0; application === null && attempt < 30; attempt++) {
      Java.use("java.lang.Thread").sleep(100);
      application = Java.use("android.app.ActivityThread").currentApplication();
    }
    if (application === null) {
      console.log("[navigation-source] Application is not ready");
      return;
    }
    var packageName = String(application.getPackageName());
    if (packageName === "ru.yandex.yandexnavi" || packageName === "ru.dublgis.dgismobile") {
      installNavigationSource(application);
      return;
    }

    installClusterNavigation();

    var TAG = "vt_clusternavi";
    var GUARD = "open_voyah.clusternavi.version";
    var VERSION = "v9";

    // syncReadyState() understands "qg.car.cluster.NAVI", notifyReadyToShow() does not:
    // its category table only holds NAVI_FULL / NAVI_SMALL / NAVI_AR / NVS / FAVORITE /
    // MEDIA_SMALL / CONTACTS / REST / CAMP. Passing plain NAVI there builds a packet with
    // command byte 0x00, which the instrument MCU silently drops.
    var NAVI = "qg.car.cluster.NAVI";
    var NAVI_FULL = "qg.car.cluster.NAVI_FULL";
    var MEDIA_SMALL = "qg.car.cluster.MEDIA_SMALL";

    var ACTION = "ru.big.town.anative.CLUSTER_NAVI";
    var SENDER_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    var KEY_ENABLED = "voyahtune_cluster_navi";
    var KEY_COMPONENT = "voyahtune_cluster_navi_component";
    var KEY_TOKEN = "voyahtune_cluster_navi_token";
    var KEY_THEME = "voyahtune_cluster_navi_theme";
    var DEFAULT_COMPONENT = "ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity";

    var SERVICE_CLASS = "com.qinggan.cluster.service.InstrumentClusterService";
    var NAVI_FRAGMENT_CLASS = "com.qinggan.cluster.display.fragments.ClusterNaviFragment";
    var PROTOCOL_CLASS = "com.qinggan.cluster.service.protocol.voyah.VoyahInstrumentCluster";
    var PRESENTATION_CALLBACK_ID = 0;
    var RECEIVER_CLASS = "ru.big.town.cluster.NaviReceiver";
    var CLUSTER_STATE_CLASS = "com.qinggan.cluster.ClusterState";

    // The chrome the MCU draws around the map — the side panels with the car, the
    // speed and the mode — has a day skin and a night skin, and it is the navigation
    // app that picks which one. baidu's naviauto did exactly one call for it:
    //
    //     setClusterState(ClusterState.NAVI_SHOW_THEM, isDay ? 1 : 2)
    //
    // from its own day/night switch. That lands in byte 0x10 of the 0x6D settings
    // frame. Nothing else on the system ever writes that byte and it is not persisted
    // anywhere, so once the package was removed the MCU kept its power-on default and
    // the panel stayed in the white day skin no matter what the map itself looked
    // like. Sending the byte ourselves is the whole fix.
    var THEME_DAY = 1;
    var THEME_NIGHT = 2;
    var DEFAULT_THEME = "night";
    var UI_MODE_NIGHT_MASK = 0x30;
    var UI_MODE_NIGHT_YES = 0x20;

    var FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    var FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;

    // The MCU crops ~25% off each side of the 1920x720 navi display, so the
    // navigator is launched as a freeform window over the visible middle part.
    var WINDOWING_MODE_FREEFORM = 5;
    var VISIBLE_LEFT = 480;
    var VISIBLE_RIGHT = 1440;

    // The MCU re-asks within ~50ms of every answer, so the answer path needs a
    // synchronous guard; without it each request queues another launch.
    var ANSWER_COOLDOWN_MS = 5000;
    // Startup restore, the installer's broadcast and an MCU request can land within
    // a second of each other; without this every one of them force-stops and
    // relaunches the navigator again.
    var SHOW_DEDUPE_MS = 3000;
    var lastAnswerAt = 0;
    var lastShowAt = 0;
    var launchedDisplayId = -1;

    var Log = Java.use("android.util.Log");
    var System = Java.use("java.lang.System");
    var ActivityThread = Java.use("android.app.ActivityThread");
    var ArrayMap = Java.use("android.util.ArrayMap");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");

    function log(message) {
      try { Log.i(TAG, "" + message); } catch (e) {}
      try { console.log("[clusternavi] " + message); } catch (e) {}
    }

    function warn(message) {
      try { Log.w(TAG, "" + message); } catch (e) {}
      try { console.log("[clusternavi] " + message); } catch (e) {}
    }

    function brief(value, limit) {
      var text;
      try { text = value === null ? "<null>" : "" + value; } catch (e) { text = "<unprintable>"; }
      return text.length <= limit ? text : text.substring(0, limit) + "…";
    }

    function appContext() {
      var app = ActivityThread.currentApplication();
      if (app !== null) {
        var context = app.getApplicationContext();
        if (context !== null) return context;
      }
      return ActivityThread.currentActivityThread().getSystemContext();
    }

    function resolver() {
      return appContext().getContentResolver();
    }

    function isEnabled() {
      try {
        var value = SettingsGlobal.getString(resolver(), KEY_ENABLED);
        return value !== null && parseInt("" + value, 10) === 1;
      } catch (e) {
        warn("isEnabled: " + brief(e, 180));
        return false;
      }
    }

    function setEnabled(enabled) {
      try {
        SettingsGlobal.putString(resolver(), KEY_ENABLED, enabled ? "1" : "0");
      } catch (e) {
        warn("setEnabled: " + brief(e, 180));
      }
    }

    function installToken() {
      try {
        var value = SettingsGlobal.getString(resolver(), KEY_TOKEN);
        if (value !== null) return "" + value;
      } catch (e) {}
      return "0";
    }

    function theme() {
      try {
        var value = SettingsGlobal.getString(resolver(), KEY_THEME);
        if (value !== null) {
          var text = ("" + value).trim().toLowerCase();
          if (text === "day" || text === "night" || text === "auto") return text;
        }
      } catch (e) {}
      return DEFAULT_THEME;
    }

    function setTheme(value) {
      try {
        SettingsGlobal.putString(resolver(), KEY_THEME, value);
      } catch (e) {
        warn("setTheme: " + brief(e, 180));
      }
    }

    // "auto" reproduces what the removed navigator did: it followed the map's own
    // day/night state, which the framework computes from sunrise and sunset and
    // publishes as the process configuration's night mode.
    function resolveTheme() {
      var mode = theme();
      if (mode === "day") return THEME_DAY;
      if (mode === "night") return THEME_NIGHT;
      try {
        var uiMode = appContext().getResources().getConfiguration().uiMode.value;
        return (uiMode & UI_MODE_NIGHT_MASK) === UI_MODE_NIGHT_YES ? THEME_NIGHT : THEME_DAY;
      } catch (e) {
        warn("resolveTheme: " + brief(e, 180));
        return THEME_NIGHT;
      }
    }

    function component() {
      try {
        var value = SettingsGlobal.getString(resolver(), KEY_COMPONENT);
        if (value !== null && ("" + value).indexOf("/") > 0) return "" + value;
      } catch (e) {}
      return DEFAULT_COMPONENT;
    }

    function setComponent(value) {
      try {
        SettingsGlobal.putString(resolver(), KEY_COMPONENT, value);
      } catch (e) {
        warn("setComponent: " + brief(e, 180));
      }
    }

    // The cluster app recreates its virtual displays on every restart, so the navi
    // display id is never stable. ClusterNaviFragment keeps the current one in a
    // static field; that field is the only reliable source.
    function naviDisplayId() {
      try {
        var virtualDisplay = Java.use(NAVI_FRAGMENT_CLASS).mVirtualDisplay.value;
        if (virtualDisplay === null) return -1;
        var display = virtualDisplay.getDisplay();
        if (display === null) return -1;
        return display.getDisplayId();
      } catch (e) {
        warn("naviDisplayId: " + brief(e, 180));
        return -1;
      }
    }

    // Java.choose() walks the ART heap and segfaults this 32-bit process, so the
    // service instance is taken from ActivityThread's own bookkeeping instead.
    function clusterService() {
      try {
        var thread = ActivityThread.currentActivityThread();
        var field = ActivityThread.class.getDeclaredField("mServices");
        field.setAccessible(true);
        var services = Java.cast(field.get(thread), ArrayMap);
        var count = services.size();
        for (var i = 0; i < count; i++) {
          var service = services.valueAt(i);
          if (("" + service.$className).indexOf("InstrumentClusterService") >= 0) {
            return Java.cast(service, Java.use(SERVICE_CLASS));
          }
        }
      } catch (e) {
        warn("clusterService: " + brief(e, 180));
      }
      return null;
    }

    function isNavigatorRunning() {
      try {
        var packageName = component().split("/")[0];
        var ActivityManager = Java.use("android.app.ActivityManager");
        var manager = Java.cast(appContext().getSystemService("activity"), ActivityManager);
        var processes = manager.getRunningAppProcesses();
        if (processes === null) return false;
        var Info = Java.use("android.app.ActivityManager$RunningAppProcessInfo");
        for (var i = 0; i < processes.size(); i++) {
          var info = Java.cast(processes.get(i), Info);
          if (("" + info.processName.value) === packageName) return true;
        }
      } catch (e) {
        warn("isNavigatorRunning: " + brief(e, 180));
      }
      return false;
    }

    function forceStop(packageName) {
      try {
        var ActivityManager = Java.use("android.app.ActivityManager");
        var manager = Java.cast(appContext().getSystemService("activity"), ActivityManager);
        manager.forceStopPackage(packageName);
        return true;
      } catch (e) {
        warn("forceStop " + packageName + ": " + brief(e, 180));
        return false;
      }
    }

    function launchNavigator(displayId, restart) {
      var flat = component();
      if (restart) forceStop(flat.split("/")[0]);
      try {
        var ComponentName = Java.use("android.content.ComponentName");
        var Intent = Java.use("android.content.Intent");
        var ActivityOptions = Java.use("android.app.ActivityOptions");

        var intent = Intent.$new();
        intent.setComponent(ComponentName.unflattenFromString(flat));
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP);

        var options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setLaunchBounds(Java.use("android.graphics.Rect").$new(VISIBLE_LEFT, 0, VISIBLE_RIGHT, 650));

        appContext().startActivity(intent, options.toBundle());
        launchedDisplayId = displayId;
        log("launched " + flat + " on display " + displayId + " (restart=" + restart + ")");
        return true;
      } catch (e) {
        warn("launchNavigator: " + brief(e, 240));
        return false;
      }
    }

    function ensureNavigator(displayId, restart) {
      if (!restart && launchedDisplayId === displayId && isNavigatorRunning()) return true;
      return launchNavigator(displayId, restart);
    }

    // Showing a card is two independent steps: the ViewPager page (local callback)
    // and the instrument MCU (socket protocol). Doing only one leaves either a blank
    // card or a card the MCU refuses to display.
    function switchCard(category) {
      var service = clusterService();
      if (service === null) {
        warn("switchCard " + category + ": cluster service not bound yet");
        return false;
      }
      var sync = service.mInstrumentCluster.value;
      var localCallback = service.mLocalCallback.value;
      if (localCallback !== null) localCallback.onCurrentItem(category);
      if (sync !== null) sync.notifyReadyToShow(category);
      log("switched cluster card to " + category);
      return true;
    }

    function announceNavi(state) {
      var service = clusterService();
      if (service === null) return false;
      var sync = service.mInstrumentCluster.value;
      if (sync === null) return false;
      sync.syncReadyState(NAVI, state);
      return true;
    }

    // NAVI_SHOW_THEM is one byte inside the shared 0x6D settings frame, and
    // setClusterState() resends that whole frame, so this is safe to repeat: every
    // other byte keeps the value the cluster already holds for it.
    function applyTheme(reason) {
      var service = clusterService();
      if (service === null) {
        warn("applyTheme (" + reason + "): cluster service not bound yet");
        return false;
      }
      var sync = service.mInstrumentCluster.value;
      if (sync === null) {
        warn("applyTheme (" + reason + "): protocol not ready");
        return false;
      }
      try {
        var value = resolveTheme();
        var state = Java.use(CLUSTER_STATE_CLASS).NAVI_SHOW_THEM.value;
        sync.setClusterState(state, value);
        log("navi card theme " + (value === THEME_NIGHT ? "night" : "day") +
            " (setting=" + theme() + ", " + reason + ")");
        return true;
      } catch (e) {
        warn("applyTheme (" + reason + "): " + brief(e, 240));
        return false;
      }
    }

    function showNavi(reason, restart, category) {
      var card = category || NAVI_FULL;
      var now = Date.now();
      if (now - lastShowAt < SHOW_DEDUPE_MS) {
        log("showNavi (" + reason + ") skipped, already shown " + (now - lastShowAt) + "ms ago");
        return false;
      }
      lastShowAt = now;
      var displayId = naviDisplayId();
      if (displayId < 0) {
        warn("showNavi (" + reason + "): navi virtual display not available");
        return false;
      }
      announceNavi(1);
      ensureNavigator(displayId, restart);
      // The MCU refuses the card if it is announced before the app has drawn a frame.
      setTimeout(function () {
        Java.perform(function () {
          switchCard(card);
          // The MCU only keeps the navi skin while a navi card is up, and a cluster
          // restart drops it, so the theme is re-sent with every card we show.
          applyTheme(reason);
        });
      }, 1500);
      log("showNavi (" + reason + ") display=" + displayId + " card=" + card);
      return true;
    }

    function hideNavi(reason) {
      lastAnswerAt = Date.now();
      lastShowAt = 0;
      announceNavi(0);
      switchCard(MEDIA_SMALL);
      log("hideNavi (" + reason + ")");
      return true;
    }

    function handleBroadcast(intent) {
      try {
        var componentExtra = intent.getStringExtra("component");
        if (componentExtra !== null && ("" + componentExtra).indexOf("/") > 0) {
          setComponent("" + componentExtra);
          launchedDisplayId = -1;
          log("component set to " + componentExtra);
        }

        var themeExtra = intent.getStringExtra("theme");
        if (themeExtra !== null) {
          var wanted = ("" + themeExtra).trim().toLowerCase();
          if (wanted === "day" || wanted === "night" || wanted === "auto") {
            setTheme(wanted);
            log("theme set to " + wanted);
            applyTheme("broadcast");
          } else {
            warn("ignored theme=" + brief(themeExtra, 40) + ", expected day|night|auto");
          }
        }

        var requested = intent.getIntExtra("enable", -1);
        // Without an explicit enable the broadcast toggles, so a theme-only one would
        // otherwise switch the card off as a side effect of repainting it.
        if (requested === -1 && themeExtra !== null && componentExtra === null) return;
        var enable = requested === -1 ? !isEnabled() : requested === 1;
        var restart = intent.getIntExtra("restart", 1) === 1;

        setEnabled(enable);
        if (enable) {
          lastAnswerAt = Date.now();
          showNavi("broadcast", restart, NAVI_FULL);
        } else {
          hideNavi("broadcast");
        }
      } catch (e) {
        warn("handleBroadcast: " + brief(e, 240));
      }
    }

    // A frida session tear-down reverts hooks but leaves a receiver registered by a
    // previous session in LoadedApk, backed by a runtime that no longer exists. Walk
    // the framework's own bookkeeping and drop those before registering ours.
    function contextImpl(context) {
      var ContextImpl = Java.use("android.app.ContextImpl");
      var ContextWrapper = Java.use("android.content.ContextWrapper");
      var current = context;
      for (var i = 0; i < 5 && current !== null; i++) {
        try { return Java.cast(current, ContextImpl); } catch (e) {}
        try { current = Java.cast(current, ContextWrapper).getBaseContext(); } catch (e) { return null; }
      }
      return null;
    }

    function unregisterStaleReceivers(context) {
      try {
        var impl = contextImpl(context);
        if (impl === null) { warn("unregisterStaleReceivers: no ContextImpl behind the context"); return; }
        var packageInfo = Java.use("android.app.ContextImpl").class.getDeclaredField("mPackageInfo");
        packageInfo.setAccessible(true);
        var loadedApk = packageInfo.get(impl);
        if (loadedApk === null) return;

        var receivers = Java.use("android.app.LoadedApk").class.getDeclaredField("mReceivers");
        receivers.setAccessible(true);
        var outer = Java.cast(receivers.get(loadedApk), ArrayMap);
        var stale = [];
        for (var i = 0; i < outer.size(); i++) {
          var inner = Java.cast(outer.valueAt(i), ArrayMap);
          for (var j = 0; j < inner.size(); j++) {
            var receiver = inner.keyAt(j);
            if (("" + receiver.getClass().getName()) === RECEIVER_CLASS) stale.push(receiver);
          }
        }
        for (var k = 0; k < stale.length; k++) {
          try {
            context.unregisterReceiver(Java.cast(stale[k], Java.use("android.content.BroadcastReceiver")));
            log("dropped stale receiver from a previous session");
          } catch (e) {
            warn("unregister stale receiver: " + brief(e, 180));
          }
        }
      } catch (e) {
        warn("unregisterStaleReceivers: " + brief(e, 180));
      }
    }

    function registerReceiver() {
      var Receiver;
      try {
        Receiver = Java.use(RECEIVER_CLASS);
      } catch (e) {
        Receiver = Java.registerClass({
          name: RECEIVER_CLASS,
          superClass: Java.use("android.content.BroadcastReceiver"),
          methods: {
            onReceive: {
              returnType: "void",
              argumentTypes: ["android.content.Context", "android.content.Intent"],
              implementation: function (context, intent) {
                handleBroadcast(intent);
              }
            }
          }
        });
      }
      var IntentFilter = Java.use("android.content.IntentFilter");
      var context = appContext();
      unregisterStaleReceivers(context);
      context.registerReceiver.overload(
        "android.content.BroadcastReceiver",
        "android.content.IntentFilter",
        "java.lang.String",
        "android.os.Handler"
      ).call(context, Receiver.$new(), IntentFilter.$new(ACTION), SENDER_PERMISSION, null);
      log("receiver registered: " + ACTION);
    }

    // The MCU drives the card carousel: it asks a registered navi client to present
    // (state 1) and reports that it gave up waiting (state 2). With no navi package
    // installed nobody answers, the request expires after 60s and the cluster keeps
    // drawing its built-in placeholder. Answer on the navigator's behalf.
    function hookPresentationRequests() {
      var protocol = Java.use(PROTOCOL_CLASS);
      var argsCallback = protocol.argsCallback.overload("int", "[Ljava.lang.Object;");
      argsCallback.implementation = function (callbackId, values) {
        try {
          if (callbackId === PRESENTATION_CALLBACK_ID && values !== null && values.length >= 2) {
            var category = "" + values[0];
            var state = parseInt("" + values[1], 10);
            var now = Date.now();
            if (category.indexOf(NAVI) === 0 && state === 1 && !isEnabled()) {
              setTimeout(function () { Java.perform(function () { applyTheme("MCU request, navigator off"); }); }, 0);
            } else if (category.indexOf(NAVI) === 0 && state === 1 &&
                now - lastAnswerAt >= ANSWER_COOLDOWN_MS) {
              lastAnswerAt = now;
              setTimeout(function () {
                Java.perform(function () {
                  showNavi("MCU presentation request", false, category);
                });
              }, 0);
            }
          }
        } catch (e) {
          warn("argsCallback hook: " + brief(e, 180));
        }
        return argsCallback.call(this, callbackId, values);
      };
      log("hooked VoyahInstrumentCluster.argsCallback");
    }

    // Every cluster restart tears the navi virtual display down and builds a new one
    // with a new display id, which kills the navigator's task. Re-arm from here.
    function hookDisplayRecreation() {
      var fragment = Java.use(NAVI_FRAGMENT_CLASS);
      var create = fragment.createVirtualDisplay.overload("android.view.Surface", "int", "int");
      create.implementation = function (surface, width, height) {
        var virtualDisplay = create.call(this, surface, width, height);
        try {
          launchedDisplayId = -1;
          if (isEnabled()) {
            setTimeout(function () {
              Java.perform(function () {
                lastAnswerAt = Date.now();
                showNavi("navi display recreated", true, NAVI_FULL);
              });
            }, 2500);
          } else {
            setTimeout(function () { Java.perform(function () { applyTheme("navi display recreated"); }); }, 2500);
          }
        } catch (e) {
          warn("createVirtualDisplay hook: " + brief(e, 180));
        }
        return virtualDisplay;
      };
      log("hooked ClusterNaviFragment.createVirtualDisplay");
    }

    function install() {
      // A plain version guard lives in a JVM system property and so outlives the
      // frida session that installed the hooks: re-running the installer would then
      // skip installation and leave the process with no hooks at all. The installer
      // bumps KEY_TOKEN, which makes the stamp differ on every deliberate install.
      var stamp = VERSION + ":" + installToken();
      if ("" + System.getProperty(GUARD, "") === stamp) {
        log("already installed (" + stamp + "), skipping");
        return;
      }
      hookDisplayRecreation();
      registerReceiver();
      // Only Voyah-protocol clusters expose argsCallback; on a GeneralInstrumentCluster
      // build the card still works, it just will not follow the driver's own card
      // selection. Never let that cost us the rest of the hooks.
      try {
        hookPresentationRequests();
      } catch (e) {
        warn("MCU presentation hook unavailable, card follows broadcasts only: " + brief(e, 180));
      }
      System.setProperty(GUARD, stamp);

      if (isEnabled()) {
        setTimeout(function () {
          Java.perform(function () {
            lastAnswerAt = Date.now();
            showNavi("startup restore", true, NAVI_FULL);
          });
        }, 4000);
      } else {
        // Without the navigator nothing else sends the byte, so the MCU keeps its
        // power-on day skin; restore the saved theme on its own.
        setTimeout(function () { Java.perform(function () { applyTheme("startup restore"); }); }, 4000);
      }
      log("hook ready " + VERSION + " token=" + installToken() + ": enabled=" + isEnabled() +
          " component=" + component() + " theme=" + theme());
    }

    var attempts = 0;
    function bootstrap() {
      try {
        install();
      } catch (e) {
        attempts++;
        if (attempts < 15) {
          setTimeout(function () { Java.perform(bootstrap); }, 2000);
          warn("install retry " + attempts + ": " + brief(e, 180));
        } else {
          warn("install failed permanently: " + brief(e, 240));
        }
      }
    }

    bootstrap();
  });
})();

// Kept identical in OD app_client.js and PI clusternavi.js; both are standalone agents.
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
            pending = {snapshot: snapshot, sampled: now()};
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
                listenerMethods[name] = function () {};
            });
            listenerMethods.onBackgroundGuidanceWillBeSuspended = function (reason) {
                changedAt = -1;
                queue(null);
            };
            listenerMethods.onParkingRouteBuilt = function (type) {};
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
            var Listener = Java.registerClass({
                name: "ru.big.town.navigation.YandexGuidanceListener",
                implements: [Java.use("com.yandex.navikit.guidance.GuidanceListener")],
                methods: listenerMethods
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
                    return {icon: policy.yandexIcon(String(annotation.getAction().name())),
                        distance: Math.round(distance), road: road === null ? "" : String(road)};
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
                return {icon: policy.dgisIcon(String(state.mManeuverIcon.value)),
                    distance: distance, road: String(state.mManeuverDescription.value || "")};
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

function installClusterNavigation() {
    var unitsReady = false;
    try {
        var GuidanceFormat = Java.use("com.qinggan.cluster.GuidanceFormat");
        var distanceUnit = GuidanceFormat.toDistanceUnit.overload("android.content.Context", "int");
        // The OEM formatter owns both the UTF-8 string and its packet length.
        distanceUnit.implementation = function (context, distanceMeters) {
            return distanceMeters < 1000 ? "m" : "km";
        };
        unitsReady = true;
        console.log("[cluster-units] hook ready v1");
    } catch (error) {
        console.log("[cluster-units] hook unavailable: " + error);
    }

    try {
        var ActivityThread = Java.use("android.app.ActivityThread");
        var application = ActivityThread.currentApplication();
        var context = application !== null ? application
            : ActivityThread.currentActivityThread().getSystemContext();
        var resolver = context.getContentResolver();
        var SettingsGlobal = Java.use("android.provider.Settings$Global");
        var Handler = Java.use("android.os.Handler");
        var Looper = Java.use("android.os.Looper");
        var mainHandler = Handler.$new(Looper.getMainLooper());
        var setting = "voyahtune_cluster_navigation_enabled";
        var descriptor = "com.qinggan.cluster.IInstrumentClusterManagerService";
        var Contract = Java.use(descriptor);
        var Stub = Java.use(descriptor + "$Stub");
        var NaviInfo = Java.use("com.qinggan.cluster.info.NaviInfo");
        var GuideState = Java.use("com.qinggan.cluster.info.GuideState");
        var enabled = true;
        var target = null;
        var targetUpdate = null;
        var hookedClasses = {};

        function stoppedInfo() {
            var info = NaviInfo.$new();
            info.setGuideState(GuideState.MAN_STOP.value);
            info.setDisplay(false);
            info.setTbtIconId(0);
            info.setNextDistance(0);
            info.setCurrentRoadName("");
            info.setTurnRoadName("");
            info.setDestName("");
            return info;
        }

        function clearOutput() {
            if (enabled || target === null) return;
            try {
                targetUpdate.call(target, stoppedInfo());
            } catch (error) {
                console.log("[cluster-navigation] clear failed: " + error);
            }
        }

        function refreshEnabled() {
            try {
                var value = SettingsGlobal.getString(resolver, setting);
                var wasEnabled = enabled;
                enabled = value === null || parseInt("" + value, 10) !== 0;
                if (wasEnabled && !enabled && target !== null) {
                    // Settings callbacks run on the OEM main looper; write on the Frida worker.
                    setTimeout(function () { Java.perform(clearOutput); }, 0);
                }
            } catch (error) {
                console.log("[cluster-navigation] setting unavailable: " + error);
            }
        }

        function captureTarget(instance) {
            if (target !== null && target.equals(instance)) return;
            var className = "" + instance.getClass().getName();
            var Implementation = Java.use(className);
            var update = Implementation.updateNaviInfo.overload("com.qinggan.cluster.info.NaviInfo");
            if (!hookedClasses[className]) {
                update.implementation = function (info) {
                    // Replace the object, leaving the caller's route state untouched.
                    return update.call(this, enabled ? info : stoppedInfo());
                };
                hookedClasses[className] = true;
            }
            if (target !== null) target.$dispose();
            target = Java.retain(Java.cast(instance, Implementation));
            targetUpdate = update;
            if (!enabled) clearOutput();
        }

        function findServiceTarget() {
            try {
                // ActivityThread keeps live services without an ART heap walk.
                var thread = ActivityThread.currentActivityThread();
                var servicesField = ActivityThread.class.getDeclaredField("mServices");
                servicesField.setAccessible(true);
                var services = Java.cast(servicesField.get(thread), Java.use("android.util.ArrayMap"));
                for (var index = 0; index < services.size(); index++) {
                    var service = services.valueAt(index);
                    var type = service.getClass();
                    if (("" + type.getName()).indexOf("InstrumentClusterService") < 0) continue;
                    while (type !== null && ("" + type.getName()).indexOf("com.qinggan.cluster.") === 0) {
                        var fields = type.getDeclaredFields();
                        for (var fieldIndex = 0; fieldIndex < fields.length; fieldIndex++) {
                            try {
                                fields[fieldIndex].setAccessible(true);
                                var candidate = fields[fieldIndex].get(service);
                                if (candidate !== null && Contract.class.isInstance(candidate)) {
                                    captureTarget(candidate);
                                    return true;
                                }
                            } catch (ignored) {}
                        }
                        type = type.getSuperclass();
                    }
                }
            } catch (error) {
                console.log("[cluster-navigation] service lookup unavailable: " + error);
            }
            return false;
        }

        // Validate the installed Parcelable before intercepting any OEM calls.
        stoppedInfo();

        // Discover the installed Stub implementation instead of fixing an anonymous class number.
        var transact = Stub.onTransact.overload("int", "android.os.Parcel", "android.os.Parcel", "int");
        transact.implementation = function (code, data, reply, flags) {
            try {
                captureTarget(this);
            } catch (error) {
                console.log("[cluster-navigation] writer hook unavailable: " + error);
            }
            return transact.call(this, code, data, reply, flags);
        };
        var Observer = Java.registerClass({
            name: "ru.big.town.cluster.ManeuverSettingsObserver",
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
                    implementation: function () { refreshEnabled(); }
                }]
            }
        });
        resolver.registerContentObserver(SettingsGlobal.getUriFor(setting), false, Observer.$new(mainHandler));
        refreshEnabled();

        var attempts = 0;
        function discoverService() {
            if (target !== null || findServiceTarget()) return;
            attempts++;
            if (attempts < 15) {
                setTimeout(function () { Java.perform(discoverService); }, 2000);
            } else {
                console.log("[cluster-navigation] awaiting first cluster Binder call");
            }
        }
        discoverService();
        console.log("[cluster-navigation] hook ready v1");
    } catch (error) {
        console.log("[cluster-navigation] hook unavailable: " + error);
    }
    return unitsReady;
}
