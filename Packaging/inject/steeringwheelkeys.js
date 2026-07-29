// steeringwheelkeys.js — хуки кнопок руля Open Voyah в процессе com.qinggan.keymanager.service.
// Действийные SWC-кнопки идут через ОДИН KeyManagerReader.onKeyEvent с кастомными QG-кодами
// (снято разведкой на живой голове H97C):
//   • кнопка-звёздочка          = keycode 3090
//   • видеорегистратор (DVR)     = keycode 173
//   • медиа-кнопки: play/pause = keycode 6, next = 3, prev = 4 — на них шлём стандартный
//     медиа-эвент в активную media-session (AudioManager.dispatchMediaKeyEvent), работает в любом
//     плеере; штатную (флейковую) QG-маршрутизацию гасим (return true).
//
// Долгое нажатие — ПО ТАЙМЕРУ (порог LONG_MS): сработал → сразу «долгое», не дожидаясь UP; отпустил
// раньше порога → «короткое». Действия читаем ЖИВЬЁМ из Settings.Global (voyahtune_<slot>, зеркалит
// Native из UI «Кнопки на руле»), исполняет их Native через explicit-broadcast STEER_ACTION →
// SetModesReceiverDynamic.handleSteerAction. Оба слота кнопки "none" → НЕ перехватываем (штатно).
Java.perform(function () {
    var LONG_MS = 600;
    // Медиа-кнопки руля: QG-код → стандартный медиа keycode (85=PLAY_PAUSE, 87=NEXT, 88=PREV).
    // Снято живым тестом на голове H97C: code 6=play/pause, 3=next, 4=prev.
    var MEDIA_MAP = { 3: 87, 4: 88, 6: 85 };

    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var Intent = Java.use("android.content.Intent");
    var KeyEvent = Java.use("android.view.KeyEvent");
    var AudioManager = Java.use("android.media.AudioManager");

    function ctx() {
        try { var app = ActivityThread.currentApplication(); if (app !== null) return app.getApplicationContext(); } catch (e) {}
        return ActivityThread.currentActivityThread().getSystemContext();
    }

    // Действие слота из Settings.Global; нет значения → "none" (не перехватываем).
    function action(slot) {
        try {
            var v = SettingsGlobal.getString(ctx().getContentResolver(), "voyahtune_" + slot);
            return (v === null) ? "none" : v.toString();
        } catch (e) { return "none"; }
    }

    // Маршрут медиа-кнопок. Решение принимает Native (NowPlayingService видит активную медиа-сессию через
    // MediaSessionManager+MEDIA_CONTENT_CONTROL — у keymanager этой привилегии нет) и кладёт в Settings.Global:
    //   "dispatch" → сторонний плеер (Яндекс/Spotify/…): перехватываем и шлём медиа-эвент сами;
    //   иначе ("native"/нет ключа/старт/ошибка) → отдаём штатной маршрутизации прошивки (BT/AVRCP, штатный
    //   плеер и его прокси). Дефолт — "native" (заводское поведение), чтобы не ломать штатные кнопки.
    function mediaRoute() {
        try {
            var v = SettingsGlobal.getString(ctx().getContentResolver(), "voyahtune_mediaRoute");
            return (v === null) ? "native" : v.toString();
        } catch (e) { return "native"; }
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
        try {
            var am = Java.cast(ctx().getSystemService("audio"), AudioManager);
            am.dispatchMediaKeyEvent(KeyEvent.$new(0, kc));   // ACTION_DOWN
            am.dispatchMediaKeyEvent(KeyEvent.$new(1, kc));   // ACTION_UP
            console.log("[swk] media dispatch kc=" + kc);
        } catch (e) { console.log("[swk] media dispatch err: " + e); }
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
            },
            up: function () {
                if (timer !== null) { clearTimeout(timer); timer = null; }
                if (!longFired) doAction(action(shortSlot));   // короткое — на отпускании, если долгого не было
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

        Reader.onKeyEvent.implementation = function (ke) {
            var code = ke.getKeyCode();
            // Медиа-кнопки: роутим по решению Native (voyahtune_mediaRoute). "dispatch" (сторонний плеер) →
            // на DOWN шлём стандартный медиа-эвент и гасим штатную маршрутизацию. Иначе — passthrough в
            // штатную маршрутизацию прошивки (BT/AVRCP/штатный плеер/старт/по умолчанию), чтобы не ломать
            // кнопки, которые работали до установки.
            if (MEDIA_MAP.hasOwnProperty(code)) {
                if (mediaRoute() === "dispatch") {
                    if (ke.getAction() == 0 && ke.getRepeatCount() == 0) dispatchMedia(MEDIA_MAP[code]);
                    return true;
                }
                return this.onKeyEvent(ke);   // native passthrough
            }
            if (BUTTON_MAP.hasOwnProperty(code)) {
                var actionCode = BUTTON_MAP[code];
                var h = HANDLER_MAP[actionCode];
                // Кнопки-действия (звезда/DVR) — таймерное короткое/долгое.
                if (h === null) return this.onKeyEvent(ke);        // не наша кнопка → штатно
                if (h.passthrough()) return this.onKeyEvent(ke);   // не настроено → штатно
                if (ke.getAction() == 0) h.down(ke.getRepeatCount());
                else if (ke.getAction() == 1) h.up();
            }
            return true;                                        // гасим штатное действие кнопки
        };
        console.log("[swk] keymanager hooks installed: STAR DVR VOICE PHONE media=3/4/6 (LONG_MS=" + LONG_MS + ")");
    } catch (e) {
        // Если класс не найден (скрипт заинжектили не в keymanager) — просто ничего не делаем.
        console.log("[swk] KeyManagerReader not found (not keymanager?): " + e);
    }

});
