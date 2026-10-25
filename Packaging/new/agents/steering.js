// Keep one media route from physical DOWN through UP to prevent duplicate delivery.
// Timer state is local to each button; Native executes configured actions.
Java.perform(function () {
    var LONG_MS = 600;

    var MEDIA_MAP = {
        3: { command: "next", keyCode: 87 },
        4: { command: "previous", keyCode: 88 },
        6: { command: "play_pause", keyCode: 85 }
    };
    var MEDIA_COMMAND_URI = "content://ru.big.town.anative.nowplaying";
    var MEDIA_PROXY_ACTION = "ru.big.town.anative.MEDIA_KEY_PROXY";
    var MEDIA_PROXY_ACK = -1;
    var mediaPress = {};
    var readerOnKeyEvent = null;
    var readerInstance = null;
    var mediaProxyReceiver = null;
    var mediaProxyInstalled = false;

    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var Intent = Java.use("android.content.Intent");
    var IntentFilter = Java.use("android.content.IntentFilter");
    var BroadcastReceiver = Java.use("android.content.BroadcastReceiver");
    var Uri = Java.use("android.net.Uri");
    var KeyEvent = Java.use("android.view.KeyEvent");
    var AudioManager = Java.use("android.media.AudioManager");
    var Log = Java.use("android.util.Log");
    var TAG = "vt_steeringwheelkeys";

    function ctx() {
        try { var app = ActivityThread.currentApplication(); if (app !== null) return app.getApplicationContext(); } catch (e) { }
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    function applicationCtx() {
        try {
            var app = ActivityThread.currentApplication();
            return app === null ? null : app.getApplicationContext();
        } catch (e) { return null; }
    }

    function action(slot) {
        try {
            var v = SettingsGlobal.getString(ctx().getContentResolver(), "voyahtune_" + slot);
            return (v === null) ? "none" : v.toString();
        } catch (e) { return "none"; }
    }

    function mediaDecision(spec) {
        var fallback = { route: "native", keyCode: spec.keyCode };
        try {
            var resolver = ctx().getContentResolver();
            var call = resolver.call.overload(
                "android.net.Uri", "java.lang.String", "java.lang.String", "android.os.Bundle");
            var result = call.call(resolver, Uri.parse(MEDIA_COMMAND_URI),
                "media_command", spec.command, null);
            if (result === null) return fallback;
            var routeValue = result.getString("route");
            var route = routeValue === null ? "native" : routeValue.toString();
            if (route !== "direct" && route !== "keymanager"
                && route !== "native" && route !== "noop") return fallback;
            var keyCode = result.getInt("keyCode", spec.keyCode);
            // Provider contract for a physical wheel command is exact; never turn PLAY_PAUSE into
            // PAUSE (or vice versa) because of a malformed/stale response.
            if (route === "keymanager" && keyCode !== spec.keyCode) keyCode = spec.keyCode;
            Log.i(TAG, "[swk] media decision " + spec.command + " -> " + route + " kc=" + keyCode);
            return { route: route, keyCode: keyCode };
        } catch (e) {
            Log.e(TAG, "[swk] media decision err, native fallback: " + e);
            return fallback;
        }
    }

    function doAction(id) {
        if (id === "none") return;
        try {
            var i = Intent.$new("ru.big.town.anative.STEER_ACTION");
            i.setClassName("ru.big.town.anative", "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra("action", id);
            i.addFlags(0x00000020);
            ctx().sendBroadcast(i);
            Log.i(TAG, "[swk] STEER_ACTION -> " + id);
        } catch (e) { Log.e(TAG, "[swk] doAction err: " + e); }
    }

    function dispatchMedia(kc) {
        var am;
        try {
            am = Java.cast(ctx().getSystemService("audio"), AudioManager);
            am.dispatchMediaKeyEvent(KeyEvent.$new(0, kc));   // ACTION_DOWN
        } catch (e) {
            Log.e(TAG, "[swk] media DOWN failed before delivery: " + e);
            return false;
        }

        try {
            am.dispatchMediaKeyEvent(KeyEvent.$new(1, kc));   // ACTION_UP
        } catch (e) {
            Log.e(TAG, "[swk] media UP failed after delivered DOWN, no fallback: " + e);
        }
        Log.i(TAG, "[swk] media dispatch kc=" + kc);
        return true;
    }

    // Door PAUSE_ONLY asks keymanager to reproduce the path proven by a physical button. Native QG
    // recreation is allowed only for confirmed active playback and only QG6/PLAY_PAUSE.
    function syntheticNativePlayPause() {
        var reader = readerInstance;
        if (reader === null || readerOnKeyEvent === null) {
            Log.i(TAG, "[swk] MEDIA_KEY_PROXY nativeQG: no captured KeyManagerReader");
            return false;
        }
        try {
            readerOnKeyEvent.call(reader, KeyEvent.$new(0, 6));
        } catch (e) {
            Log.e(TAG, "[swk] MEDIA_KEY_PROXY QG down err: " + e);
            return false;
        }

        try { readerOnKeyEvent.call(reader, KeyEvent.$new(1, 6)); }
        catch (e) {
            Log.e(TAG, "[swk] MEDIA_KEY_PROXY QG up failed after DOWN, no fallback: " + e);
        }
        return true;
    }

    function allowedMediaKey(kc) {
        return kc === 85 || kc === 87 || kc === 88 || kc === 126 || kc === 127;
    }

    // Receiver accepts only Native: the permission is checked against the SENDER at registration.
    // No manifest change in the vendor process is needed. Ordered result lets Native fall back when
    // the hook is absent or no Reader instance has been captured yet.
    function installMediaProxyReceiver(attempt) {
        if (mediaProxyInstalled) return;
        var context = applicationCtx();
        if (context === null) {
            if (attempt < 120) {
                setTimeout(function () { installMediaProxyReceiver(attempt + 1); }, 250);
            } else {
                Log.w(TAG, "[swk] MEDIA_KEY_PROXY: application context unavailable");
            }
            return;
        }
        try {
            if (mediaProxyReceiver === null) {
                var ReceiverClass = Java.registerClass({
                    name: "com.qinggan.keymanager.service.VoyahMediaProxyReceiver_" + Date.now(),
                    superClass: BroadcastReceiver,
                    methods: {
                        onReceive: {
                            returnType: "void",
                            argumentTypes: ["android.content.Context", "android.content.Intent"],
                            implementation: function (receiverContext, intent) {
                                try {
                                    if (intent === null || intent.getAction() === null
                                        || intent.getAction().toString() !== MEDIA_PROXY_ACTION) return;
                                    var keyCode = intent.getIntExtra("keyCode", -1);
                                    if (!allowedMediaKey(keyCode)) {
                                        Log.w(TAG, "[swk] MEDIA_KEY_PROXY rejected kc=" + keyCode);
                                        return;
                                    }
                                    var nativeQG = intent.getBooleanExtra("nativeQG", false);
                                    if (nativeQG && keyCode !== 85) {
                                        Log.w(TAG, "[swk] MEDIA_KEY_PROXY rejected nativeQG kc=" + keyCode);
                                        return;
                                    }
                                    var handled = nativeQG
                                        ? syntheticNativePlayPause() : dispatchMedia(keyCode);
                                    if (handled) this.setResultCode(MEDIA_PROXY_ACK);
                                } catch (e) {
                                    Log.e(TAG, "[swk] MEDIA_KEY_PROXY err: " + e);
                                }
                            }
                        }
                    }
                });
                mediaProxyReceiver = ReceiverClass.$new();
            }
            var register = context.registerReceiver.overload(
                "android.content.BroadcastReceiver", "android.content.IntentFilter",
                "java.lang.String", "android.os.Handler");
            register.call(context, mediaProxyReceiver, IntentFilter.$new(MEDIA_PROXY_ACTION),
                "android.permission.WRITE_SECURE_SETTINGS", null);
            mediaProxyInstalled = true;
            Log.i(TAG, "[swk] MEDIA_KEY_PROXY receiver registered");
        } catch (e) {
            Log.e(TAG, "[swk] MEDIA_KEY_PROXY registration attempt " + attempt + " failed: " + e);
            if (attempt < 120) {
                setTimeout(function () { installMediaProxyReceiver(attempt + 1); }, 250);
            }
        }
    }

    function pressHandler(shortSlot, longSlot) {
        var longFired = false, nativeLongFallback = false, timer = null;
        return {
            passthrough: function () {
                return action(shortSlot) === "none" && action(longSlot) === "none";
            },
            down: function (repeatCount) {
                if (repeatCount > 0) return;
                longFired = false;
                nativeLongFallback = false;
                if (timer !== null) clearTimeout(timer);
                var longA = action(longSlot);
                timer = setTimeout(function () {
                    timer = null;
                    if (longA === "none") {

                        nativeLongFallback = true;
                        return;
                    }
                    longFired = true;
                    doAction(longA);
                }, LONG_MS);

                return true;
            },
            up: function () {
                if (timer !== null) { clearTimeout(timer); timer = null; }
                if (nativeLongFallback) {
                    nativeLongFallback = false;
                    return false;
                }
                if (!longFired) {
                    if (action(shortSlot) === "none") {
                        return false;
                    } else {
                        doAction(action(shortSlot));
                    }
                }
                return true;
            }
        };
    }

    try {
        var Reader = Java.use("com.qinggan.keymanager.service.engine.KeyManagerReader");
        var BUTTON_MAP = {
            3090: "STAR",
            173: "DVR",
            130: "VOICE",
            128: "PHONE"
        };

        var HANDLER_MAP = {
            "STAR": pressHandler("steerStarShort", "steerStarLong"),
            "DVR": pressHandler("steerDvrShort", "steerDvrLong"),
            "VOICE": pressHandler("steerVoiceShort", "steerVoiceLong"),
            "PHONE": pressHandler("steerPhoneShort", "steerPhoneLong")
        };

        readerOnKeyEvent = Reader.onKeyEvent.overload("android.view.KeyEvent");
        readerOnKeyEvent.implementation = function (ke) {
            if (readerInstance === null) {
                try { readerInstance = Java.retain(this); }
                catch (e) { Log.e(TAG, "[swk] retain KeyManagerReader err: " + e); }
            }
            var code = ke.getKeyCode();
            Log.i(TAG, "key press: " + code + " action: " + ke.getAction())

            if (MEDIA_MAP.hasOwnProperty(code)) {
                var spec = MEDIA_MAP[code];
                var eventAction = ke.getAction();
                var repeat = ke.getRepeatCount();
                var state = mediaPress[code] || null;
                if (eventAction === 0 && repeat === 0) {
                    state = mediaDecision(spec);
                    mediaPress[code] = state;
                    if (state.route === "keymanager" && !dispatchMedia(state.keyCode)) {
                        state.route = "native";
                    }
                }
                if (eventAction === 1) delete mediaPress[code];
                if (state !== null && state.route !== "native") return true;
                return readerOnKeyEvent.call(this, ke);
            }
            if (BUTTON_MAP.hasOwnProperty(code)) {
                var actionCode = BUTTON_MAP[code];
                var h = HANDLER_MAP[actionCode];

                if (h === null) return readerOnKeyEvent.call(this, ke);
                if (h.passthrough()) return readerOnKeyEvent.call(this, ke);
                if (ke.getAction() == 0) {
                    h.down(ke.getRepeatCount());
                }
                else if (ke.getAction() == 1) {
                    if (h.up() === false) {

                        try {
                            var replayDown = KeyEvent.changeAction(
                                KeyEvent.changeTimeRepeat(ke, ke.getDownTime(), 0), 0);
                            readerOnKeyEvent.call(this, replayDown);
                            return readerOnKeyEvent.call(this, ke);
                        } catch (e) {
                            Log.e(TAG, "[swk] native action replay err: " + e);
                            return true;
                        }
                    }
                }
                return true;
            }
            return readerOnKeyEvent.call(this, ke);
        };
        installMediaProxyReceiver(0);
        var ready = "[swk] keymanager hooks installed: STAR DVR VOICE PHONE media=3/4/6 (LONG_MS=" + LONG_MS + ")";
        Log.i(TAG, ready);
        console.log("[steering] hook ready v1");
        console.log(ready); // Loader can observe readiness before frida-inject -e returns.
    } catch (e) {

        Log.e(TAG, "[swk] KeyManagerReader not found (not keymanager?): " + e);
    }

});
