// steeringwheelkeys.js — хуки кнопок руля Open Voyah в процессе com.qinggan.keymanager.service.
// Действийные SWC-кнопки идут через ОДИН KeyManagerReader.onKeyEvent с кастомными QG-кодами
// (снято разведкой на живой голове H97C):
//   • кнопка-звёздочка          = keycode 3090
//   • видеорегистратор (DVR)     = keycode 173
//   • медиа-кнопки: play/pause = keycode 6, next = 3, prev = 4. На initial DOWN Native получает
//     свежий список MediaSession и выбирает РОВНО ОДИН путь: exact MediaController, keymanager или
//     оригинальный Qinggan. Решение держим до физического UP, исключая двойную доставку.
//
// Долгое нажатие — ПО ТАЙМЕРУ (порог LONG_MS): сработал → сразу «долгое», не дожидаясь UP; отпустил
// раньше порога → «короткое». Действия читаем ЖИВЬЁМ из Settings.Global (voyahtune_<slot>, зеркалит
// Native из UI «Кнопки на руле»), исполняет их Native через explicit-broadcast STEER_ACTION →
// SetModesReceiverDynamic.handleSteerAction. Оба слота кнопки "none" → НЕ перехватываем (штатно).
Java.perform(function () {
    var LONG_MS = 600;
    // Медиа-кнопки руля: QG-код → команда Native + стандартный media keycode.
    // Снято живым тестом на голове H97C: code 6=play/pause, 3=next, 4=prev.
    var MEDIA_MAP = {
        3: { command: "next",       keyCode: 87 },
        4: { command: "previous",   keyCode: 88 },
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

    function ctx() {
        try { var app = ActivityThread.currentApplication(); if (app !== null) return app.getApplicationContext(); } catch (e) {}
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    // Для package-targeted runtime receiver годится только настоящий context приложения keymanager.
    // SystemContext имеет package=android и explicit broadcast до него не дойдёт.
    function applicationCtx() {
        try {
            var app = ActivityThread.currentApplication();
            return app === null ? null : app.getApplicationContext();
        } catch (e) { return null; }
    }

    // Действие слота из Settings.Global; нет значения → "none" (не перехватываем).
    function action(slot) {
        try {
            var v = SettingsGlobal.getString(ctx().getContentResolver(), "voyahtune_" + slot);
            return (v === null) ? "none" : v.toString();
        } catch (e) { return "none"; }
    }

    // На initial DOWN Native атомарно читает свежий список сессий и либо уже доставляет key в точный
    // MediaController (direct), либо просит этот процесс сохранить рабочий keymanager-путь, либо оставляет
    // оригинальный QG event (native). Любая ошибка — native passthrough, то есть заводское поведение.
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
            console.log("[swk] media decision " + spec.command + " -> " + route + " kc=" + keyCode);
            return { route: route, keyCode: keyCode };
        } catch (e) {
            console.log("[swk] media decision err, native fallback: " + e);
            return fallback;
        }
    }

    // Отдаём исполнение Native — explicit broadcast STEER_ACTION с id (напр. "energy:EV,REV").
    function doAction(id) {
        if (id === "none") return;
        try {
            var i = Intent.$new("ru.big.town.anative.STEER_ACTION");
            i.setClassName("ru.big.town.anative", "ru.big.town.anative.SetModesReceiverDynamic");
            i.putExtra("action", id);
            i.addFlags(0x00000020);   // FLAG_INCLUDE_STOPPED_PACKAGES — добудиться, даже если Native стоплен
            ctx().sendBroadcast(i);
            console.log("[swk] STEER_ACTION -> " + id);
        } catch (e) { console.log("[swk] doAction err: " + e); }
    }

    // Медиа-кнопка → ровно один стандартный медиа-эвент в активную media-session (работает в любом
    // плеере). Штатная QG-маршрутизация флейковая (пауза не снималась) — поэтому гасим её и шлём сами.
    function dispatchMedia(kc) {
        var am;
        try {
            am = Java.cast(ctx().getSystemService("audio"), AudioManager);
            am.dispatchMediaKeyEvent(KeyEvent.$new(0, kc));   // ACTION_DOWN
        } catch (e) {
            console.log("[swk] media DOWN failed before delivery: " + e);
            return false;
        }

        // После успешно вернувшегося void DOWN backend уже выбран. Ошибка UP не разрешает второй
        // native/fallback toggle — только логируем незавершённую пару.
        try {
            am.dispatchMediaKeyEvent(KeyEvent.$new(1, kc));   // ACTION_UP
        } catch (e) {
            console.log("[swk] media UP failed after delivered DOWN, no fallback: " + e);
        }
        console.log("[swk] media dispatch kc=" + kc);
        return true;
    }

    // Door PAUSE_ONLY asks keymanager to reproduce the path proven by a physical button. Native QG
    // recreation is allowed only for confirmed active playback and only QG6/PLAY_PAUSE.
    function syntheticNativePlayPause() {
        var reader = readerInstance;
        if (reader === null || readerOnKeyEvent === null) {
            console.log("[swk] MEDIA_KEY_PROXY nativeQG: no captured KeyManagerReader");
            return false;
        }
        try {
            readerOnKeyEvent.call(reader, KeyEvent.$new(0, 6));
        } catch (e) {
            console.log("[swk] MEDIA_KEY_PROXY QG down err: " + e);
            return false;
        }
        // UP синхронный: ACK ordered-broadcast выдаётся только после попытки завершить всю пару.
        try { readerOnKeyEvent.call(reader, KeyEvent.$new(1, 6)); }
        catch (e) {
            console.log("[swk] MEDIA_KEY_PROXY QG up failed after DOWN, no fallback: " + e);
        }
        return true;
    }

    function allowedMediaKey(kc) {
        return kc === 85 || kc === 87 || kc === 88 || kc === 127;
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
                console.log("[swk] MEDIA_KEY_PROXY: application context unavailable");
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
                                        console.log("[swk] MEDIA_KEY_PROXY rejected kc=" + keyCode);
                                        return;
                                    }
                                    var nativeQG = intent.getBooleanExtra("nativeQG", false);
                                    if (nativeQG && keyCode !== 85) {
                                        console.log("[swk] MEDIA_KEY_PROXY rejected nativeQG kc=" + keyCode);
                                        return;
                                    }
                                    var handled = nativeQG
                                        ? syntheticNativePlayPause() : dispatchMedia(keyCode);
                                    if (handled) this.setResultCode(MEDIA_PROXY_ACK);
                                } catch (e) {
                                    console.log("[swk] MEDIA_KEY_PROXY err: " + e);
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
            console.log("[swk] MEDIA_KEY_PROXY receiver registered");
        } catch (e) {
            console.log("[swk] MEDIA_KEY_PROXY registration attempt " + attempt + " failed: " + e);
            if (attempt < 120) {
                setTimeout(function () { installMediaProxyReceiver(attempt + 1); }, 250);
            }
        }
    }

    // Таймерный обработчик короткого/долгого для ОДНОЙ кнопки — держит своё состояние (звёздочка и
    // DVR не мешают друг другу). Значения слотов читаются живьём в момент нажатия/отпускания.
    function pressHandler(shortSlot, longSlot) {
        var longFired = false, timer = null;
        return {
            passthrough: function () {   // оба слота "none" → перехватывать не нужно
                return action(shortSlot) === "none" && action(longSlot) === "none";
            },
            down: function (repeatCount) {
                if (repeatCount > 0) return;          // игнор автоповтора удержания
                longFired = false;
                if (timer !== null) clearTimeout(timer);
                var longA = action(longSlot);
                timer = setTimeout(function () {      // порог удержания
                    longFired = true; timer = null;
                    doAction(longA);                  // долгое — СРАЗУ по порогу, не дожидаясь UP
                }, LONG_MS);
                // DOWN держим до решения short/long. Иначе short=none+long=custom отдавал бы OEM
                // DOWN, но поглощал UP после long и оставлял KeyManager в pressed-state.
                return true;
            },
            up: function () {
                if (timer !== null) { clearTimeout(timer); timer = null; }
                if (!longFired) {
                    if (action(shortSlot) === "none") {
                        return false; // на UP нужно replay штатной пары DOWN+UP
                    } else {
                        doAction(action(shortSlot));   // короткое — на отпускании, если долгого не было}
                    }
                }
                return true;
            }
        };
    }

    try {
        var Reader = Java.use("com.qinggan.keymanager.service.engine.KeyManagerReader");
        var BUTTON_MAP = {
            3090 : "STAR",
            173 : "DVR",
            130 : "VOICE",
            128 : "PHONE"
        };

        var HANDLER_MAP = {
            "STAR" : pressHandler("steerStarShort", "steerStarLong"),
            "DVR" : pressHandler("steerDvrShort",  "steerDvrLong"),
            "VOICE" : pressHandler("steerVoiceShort",  "steerVoiceLong"),
            "PHONE" : pressHandler("steerPhoneShort",  "steerPhoneLong")
        };

        readerOnKeyEvent = Reader.onKeyEvent.overload("android.view.KeyEvent");
        readerOnKeyEvent.implementation = function (ke) {
            if (readerInstance === null) {
                try { readerInstance = Java.retain(this); }
                catch (e) { console.log("[swk] retain KeyManagerReader err: " + e); }
            }
            var code = ke.getKeyCode();
            // Решение вычисляем один раз на initial DOWN и держим до UP. direct/noop уже обработаны
            // Native; keymanager отправляет одну стандартную пару здесь; native получает настоящие
            // физические DOWN/repeat/UP через оригинальную реализацию.
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
                // Кнопки-действия (звезда/DVR) — таймерное короткое/долгое.
                if (h === null) return readerOnKeyEvent.call(this, ke);        // не наша кнопка → штатно
                if (h.passthrough()) return readerOnKeyEvent.call(this, ke);   // не настроено → штатно
                if (ke.getAction() == 0) {
                    h.down(ke.getRepeatCount());
                }
                else if (ke.getAction() == 1) {
                    if (h.up() === false) {
                        // Короткое штатное действие было отложено до UP, чтобы long мог безопасно
                        // поглотить обе половины. Replay делаем полной парой через original overload.
                        try {
                            readerOnKeyEvent.call(this, KeyEvent.changeAction(ke, 0));
                            return readerOnKeyEvent.call(this, ke);
                        } catch (e) {
                            console.log("[swk] native short replay err: " + e);
                            return true;
                        }
                    }
                }
                return true; // гасим штатное действие кнопки
            }
            return readerOnKeyEvent.call(this, ke);     // штатное действие кнопки
        };
        installMediaProxyReceiver(0);
        console.log("[swk] keymanager hooks installed: STAR DVR VOICE PHONE media=3/4/6 (LONG_MS=" + LONG_MS + ")");
    } catch (e) {
        // Если класс не найден (скрипт заинжектили не в keymanager) — просто ничего не делаем.
        console.log("[swk] KeyManagerReader not found (not keymanager?): " + e);
    }

});
