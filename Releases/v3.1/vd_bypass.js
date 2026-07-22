// Frida-хук в system_server: разрешает нашему приложению (ru.big.town.anative) создавать trusted
// VirtualDisplay и инжектить касания — единственный путь VD-хостинга на release-keys голове
// (ADD_TRUSTED_DISPLAY/INJECT_EVENTS — чистый signature, whitelist их не выдаёт).
//
// ВАЖНО: хукаем ТОЧЕЧНЫЕ РЕДКИЕ методы, НЕ общий checkComponentPermission (тот на горячем пути —
// тысячи вызовов/сек — и роняет watchdog system_server). Плюс инжектить нужно через -e (eternalize):
// приаттаченный frida-inject держит ptrace на system_server и тоже его дестабилизирует.
//   1) InputManagerService.checkInjectEventsPermission — только при инъекции ввода;
//   2) DisplayManagerService$BinderService.checkCallingPermission — при создании VirtualDisplay (trusted);
//   3) ActivityStackSupervisor.isCallerAllowedToLaunchOnDisplay — при запуске активити на дисплей;
//   4) ActivityRecord.canBeLaunchedOnDisplay — совместимость «капризных» приложений на вторичном дисплее;
//   5) PackageManagerService.hasSystemFeature — фич-флаг activities_on_secondary_displays = true.
// uid резолвится динамически по имени пакета (устойчиво к переустановке).
Java.perform(function () {
    var OUR_PKG = "ru.big.town.anative";
    var Log = Java.use("android.util.Log");
    var Binder = Java.use("android.os.Binder");
    var ourUid = -1;
    try {
        ourUid = Java.use("android.app.ActivityThread").currentActivityThread()
                 .getSystemContext().getPackageManager().getPackageUid(OUR_PKG, 0);
    } catch (e) {
        ourUid = 10060;
    }
    var installed = [];

    // 1) INJECT_EVENTS — редко (только при инъекции ввода из SplitHostActivity)
    try {
        var IMS = Java.use("com.android.server.input.InputManagerService");
        IMS.checkInjectEventsPermission.implementation = function (pid, uid) {
            if (uid === ourUid) return true;
            return this.checkInjectEventsPermission(pid, uid);
        };
        installed.push("IMS.checkInjectEventsPermission");
    } catch (e) {
        Log.e("VDBYPASS", "IMS hook fail: " + e);
    }

    // 2) ADD_TRUSTED_DISPLAY / INTERNAL_SYSTEM_WINDOW — редко (только при createVirtualDisplay и т.п.)
    try {
        var BS = Java.use("com.android.server.display.DisplayManagerService$BinderService");
        BS.checkCallingPermission.overload('java.lang.String', 'java.lang.String').implementation = function (permission, func) {
            if ((permission === "android.permission.ADD_TRUSTED_DISPLAY"
                 || permission === "android.permission.INTERNAL_SYSTEM_WINDOW")
                && Binder.getCallingUid() === ourUid) {
                return true;
            }
            return this.checkCallingPermission(permission, func);
        };
        installed.push("BinderService.checkCallingPermission");
    } catch (e) {
        Log.e("VDBYPASS", "DMS hook fail: " + e);
    }

    // 3) запуск активити на нашем VirtualDisplay — редко (только при старте активити на дисплей)
    try {
        var ASS = Java.use("com.android.server.wm.ActivityStackSupervisor");
        ASS.isCallerAllowedToLaunchOnDisplay.implementation = function (pid, uid, displayId, aInfo) {
            if (uid === ourUid) return true;
            return this.isCallerAllowedToLaunchOnDisplay(pid, uid, displayId, aInfo);
        };
        installed.push("ASS.isCallerAllowedToLaunchOnDisplay");
    } catch (e) {
        Log.e("VDBYPASS", "ASS hook fail: " + e);
    }

    // 4) Совместимость «капризных» приложений на вторичных дисплеях (наш VD): разрешаем запуск
    //    активити, которые сами не заявляют resizeable/мультидисплей. Только displayId != 0 —
    //    первичный дисплей не трогаем (там оставляем штатную логику). Редко (при запуске активити).
    try {
        var AR = Java.use("com.android.server.wm.ActivityRecord");
        AR.canBeLaunchedOnDisplay.implementation = function (displayId) {
            if (displayId !== 0) return true;
            return this.canBeLaunchedOnDisplay(displayId);
        };
        installed.push("ActivityRecord.canBeLaunchedOnDisplay");
    } catch (e) {
        Log.e("VDBYPASS", "AR.canBeLaunchedOnDisplay hook fail: " + e);
    }

    // 5) Системный фич-флаг «активити на вторичных дисплеях» — часть проверок мультиоконности
    //    опирается на него. Возвращаем true ТОЛЬКО для этой строки, всё остальное — как было
    //    (дешёвое сравнение строки, не горячий путь).
    try {
        var PMS = Java.use("com.android.server.pm.PackageManagerService");
        PMS.hasSystemFeature.overload('java.lang.String', 'int').implementation = function (name, version) {
            if (name === "android.software.activities_on_secondary_displays") return true;
            return this.hasSystemFeature(name, version);
        };
        installed.push("PMS.hasSystemFeature(secondary_displays)");
    } catch (e) {
        Log.e("VDBYPASS", "PMS.hasSystemFeature hook fail: " + e);
    }

    // ============================================================================================
    // 6-7) «ФЕЙК-FREEFORM»: не-системные окна на ФИЗИЧЕСКИХ экранах (display 0 =
    //      водитель, display 1 = пассажир) ужимаются в Rect (справа от дока, ниже статус-бара) + кастомный
    //      DPI. Даёт поведение «приложение в окне внутри рамок лаунчера» (док подсвечивает, Home не
    //      появляется) для ЛЮБОГО запуска — WM ловит окно независимо от источника (док/список/интент). Наш
    //      VD (сплит двух приложений) и прочие дисплеи НЕ трогаются. Пассажирский док НЕ переопределяем.
    //  ⚠️ ЭТО ГОРЯЧИЙ ПУТЬ И SYSTEM_SERVER. Требования безопасности:
    //   • ВСЕГДА ВКЛючено для сторонних приложений (это штатный режим запуска: одиночное приложение →
    //     окно справа от дока; сплит двух приложений остаётся на VD). Флаг voyahtune_freeform по умолчанию
    //     1; служит АВАРИЙНЫМ ВЫКЛЮЧАТЕЛЕМ через adb (`settings put global voyahtune_freeform 0` +
    //     WIN_RELOAD) на случай проблемной прошивки. Проверяется ПЕРВЫМ, fast-path без рефлексии.
    //   • Конфиг КЭШируется (в layoutWindowLw НЕТ чтений Settings.Global), обновляется по broadcast
    //     ru.big.town.anative.WIN_RELOAD.
    //   • ВЕСЬ код хука в try/catch → при ЛЮБОЙ ошибке (в т.ч. неверные имена приватных полей WM на
    //     другой прошивке) молча отдаём штатное поведение + латчим FF.on=false. WM НЕ падает.
    //  Ключи: voyahtune_freeform(0/1, деф 1), voyahtune_win_left/top/right/bottom(int,145/45/1920/720 — для обоих
    //         физических экранов, они идентичны), voyahtune_dpi_<pkg>(int, 0=не трогать).
    //  РАЗВЕДКА перед включением флага: подтвердить поля WindowFrames
    //  (mStableFrame/mParentFrame/mDisplayFrame/mContentFrame/mVisibleFrame/mDecorFrame),
    //  DisplayFrames.mStable, поле ActivityRecord.task/packageName, сигнатуры layoutWindowLw/ensureActivityConfiguration.
    // ============================================================================================
    var FF = { on: true, left: 145, top: 45, right: 1920, bottom: 720, dpi: {} };
    var Rect = Java.use('android.graphics.Rect');
    var SettingsGlobal = Java.use('android.provider.Settings$Global');
    var ATh = Java.use('android.app.ActivityThread');

    function ffCr() {
        try { return ATh.currentActivityThread().getSystemContext().getContentResolver(); } catch (e) { return null; }
    }
    function ffInt(cr, key, def) {
        try { var v = SettingsGlobal.getString(cr, key); var n = parseInt(v, 10); return isNaN(n) ? def : n; }
        catch (e) { return def; }
    }
    function refreshFreeformCfg() {
        try {
            var cr = ffCr(); if (cr === null) return;
            FF.on     = ffInt(cr, "voyahtune_freeform", 1) === 1;   // деф 1: always-on, 0 = аварийно выкл через adb
            FF.left   = ffInt(cr, "voyahtune_win_left", 145);
            FF.top    = ffInt(cr, "voyahtune_win_top", 45);
            FF.right  = ffInt(cr, "voyahtune_win_right", 1920);
            FF.bottom = ffInt(cr, "voyahtune_win_bottom", 720);
            FF.dpi = {};   // сбросить кэш per-app DPI
            Log.i("VDBYPASS", "freeform cfg on=" + FF.on + " rect=" + FF.left + "," + FF.top + "," + FF.right + "," + FF.bottom);
        } catch (e) { Log.e("VDBYPASS", "refreshFreeformCfg: " + e); }
    }
    // Блэклист системных пакетов + наши ru.big.town.*. settings/documentsui — исключения.
    function ffBlacklisted(pkg) {
        if (!pkg) return true;
        if (pkg.indexOf("ru.big.town") === 0) return true;
        if (pkg === "com.android.settings" || pkg === "com.android.documentsui") return false;
        var P = ["com.android", "com.qinggan", "com.pateo", "com.baidu", "com.huawei", "com.iflytek",
                 "com.iland", "com.mega", "com.qti", "com.qualcomm", "com.tencent", "com.nng.igo.primong", "com.bz.CA08"];
        for (var i = 0; i < P.length; i++) if (pkg.indexOf(P[i]) === 0) return true;
        return false;
    }
    function ffDpiFor(pkg) {
        var d = FF.dpi[pkg];
        if (typeof d !== "number") { d = ffInt(ffCr(), "voyahtune_dpi_" + pkg, 0); FF.dpi[pkg] = d; }
        return d;
    }

    refreshFreeformCfg();

    // reload-ресивер: Native шлёт WIN_RELOAD при смене флага/bounds/DPI → перечитать кэш.
    try {
        // ВАЖНО: BroadcastReceiver.onReceive — АБСТРАКТНЫЙ метод. Shorthand-форма
        // (methods:{onReceive:function(){}}) на этой прошивке НЕ переопределяет абстрактный слот vtable →
        // AbstractMethodError при доставке брэдкаста → КРЭШ system_server (soft-reboot всей системы!).
        // Объявляем метод с ЯВНОЙ сигнатурой (returnType/argumentTypes) — гарантирует конкретный override.
        var WinReceiver = Java.registerClass({
            name: "ru.big.town.vd.WinReloadReceiver",
            superClass: Java.use("android.content.BroadcastReceiver"),
            methods: {
                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: function (c, i) { refreshFreeformCfg(); }
                }
            }
        });
        var IF = Java.use("android.content.IntentFilter");
        var sctx = ATh.currentActivityThread().getSystemContext();
        // Пермишен-гейт: WIN_RELOAD примем ТОЛЬКО от держателя WRITE_SECURE_SETTINGS (наш Native), чтобы
        // любое приложение не могло спамить перечитку конфига в system_server.
        sctx.registerReceiver.overload('android.content.BroadcastReceiver', 'android.content.IntentFilter', 'java.lang.String', 'android.os.Handler')
            .call(sctx, WinReceiver.$new(), IF.$new("ru.big.town.anative.WIN_RELOAD"), "android.permission.WRITE_SECURE_SETTINGS", null);
        installed.push("WIN_RELOAD receiver");
    } catch (e) { Log.e("VDBYPASS", "WIN_RELOAD receiver fail: " + e); }

    // 6) Ресайз не-системного окна на ФИЗИЧЕСКИХ экранах (display 0 = водитель, display 1 = пассажир) в Rect
    //    (фейк-freeform). Наш VD/прочие дисплеи не трогаем. Горячий путь → fast-path по флагу.
    try {
        var DP = Java.use("com.android.server.wm.DisplayPolicy");
        DP.layoutWindowLw.implementation = function (win, attached, displayFrames) {
            this.layoutWindowLw(win, attached, displayFrames);   // оригинал раскладывает окно
            if (!FF.on) return;                                   // fast-path: фича выключена
            try {
                var pkg = win.getOwningPackage();
                if (ffBlacklisted(pkg)) return;
                var displayId = win.getDisplayContent().getDisplayId();
                if (displayId !== 0 && displayId !== 1) return;   // только два ФИЗИЧЕСКИХ экрана (не наш VD/прочие)
                var wt = win.getAttrs().type.value;
                if (wt === 2011 || wt === 2012 || wt === 2038 || wt === 2032) return;  // статус/навбар/оверлеи
                if (win.getWindowingMode() == 5) return;                    // настоящий freeform не трогаем
                displayFrames = win.getDisplayFrames(displayFrames);
                // Пассажирский экран (display 1) на этой голове ПОЛНОСТЬЮ идентичен главному (1920×720, док
                // 145dp) → те же bounds, что и display 0. Guard выше уже пропускает оба физических экрана.
                var b = Rect.$new(FF.left, FF.top, FF.right, FF.bottom);
                var wf = win.getWindowFrames();
                displayFrames.mStable.value = b;
                wf.mStableFrame.value = b;  wf.mParentFrame.value = b;  wf.mDisplayFrame.value = b;
                wf.mContentFrame.value = b; wf.mVisibleFrame.value = b; wf.mDecorFrame.value = b;
                win.computeFrame(displayFrames);
            } catch (e) {
                // Несовместимая прошивка (нет поля/метода WindowFrames) → НАВСЕГДА выключаем фичу, чтобы не
                // мутировать рамку частично на каждый layout-pass. WM жив (оригинал уже отработал выше).
                FF.on = false;
                Log.e("VDBYPASS", "freeform layout disabled (incompatible WM): " + e);
            }
        };
        installed.push("DisplayPolicy.layoutWindowLw(fake-freeform)");
    } catch (e) { Log.e("VDBYPASS", "layoutWindowLw hook fail: " + e); }

    // 7) Кастомный DPI не-системному приложению (voyahtune_dpi_<pkg>). Тоже opt-in + try/catch.
    try {
        var ARc = Java.use("com.android.server.wm.ActivityRecord");
        var Task = Java.use("com.android.server.wm.Task");
        ARc.ensureActivityConfiguration.overload('int', 'boolean', 'boolean').implementation = function (g, p, iv) {
            var result = this.ensureActivityConfiguration(g, p, iv);
            if (!FF.on) return result;
            try {
                var pkg = this.packageName.value;
                if (ffBlacklisted(pkg)) return result;
                var dpi = ffDpiFor(pkg);
                if (!(dpi > 0)) return result;   // DPI не задан/невалиден (в т.ч. undefined из гонки кэша) → не трогаем
                var taskF = this.getClass().getDeclaredField("task");
                taskF.setAccessible(true);
                var task = Java.cast(taskF.get(this), Task);
                var tc = task.getRequestedOverrideConfiguration();
                tc.setTo(task.getConfiguration());
                tc.densityDpi.value = dpi; tc.orientation.value = 2;
                task.onRequestedOverrideConfigurationChanged(tc);
                var ac = this.getRequestedOverrideConfiguration();
                ac.setTo(this.getConfiguration());
                ac.densityDpi.value = dpi; ac.orientation.value = 2;
                this.onConfigurationChanged(ac);
            } catch (e) { /* не роняем WM */ }
            return result;
        };
        installed.push("ActivityRecord.ensureActivityConfiguration(dpi)");
    } catch (e) { Log.e("VDBYPASS", "ensureActivityConfiguration hook fail: " + e); }

    // Маркер пишет load.bin (root), а не мы: система (uid system) не может писать в /data/local/tmp (EACCES).
    Log.i("VDBYPASS", "hooks installed [" + installed.join(", ") + "] uid=" + ourUid);
});
