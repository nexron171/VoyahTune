(function () {
  Java.perform(function () {
    "use strict";

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
