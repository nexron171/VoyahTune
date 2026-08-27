// Frida-хук в system_server: разрешает нашему приложению (ru.big.town.anative) создавать trusted
// VirtualDisplay и инжектить касания — путь двухпанельного VD-hosting на release-keys голове
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
    var TAG = "vt_vdbypass";
    var OUR_PKG = "ru.big.town.anative";
    // The OEM Android 11 launcher expects ordinary physical-display tasks whose frames are clamped
    // by the two WindowManager hooks below. This is intentionally global for all non-stock apps:
    // launch source does not matter (Dock, VoyahTune, steering action or another intent).
    var SYSTEM_SERVER_FREEFORM_HOT_HOOKS = true;
    var Log = Java.use("android.util.Log");
    var Binder = Java.use("android.os.Binder");
    var ourUid = -1;
    try {
        ourUid = Java.use("android.app.ActivityThread").currentActivityThread()
                 .getSystemContext().getPackageManager().getPackageUid(OUR_PKG, 0);
    } catch (e) {
        // Fail closed: фиксированный UID после переустановки может принадлежать совсем другому пакету.
        // При -1 все UID-scoped bypass ниже штатно откажут, вместо выдачи системных прав постороннему app.
        ourUid = -1;
        Log.e(TAG, "package uid resolve failed; privileged UID hooks disabled: " + e);
    }
    var installed = [];

    // 1) INJECT_EVENTS — редко (только при инъекции ввода из SplitHostActivity)
    try {
        var IMS = Java.use("com.android.server.input.InputManagerService");
        IMS.checkInjectEventsPermission.implementation = function (pid, uid) {
            if (ourUid >= 0 && uid === ourUid) return true;
            return this.checkInjectEventsPermission(pid, uid);
        };
        installed.push("IMS.checkInjectEventsPermission");
    } catch (e) {
        Log.e(TAG, "IMS hook fail: " + e);
    }

    // 2) ADD_TRUSTED_DISPLAY / INTERNAL_SYSTEM_WINDOW — редко (только при createVirtualDisplay и т.п.)
    try {
        var BS = Java.use("com.android.server.display.DisplayManagerService$BinderService");
        BS.checkCallingPermission.overload('java.lang.String', 'java.lang.String').implementation = function (permission, func) {
            if ((permission === "android.permission.ADD_TRUSTED_DISPLAY"
                 || permission === "android.permission.INTERNAL_SYSTEM_WINDOW")
                && ourUid >= 0 && Binder.getCallingUid() === ourUid) {
                return true;
            }
            return this.checkCallingPermission(permission, func);
        };
        installed.push("BinderService.checkCallingPermission");
    } catch (e) {
        Log.e(TAG, "DMS hook fail: " + e);
    }

    // 3) запуск активити на нашем VirtualDisplay — редко (только при старте активити на дисплей)
    try {
        var ASS = Java.use("com.android.server.wm.ActivityStackSupervisor");
        ASS.isCallerAllowedToLaunchOnDisplay.implementation = function (pid, uid, displayId, aInfo) {
            if (ourUid >= 0 && uid === ourUid) return true;
            return this.isCallerAllowedToLaunchOnDisplay(pid, uid, displayId, aInfo);
        };
        installed.push("ASS.isCallerAllowedToLaunchOnDisplay");
    } catch (e) {
        Log.e(TAG, "ASS hook fail: " + e);
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
        Log.e(TAG, "AR.canBeLaunchedOnDisplay hook fail: " + e);
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
        Log.e(TAG, "PMS.hasSystemFeature hook fail: " + e);
    }

    // ============================================================================================
    // 6-7) «ФЕЙК-FREEFORM»: не-системные окна на ФИЗИЧЕСКИХ экранах (display 0 =
    //      водитель, display 1 = пассажир) ужимаются в Rect (справа от дока, ниже статус-бара) + кастомный
    //      DPI. Даёт поведение «приложение в окне внутри рамок лаунчера» (док подсвечивает, Home не
    //      появляется) для ЛЮБОГО запуска — WM ловит окно независимо от источника (док/список/интент). Наш
    //      VD (сплит двух приложений) и прочие дисплеи НЕ трогаются. Пассажирский док НЕ переопределяем.
    //  ⚠️ ЭТО ГОРЯЧИЙ ПУТЬ И SYSTEM_SERVER. Требования безопасности:
    //   • ВСЕГДА ВКЛючено для сторонних приложений (штатный одиночный режим: обычная задача целевого
    //     пакета на физическом display, затем WindowManager ужимает её справа от дока). Флаг
    //     voyahtune_freeform по умолчанию 1 остаётся аварийным выключателем через WIN_RELOAD.
    //   • Конфиг КЭШируется (в layoutWindowLw НЕТ чтений Settings.Global), обновляется по broadcast
    //     ru.big.town.anative.WIN_RELOAD.
    //   • ВЕСЬ код хука в try/catch → при ЛЮБОЙ ошибке (в т.ч. неверные имена приватных полей WM на
    //     другой прошивке) молча отдаём штатное поведение + латчим FF.on=false. WM НЕ падает.
    //  Ключи: voyahtune_freeform(0/1, деф 1), voyahtune_win_left/top/right/bottom
    //  (int,145/45/1920/720), voyahtune_win_compact_bottom (int, деф 560),
    //  voyahtune_dpi_<pkg> (int, 0=не трогать).
    //  РАЗВЕДКА перед включением флага: подтвердить поля WindowFrames
    //  (mStableFrame/mParentFrame/mDisplayFrame/mContentFrame/mVisibleFrame/mDecorFrame),
    //  DisplayFrames.mStable, поле ActivityRecord.task/packageName, сигнатуры layoutWindowLw/ensureActivityConfiguration.
    // ============================================================================================
    // На SCREEN_OFF полностью снимаем два hot replacements. Поэтому sleep/wake storm до отложенного
    // reattach вообще не пересекает Java<->Frida bridge, а не просто делает JS fast-path.
    var FF = { on: true, screenOn: true, hookEpoch: 0,
               lastScreenTransitionAt: 0, rapidScreenTransitions: 0,
               left: 145, top: 45, right: 1920, bottom: 720, compactBottom: 560,
               liftType: 2, dpi: {} };
    var SettingsGlobal = Java.use('android.provider.Settings$Global');
    var ATh = Java.use('android.app.ActivityThread');
    var ffLayoutMethod = null, ffLayoutImplementation = null, ffLayoutAttached = false;
    var ffConfigMethod = null, ffConfigImplementation = null, ffConfigAttached = false;
    var ffTraversalService = null, ffTraversalMethod = null, ffTraversalWarned = false;
    var ffTaskClass = null, ffConfigurationClass = null, ffTaskField = null;
    var ffDisplayChangedMethod = null, ffDisplayChangedApplying = false;
    var ffDisplayChangedWarned = false;

    function ffCr() {
        try { return ATh.currentActivityThread().getSystemContext().getContentResolver(); } catch (e) { return null; }
    }
    function ffInt(cr, key, def) {
        try { var v = SettingsGlobal.getString(cr, key); var n = parseInt(v, 10); return isNaN(n) ? def : n; }
        catch (e) { return def; }
    }
    function readScreenLiftType() {
        try {
            var SystemProperties = Java.use("android.os.SystemProperties");
            var type = SystemProperties.getInt("persist.qg.canbus.bcm_screenAutoLiftFdb", 2);
            if (type === 1 || type === 2) return type;
        } catch (e) {}
        return ffInt(ffCr(), "voyahtune_screen_lift_type", 2);
    }
    function ffBottom() {
        return FF.liftType === 1 ? FF.compactBottom : FF.bottom;
    }
    function refreshFreeformCfg() {
        try {
            var cr = ffCr(); if (cr === null) return;
            FF.on     = ffInt(cr, "voyahtune_freeform", 1) === 1;   // деф 1: always-on, 0 = аварийно выкл через adb
            FF.left   = ffInt(cr, "voyahtune_win_left", 145);
            FF.top    = ffInt(cr, "voyahtune_win_top", 45);
            FF.right  = ffInt(cr, "voyahtune_win_right", 1920);
            FF.bottom = ffInt(cr, "voyahtune_win_bottom", 720);
            FF.compactBottom = ffInt(cr, "voyahtune_win_compact_bottom", 560);
            FF.liftType = readScreenLiftType();
            FF.dpi = {};   // сбросить кэш per-app DPI
            Log.i(TAG, "freeform cfg on=" + FF.on + " liftType=" + FF.liftType + " rect="
                    + FF.left + "," + FF.top + "," + FF.right + "," + ffBottom());
        } catch (e) { Log.e(TAG, "refreshFreeformCfg: " + e); }
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

    // Keep the task requested override minimal. In particular, never copy the resolved bounds,
    // appBounds or dp sizes here: those belong to the destination display and copying them would
    // freeze an intermediate size after a reparent/VD resize. Physical viewport bounds are applied
    // by layoutWindowLw below; the only persistent per-task request is the explicitly configured DPI.
    function ffApplyTaskDpi(taskObject, pkg) {
        if (ffTaskClass === null || ffConfigurationClass === null || taskObject === null) return false;
        var dpi = ffDpiFor(pkg);
        if (!(dpi > 0)) return false;
        var task = Java.cast(taskObject, ffTaskClass);
        var current = task.getRequestedOverrideConfiguration();
        if (current.densityDpi.value === dpi) return false;
        var requested = ffConfigurationClass.$new(current);
        requested.densityDpi.value = dpi;
        task.onRequestedOverrideConfigurationChanged(requested);
        return true;
    }

    // One-shot replay after delayed reattach. WindowManagerInternal's implementation takes
    // mGlobalLock itself and only schedules a traversal; unlike a global Configuration update,
    // this is safe and bounded during wake. Task requested density overrides survive sleep.
    function resolveFreeformTraversalRequester() {
        try {
            var LocalServices = Java.use("com.android.server.LocalServices");
            var names = [
                "com.android.server.wm.WindowManagerInternal", // Android 10+
                "android.view.WindowManagerInternal"           // older vendor branches
            ];
            for (var i = 0; i < names.length; i++) {
                try {
                    var Wmi = Java.use(names[i]);
                    var service = LocalServices.getService(Wmi.class);
                    if (service === null) continue;
                    var method = Wmi.requestTraversalFromDisplayManager.overload();
                    ffTraversalService = Java.retain(Java.cast(service, Wmi));
                    ffTraversalMethod = method;
                    return;
                } catch (ignored) {}
            }
        } catch (e) {
            Log.w(TAG, "WindowManagerInternal lookup failed: " + e);
        }
    }

    function requestFreeformTraversalOnce(reason) {
        // Injection can happen before WMS publishes its LocalService; retry lazily at reattach.
        if (ffTraversalService === null || ffTraversalMethod === null) {
            resolveFreeformTraversalRequester();
        }
        if (ffTraversalService === null || ffTraversalMethod === null) {
            if (!ffTraversalWarned) {
                ffTraversalWarned = true;
                Log.w(TAG, "freeform traversal replay unavailable");
            }
            return;
        }
        try {
            ffTraversalMethod.call(ffTraversalService);
            Log.i(TAG, "freeform traversal requested: " + reason);
        } catch (e) {
            if (!ffTraversalWarned) {
                ffTraversalWarned = true;
                Log.w(TAG, "freeform traversal request failed: " + e);
            }
        }
    }

    // Разовая заметка о ПРОПУЩЕННОМ окне (диагностика). layoutWindowLw — горячий путь, поэтому пишем
    // не чаще одного раза на комбинацию pkg+экран+режим и не больше 20 записей за жизнь процесса.
    // Нужна, чтобы понять, в каком windowing mode оказывается приложение после переноса между экранами
    // системным жестом: если наш кламп его пропускает, окно занимает весь экран и закрывает док.
    var ffSeen = {}, ffSeenN = 0;
    function ffNote(why, pkg, displayId, mode) {
        if (ffSeenN >= 20) return;
        var k = why + "|" + pkg + "|" + displayId + "|" + mode;
        if (ffSeen[k]) return;
        ffSeen[k] = 1; ffSeenN++;
        Log.i(TAG, "ff " + why + " pkg=" + pkg + " display=" + displayId + " mode=" + mode);
    }

    refreshFreeformCfg();
    resolveFreeformTraversalRequester();
    if (SYSTEM_SERVER_FREEFORM_HOT_HOOKS) {
        installed.push("system_server freeform hot hooks enabled");
    }

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
                    implementation: function (c, i) {
                        refreshFreeformCfg();
                        if (!FF.on) {
                            ++FF.hookEpoch;
                            detachFreeformHotHooks("config off");
                        } else if (FF.screenOn) {
                            scheduleFreeformHotAttach(0, "config reload");
                        }
                    }
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
    } catch (e) { Log.e(TAG, "WIN_RELOAD receiver fail: " + e); }

    // The logical displays stay 1920x720 while the dashboard is physically lowered. Apply the
    // 560px viewport immediately on the OEM completion broadcast and replay WM traversal so every
    // already visible third-party task receives new frames without being relaunched.
    try {
        var LiftReceiver = Java.registerClass({
            name: "ru.big.town.vd.ScreenLiftReceiver",
            superClass: Java.use("android.content.BroadcastReceiver"),
            methods: {
                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: function (c, i) {
                        try {
                            var type = i.getIntExtra("type", 2);
                            var actualType = readScreenLiftType();
                            if (type !== actualType) {
                                Log.w(TAG, "screen lift broadcast ignored: type=" + type
                                        + " property=" + actualType);
                                return;
                            }
                            FF.liftType = type === 1 ? 1 : 2;
                            Log.i(TAG, "screen lift changed type=" + FF.liftType
                                    + " effectiveBottom=" + ffBottom());
                            requestFreeformTraversalOnce("screen lift type=" + FF.liftType);
                        } catch (e) { Log.e(TAG, "screen lift receiver: " + e); }
                    }
                }
            }
        });
        var liftFilter = Java.use("android.content.IntentFilter").$new("action.qg.layout.changed");
        var liftCtx = ATh.currentActivityThread().getSystemContext();
        liftCtx.registerReceiver.overload('android.content.BroadcastReceiver', 'android.content.IntentFilter')
            .call(liftCtx, LiftReceiver.$new(), liftFilter);
        installed.push("screen-lift bounds receiver");
    } catch (e) { Log.e(TAG, "screen-lift receiver fail: " + e); }

    // 6) Ресайз не-системного окна на ФИЗИЧЕСКИХ экранах (display 0 = водитель, display 1 = пассажир) в Rect
    //    (фейк-freeform). Наш VD/прочие дисплеи не трогаем. Горячий путь → fast-path по флагу.
    try {
        var DP = Java.use("com.android.server.wm.DisplayPolicy");
        ffLayoutMethod = DP.layoutWindowLw;
        ffLayoutImplementation = function (win, attached, displayFrames) {
            ffLayoutMethod.call(this, win, attached, displayFrames); // оригинал раскладывает окно
            if (!FF.on) return;
            try {
                var pkg = win.getOwningPackage();
                if (ffBlacklisted(pkg)) return;
                var dc = win.getDisplayContent();
                if (!dc) return;                                  // окно без displayContent (транзиентное) — пропуск
                var displayId = dc.getDisplayId();
                if (displayId !== 0 && displayId !== 1) return;   // только два ФИЗИЧЕСКИХ экрана (не наш VD/прочие)
                var wt = win.getAttrs().type.value;
                if (wt === 2011 || wt === 2012 || wt === 2038 || wt === 2032) return;  // статус/навбар/оверлеи
                var wmode = win.getWindowingMode();
                if (wmode == 5) { ffNote("skip-freeform", pkg, displayId, wmode); return; }  // настоящий freeform не трогаем
                var df = win.getDisplayFrames(displayFrames);
                var wf = win.getWindowFrames();
                if (!df || !wf) return;                           // нечего мутировать — чистый пропуск (без порчи рамки)
                // Оба физических экрана логически 1920×720 с доком 145 px; при опущенной панели
                // effective bottom становится 560. Guard выше уже пропускает оба display.
                // DisplayFrames принадлежит всему display/layout-проходу, а не только этому окну.
                // Раньше мы заменяли mStable и оставляли уменьшенный Rect там навсегда: следующие окна
                // (включая Launcher/док) могли получить геометрию стороннего приложения. Сохраняем
                // значение и обязательно возвращаем его после computeFrame; WindowFrames самого окна
                // остаются уменьшенными.
                var stable = df.mStable.value;
                var bottom = ffBottom();
                // Не создаём Rect на каждом layout: этот метод вызывается сотни раз на screen-on.
                var savedLeft = stable.left.value, savedTop = stable.top.value;
                var savedRight = stable.right.value, savedBottom = stable.bottom.value;
                try {
                    stable.set(FF.left, FF.top, FF.right, bottom);
                    wf.mStableFrame.value.set(FF.left, FF.top, FF.right, bottom);
                    wf.mParentFrame.value.set(FF.left, FF.top, FF.right, bottom);
                    wf.mDisplayFrame.value.set(FF.left, FF.top, FF.right, bottom);
                    wf.mContentFrame.value.set(FF.left, FF.top, FF.right, bottom);
                    wf.mVisibleFrame.value.set(FF.left, FF.top, FF.right, bottom);
                    wf.mDecorFrame.value.set(FF.left, FF.top, FF.right, bottom);
                    win.computeFrame(df);
                } finally {
                    stable.set(savedLeft, savedTop, savedRight, savedBottom);
                }
            } catch (e) {
                // Ошибка на КОНКРЕТНОМ окне (напр. нестандартное окно без ожидаемых полей WindowFrames) →
                // пропускаем ТОЛЬКО его. НЕ выключаем freeform глобально: раньше FF.on=false здесь убивал
                // окна ВСЕХ приложений из-за одного проблемного окна (freeform «ломался» до перезагрузки).
                // WM жив (оригинал уже отработал). Лог троттлим — один раз, чтобы не спамить каждый layout-pass.
                if (!FF._warned) { FF._warned = true; Log.e(TAG, "freeform layout skip (window, once): " + e); }
            }
        };
        installed.push("DisplayPolicy.layoutWindowLw(detachable-freeform)");
    } catch (e) { Log.e(TAG, "layoutWindowLw hook fail: " + e); }

    // 7) Кастомный DPI не-системному приложению на ФИЗИЧЕСКИХ дисплеях.
    //
    // VirtualDisplay сюда принципиально не попадает: его density задаёт createVirtualDisplay/resize.
    // Старый код делал tc.setTo(task.getConfiguration()), то есть записывал в REQUESTED override всю
    // уже разрешённую конфигурацию (bounds/appBounds/screenWidthDp/screenHeightDp). После resize VD это
    // «замораживало» размер task на одном из промежуточных значений: сам display и Surface уже росли,
    // а Activity продолжала рисовать узкий прямоугольник. На физических дисплеях также меняем только
    // одно действительно запрошенное поле — densityDpi; resolved Configuration копировать нельзя.
    try {
        var ARc = Java.use("com.android.server.wm.ActivityRecord");
        ffTaskClass = Java.use("com.android.server.wm.Task");
        ffConfigurationClass = Java.use("android.content.res.Configuration");
        // Поле одно и то же для всех ActivityRecord: reflection lookup на каждом config-pass не нужен.
        ffTaskField = ARc.class.getDeclaredField("task");
        ffTaskField.setAccessible(true);
        ffConfigMethod = ARc.ensureActivityConfiguration.overload('int', 'boolean', 'boolean');
        ffConfigImplementation = function (g, p, iv) {
            var result = ffConfigMethod.call(this, g, p, iv);
            if (!FF.on) return result;
            try {
                var displayId = this.getDisplayId();
                if (displayId !== 0 && displayId !== 1) return result;
                var pkg = this.packageName.value;
                if (ffBlacklisted(pkg)) return result;
                ffApplyTaskDpi(ffTaskField.get(this), pkg);
            } catch (e) { /* не роняем WM */ }
            return result;
        };
        installed.push("ActivityRecord.ensureActivityConfiguration(detachable-dpi)");
    } catch (e) { Log.e(TAG, "ensureActivityConfiguration hook fail: " + e); }

    // 8) A successful OEM swap reparents ActivityRecord to the other physical DisplayContent.
    // Run the stock move first, then request one ordinary WM traversal: layoutWindowLw will rebuild
    // this task's frames from the current raised/compact viewport. Re-apply only the requested DPI
    // here; writing mSizeCompatBounds or a resolved Configuration (as some older scripts do) can pin
    // stale source-display bounds and make the task impossible to resize.
    //
    // The guard is intentionally strict: only a real third-party task whose destination is physical
    // display 0/1. Any reflection/OEM mismatch is swallowed after the original method, and the depth
    // latch prevents our configuration update from recursively re-applying itself. Keep this rare
    // hook isolated from the detachable config hook so a firmware ABI mismatch cannot disable DPI.
    try {
        if (SYSTEM_SERVER_FREEFORM_HOT_HOOKS && ffTaskField !== null) {
            var ARd = Java.use("com.android.server.wm.ActivityRecord");
            ffDisplayChangedMethod = ARd.onDisplayChanged.overload(
                    'com.android.server.wm.DisplayContent');
            ffDisplayChangedMethod.implementation = function (displayContent) {
                ffDisplayChangedMethod.call(this, displayContent);
                if (!FF.on || !FF.screenOn || ffDisplayChangedApplying) return;
                try {
                    if (displayContent === null) return;
                    var displayId = displayContent.getDisplayId();
                    if (displayId !== 0 && displayId !== 1) return;
                    if (this.getDisplayId() !== displayId) return;
                    var pkg = this.packageName.value;
                    if (ffBlacklisted(pkg)) return;
                    var taskObject = ffTaskField.get(this);
                    if (taskObject === null) return;

                    ffDisplayChangedApplying = true;
                    try {
                        ffApplyTaskDpi(taskObject, pkg);
                        requestFreeformTraversalOnce("physical reparent pkg=" + pkg
                                + " display=" + displayId);
                    } finally {
                        ffDisplayChangedApplying = false;
                    }
                } catch (e) {
                    ffDisplayChangedApplying = false;
                    if (!ffDisplayChangedWarned) {
                        ffDisplayChangedWarned = true;
                        Log.w(TAG, "physical reparent replay skipped (once): " + e);
                    }
                }
            };
            installed.push("ActivityRecord.onDisplayChanged(physical-reparent-replay)");
        }
    } catch (e) { Log.e(TAG, "ActivityRecord.onDisplayChanged hook fail: " + e); }

    function detachFreeformHotHooks(reason) {
        var changed = false;
        if (ffLayoutAttached && ffLayoutMethod !== null) {
            try {
                ffLayoutMethod.implementation = null;
                ffLayoutAttached = false;
                changed = true;
            } catch (e) { Log.e(TAG, "layout detach fail: " + e); }
        }
        if (ffConfigAttached && ffConfigMethod !== null) {
            try {
                ffConfigMethod.implementation = null;
                ffConfigAttached = false;
                changed = true;
            } catch (e) { Log.e(TAG, "config detach fail: " + e); }
        }
        if (changed) Log.i(TAG, "freeform hot hooks DETACHED: " + reason);
    }

    function attachFreeformHotHooks(reason) {
        if (!SYSTEM_SERVER_FREEFORM_HOT_HOOKS) return;
        if (!FF.on || !FF.screenOn) return;
        var changed = false;
        if (!ffLayoutAttached && ffLayoutMethod !== null && ffLayoutImplementation !== null) {
            try {
                ffLayoutMethod.implementation = ffLayoutImplementation;
                ffLayoutAttached = true;
                changed = true;
            } catch (e) { Log.e(TAG, "layout attach fail: " + e); }
        }
        if (!ffConfigAttached && ffConfigMethod !== null && ffConfigImplementation !== null) {
            try {
                ffConfigMethod.implementation = ffConfigImplementation;
                ffConfigAttached = true;
                changed = true;
            } catch (e) { Log.e(TAG, "config attach fail: " + e); }
        }
        if (changed) {
            Log.i(TAG, "freeform hot hooks ATTACHED: " + reason);
            requestFreeformTraversalOnce(reason);
        }
    }

    function scheduleFreeformHotAttach(delayMs, reason) {
        FF.screenOn = true;
        var epoch = ++FF.hookEpoch;
        // Даже если SCREEN_OFF был пропущен, SCREEN_ON сначала снимает replacements синхронно.
        detachFreeformHotHooks(reason + " stabilization");
        if (!SYSTEM_SERVER_FREEFORM_HOT_HOOKS) return;
        setTimeout(function () {
            if (FF.hookEpoch === epoch && FF.screenOn && FF.on) {
                attachFreeformHotHooks(reason);
            }
        }, delayMs);
    }

    function noteFreeformScreenTransition() {
        var now = Date.now();
        if (FF.lastScreenTransitionAt > 0
                && now >= FF.lastScreenTransitionAt
                && now - FF.lastScreenTransitionAt < 30000) {
            FF.rapidScreenTransitions++;
        } else {
            FF.rapidScreenTransitions = 0;
        }
        FF.lastScreenTransitionAt = now;
        // Начиная со второго быстрого off/on держим hooks снятыми дольше. Это гасит именно
        // proximity-сценарий 5–7 циклов, но обычное одиночное пробуждение сохраняет задержку 1с.
        return FF.rapidScreenTransitions >= 2 ? 5000 : 1000;
    }

    function ffScreenIsInteractive() {
        try {
            var PowerManager = Java.use("android.os.PowerManager");
            var power = Java.cast(ATh.currentActivityThread().getSystemContext()
                    .getSystemService("power"), PowerManager);
            return power.isInteractive();
        } catch (e) {
            Log.w(TAG, "isInteractive unavailable, assume screen on: " + e);
            return true;
        }
    }

    try {
        var ScreenReceiver = Java.registerClass({
            name: "ru.big.town.vd.ScreenStateReceiver",
            superClass: Java.use("android.content.BroadcastReceiver"),
            methods: {
                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: function (c, i) {
                        var action = i.getAction();
                        if (action === "android.intent.action.SCREEN_OFF") {
                            noteFreeformScreenTransition();
                            FF.screenOn = false;
                            ++FF.hookEpoch; // отменить pending attach от предыдущего SCREEN_ON
                            detachFreeformHotHooks("SCREEN_OFF");
                        } else if (action === "android.intent.action.SCREEN_ON") {
                            var attachDelay = noteFreeformScreenTransition();
                            scheduleFreeformHotAttach(attachDelay,
                                    "SCREEN_ON +" + attachDelay + "ms");
                        }
                    }
                }
            }
        });
        var ScreenFilter = Java.use("android.content.IntentFilter");
        var sf = ScreenFilter.$new("android.intent.action.SCREEN_ON");
        sf.addAction("android.intent.action.SCREEN_OFF");
        ATh.currentActivityThread().getSystemContext().registerReceiver(ScreenReceiver.$new(), sf);
        installed.push("screen hot-hook attach/detach");
    } catch (e) { Log.e(TAG, "screen hot-hook controller fail: " + e); }

    FF.screenOn = ffScreenIsInteractive();
    if (FF.screenOn) {
        // Инъекция часто совпадает с boot/wake: тот же короткий стабилизационный интервал.
        scheduleFreeformHotAttach(1000, "initial +1s");
    } else {
        ++FF.hookEpoch;
        detachFreeformHotHooks("initial screen off");
    }

    // Маркер пишет load.bin (root), а не мы: система (uid system) не может писать в /data/local/tmp (EACCES).
    Log.i(TAG, "hooks installed [" + installed.join(", ") + "] uid=" + ourUid);
});
