// OD system_server hooks for Native-owned virtual displays and OEM physical-window layout.
// Use eternalized injection (-e); a persistent ptrace attachment can destabilize system_server.
// Keep permission overrides on specific operations, never general permission hot paths.
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
    var AGENT_VERSION = "3.22.0-v1";
    var STATUS_PATH = "/data/local/open_voyah/vd_hooks/status.v1";
    var preparationTimer = null, preparationAttempts = 0;
    var agentFailed = false, agentIdentity = "", lastAgentStatus = "";
    var replacements = [], receivers = [];
    var installed = [];
    var FF, ffConfigReplayTimer = null, ffHotAttachTimer = null;
    var ffReloadTimer = null, ffTraversalTimer = null;
    var ffHotAttachPending = false, ffHookState = "preparing";
    var restoreAgentWindows = null, agentWindowRollbackIncomplete = false;

    function cancelFreeformPending() {
        [ffConfigReplayTimer, ffHotAttachTimer, ffReloadTimer, ffTraversalTimer].forEach(function (timer) {
            if (timer !== null) clearTimeout(timer);
        });
        ffConfigReplayTimer = ffHotAttachTimer = ffReloadTimer = ffTraversalTimer = null;
        ffHotAttachPending = false;
        if (FF) ++FF.hookEpoch;
    }
    var ATh = Java.use("android.app.ActivityThread");
    var SystemClock = Java.use("android.os.SystemClock");
    var preparationDeadline = Number(SystemClock.elapsedRealtime()) + 20000;

    function readAgentFile(path) {
        var reader = Java.use("java.io.BufferedReader").$new(
                Java.use("java.io.FileReader").$new(path));
        try { return String(reader.readLine()); } finally { reader.close(); }
    }

    function publishAgentStatus(state, reason) {
        if (!agentIdentity) {
            var stat = readAgentFile("/proc/self/stat");
            var fields = stat.slice(stat.lastIndexOf(")") + 2).split(/\s+/);
            agentIdentity = "v2:" + readAgentFile("/proc/sys/kernel/random/boot_id")
                    + ":" + Java.use("android.os.Process").myPid() + ":" + fields[19];
        }
        var record = agentIdentity + "|" + AGENT_VERSION + "|" + state + "|"
                + String(reason).replace(/[^a-zA-Z0-9_.-]/g, "_").slice(0, 160);
        if (record === lastAgentStatus) return;
        var out = Java.use("java.io.FileWriter").$new(STATUS_PATH + ".new", false);
        try {
            var line = record + "\n";
            out.write.overload("java.lang.String", "int", "int").call(out, line, 0, line.length);
        } finally { out.close(); }
        var File = Java.use("java.io.File");
        if (!File.$new(STATUS_PATH + ".new").renameTo(File.$new(STATUS_PATH))) {
            throw new Error("VD status atomic rename failed");
        }
        lastAgentStatus = record;
        Log.i(TAG, "agent " + state + " reason=" + reason);
    }

    function trackReplacement(method) {
        if (replacements.indexOf(method) < 0) replacements.push(method);
    }

    function registerAgentReceiver(context, receiver, filter, permission) {
        // Record before registration: a throwing vendor implementation may have registered it.
        receivers.push({ context: context, receiver: receiver });
        if (permission) {
            context.registerReceiver.overload('android.content.BroadcastReceiver',
                    'android.content.IntentFilter', 'java.lang.String', 'android.os.Handler')
                    .call(context, receiver, filter, permission, null);
        } else {
            context.registerReceiver.overload('android.content.BroadcastReceiver',
                    'android.content.IntentFilter').call(context, receiver, filter);
        }
    }

    function failAgent(reason) {
        if (agentFailed) return;
        agentFailed = true;
        ffHookState = "error";
        if (preparationTimer !== null) clearTimeout(preparationTimer);
        cancelFreeformPending();
        if (FF) FF.on = false;
        var clean = !agentWindowRollbackIncomplete;
        if (restoreAgentWindows !== null) {
            try { if (!restoreAgentWindows()) clean = false; }
            catch (e) { clean = false; Log.e(TAG, "window rollback failed: " + e); }
        }
        for (var i = receivers.length - 1; i >= 0; --i) {
            try { receivers[i].context.unregisterReceiver(receivers[i].receiver); }
            catch (e) {
                if (String(e).indexOf("Receiver not registered") < 0) clean = false;
            }
        }
        for (var j = replacements.length - 1; j >= 0; --j) {
            try { replacements[j].implementation = null; } catch (e) { clean = false; }
        }
        try { publishAgentStatus("failed", (clean ? "clean." : "partial.") + reason); }
        catch (e) { Log.e(TAG, "agent status unavailable: " + e); }
        Log.e(TAG, "agent failed rollback=" + clean + " reason=" + reason);
    }

    function prepareAgent() {
        var thread = ATh.currentActivityThread();
        if (thread === null) throw new Error("ActivityThread pending");
        var context = thread.getSystemContext();
        if (context === null || context.getPackageManager() === null) {
            throw new Error("system context/PackageManager pending");
        }
        // Keep Frida's working loader. On the OEM ROM the
        // main thread's context loader can point at "." and cannot resolve services.jar classes.
        ourUid = context.getPackageManager().getPackageUid(OUR_PKG, 0);
        if (!(ourUid >= 0)) throw new Error("Native UID unavailable");
        var methods = [
            ["com.android.server.wm.ActivityTaskManagerService", "removeTask", ["int"]],
            ["com.android.server.input.InputManagerService", "checkInjectEventsPermission", ["int", "int"]],
            ["com.android.server.display.DisplayManagerService$BinderService", "checkCallingPermission", ["java.lang.String", "java.lang.String"]],
            ["com.android.server.wm.ActivityStackSupervisor", "isCallerAllowedToLaunchOnDisplay", ["int", "int", "int", "android.content.pm.ActivityInfo"]],
            ["com.android.server.wm.ActivityRecord", "canBeLaunchedOnDisplay", ["int"]],
            ["com.android.server.pm.PackageManagerService", "hasSystemFeature", ["java.lang.String", "int"]],
            ["com.android.server.wm.DisplayWindowSettings", "shouldShowImeLocked", ["com.android.server.wm.DisplayContent"]],
            ["com.android.server.wm.DisplayContent", "getDisplayInfo", []],
            ["com.android.server.wm.DisplayContent", "isTrusted", []],
            ["com.android.server.wm.DisplayPolicy", "layoutWindowLw", ["com.android.server.wm.WindowState", "com.android.server.wm.WindowState", "com.android.server.wm.DisplayFrames"]],
            ["com.android.server.wm.ActivityRecord", "ensureActivityConfiguration", ["int", "boolean", "boolean"]],
            ["com.android.server.wm.ActivityRecord", "onDisplayChanged", ["com.android.server.wm.DisplayContent"]],
            ["com.android.server.wm.WindowState", "removeImmediately", []],
            ["com.android.server.wm.WindowManagerService", "requestTraversal", []]
        ];
        methods.forEach(function (entry) {
            var method = Java.use(entry[0])[entry[1]];
            if (!method) throw new Error("unsupported method " + entry[1]);
            // An ABI mismatch is final; a missing services class may still be startup timing.
            try { method.overload.apply(method, entry[2]); }
            catch (e) { e.vdUnsupported = true; throw e; }
        });
        ["mRequestedWidth", "mRequestedHeight"].forEach(function (field) {
            Java.use("com.android.server.wm.WindowState").class.getDeclaredField(field);
        });
        Java.use("com.android.server.wm.WindowManagerService").class.getDeclaredField("mGlobalLock");
        Java.use("com.android.server.wm.ActivityRecord").class.getDeclaredField("task");
        Java.use("com.android.server.wm.Task");
        Java.use("android.content.res.Configuration");
    }

    function preparationPass() {
        if (agentFailed) return;
        ++preparationAttempts;
        try {
            publishAgentStatus("preparing", "services");
            prepareAgent();
            if (Number(SystemClock.elapsedRealtime()) >= preparationDeadline) {
                var expired = new Error("preparation deadline"); expired.vdUnsupported = true; throw expired;
            }
        } catch (e) {
            if (e.vdUnsupported || preparationAttempts >= 41
                    || Number(SystemClock.elapsedRealtime()) >= preparationDeadline) {
                failAgent("prepare." + e); return;
            }
            preparationTimer = setTimeout(function () {
                preparationTimer = null;
                Java.perform(preparationPass);
            }, 500);
            return;
        }
        try { installAgent(); } catch (e) { failAgent("install." + e); }
    }

    // BEGIN_CARPLAY_MEMORY_PROTECTION
    function installCarPlayMemoryProtection() {
        var hooks = [];
        try {
            var processClass = Java.use("com.android.server.am.ProcessRecord");
            var serviceClass = Java.use("com.android.server.am.ServiceRecord");
            var activityClass = Java.use("com.android.server.wm.ActivityRecord");
            var applyAdjustment = Java.use("com.android.server.am.OomAdjuster")
                .applyOomAdjLocked.overload("com.android.server.am.ProcessRecord", "boolean", "long", "long");
            var isDestroyable = activityClass.isDestroyable.overload();
            ["processName", "curAdj"].forEach(function (field) {
                processClass.class.getDeclaredField(field);
            });
            ["name", "isForeground"].forEach(function (field) {
                serviceClass.class.getDeclaredField(field);
            });
            ["app", "mActivityComponent", "finishing"].forEach(function (field) {
                activityClass.class.getDeclaredField(field);
            });
            var serviceCount;
            var serviceAt;
            try {
                processClass.numberOfRunningServices.overload();
                processClass.getRunningServiceAt.overload("int");
                serviceCount = function (process) { return process.numberOfRunningServices(); };
                serviceAt = function (process, index) { return process.getRunningServiceAt(index); };
            } catch (missingAccessors) {
                // Earlier Android 11 builds expose the same collection as a field.
                var servicesField = processClass.class.getDeclaredField("services");
                servicesField.setAccessible(true);
                var arraySetClass = Java.use("android.util.ArraySet");
                serviceCount = function (process) {
                    return Java.cast(servicesField.get(process), arraySetClass).size();
                };
                serviceAt = function (process, index) {
                    return Java.cast(servicesField.get(process), arraySetClass).valueAt(index);
                };
            }
            processClass.getWindowProcessController.overload();
            var protectedWindows = Java.use("java.util.WeakHashMap").$new();
            var restorePackage = "ru.big.town.restoremode";
            var sessionService = "com.shilapi.xcertplay.DiPlaySessionService";
            var projectionActivity = "com.shilapi.xcertplay.CarPlayHostActivity";
            var runtimeFailureReported = false;

            function reportFailure(error) {
                if (runtimeFailureReported) return;
                runtimeFailureReported = true;
                Log.e(TAG, "CarPlay memory protection unavailable: " + error);
            }

            function hasSessionService(process) {
                for (var index = 0; index < serviceCount(process); index++) {
                    var service = Java.cast(serviceAt(process, index), serviceClass);
                    var component = service.name.value;
                    if (service.isForeground.value && component !== null
                            && String(component.getPackageName()) === restorePackage
                            && String(component.getClassName()) === sessionService) return true;
                }
                return false;
            }

            hooks.push(applyAdjustment);
            trackReplacement(applyAdjustment);
            applyAdjustment.implementation = function (process, doingAll, now, nowElapsed) {
                try {
                    if (!runtimeFailureReported && String(process.processName.value) === restorePackage) {
                        var windowProcess = process.getWindowProcessController();
                        var protect = hasSessionService(process);
                        var wasProtected = protectedWindows.containsKey(windowProcess);
                        if (protect) {
                            protectedWindows.put(windowProcess, Java.use("java.lang.Boolean").TRUE.value);
                            // Leave raw adjustment, scheduling and system-level priorities intact.
                            if (process.curAdj.value > 0) process.curAdj.value = 0;
                        } else {
                            protectedWindows.remove(windowProcess);
                        }
                        if (protect !== wasProtected) {
                            Log.i(TAG, "CarPlay memory protection " + (protect ? "active" : "released"));
                        }
                    }
                } catch (error) { reportFailure(error); }
                return applyAdjustment.call(this, process, doingAll, now, nowElapsed);
            };

            hooks.push(isDestroyable);
            trackReplacement(isDestroyable);
            isDestroyable.implementation = function () {
                try {
                    var component = this.mActivityComponent.value;
                    if (!runtimeFailureReported && component !== null && !this.finishing.value
                            && String(component.getPackageName()) === restorePackage
                            && String(component.getClassName()) === projectionActivity
                            && this.app.value !== null
                            && protectedWindows.containsKey(this.app.value)) return false;
                } catch (error) { reportFailure(error); }
                return isDestroyable.call(this);
            };
            installed.push("CarPlay.session-memory-protection");
        } catch (error) {
            var rollbackFailed = false;
            hooks.forEach(function (hook) {
                try { hook.implementation = null; }
                catch (rollbackError) { rollbackFailed = true; }
            });
            if (rollbackFailed) throw error;
            Log.e(TAG, "CarPlay memory protection unsupported: " + error);
        }
    }
    // END_CARPLAY_MEMORY_PROTECTION

    function installAgent() {
        // BEGIN_NATIVE_TASK_REMOVAL
        // REMOVE_TASKS is signature|documenter on the OEM ROM, not signature|privileged.
        // Whitelisting Native cannot grant it. Only this rare operation, only Native's resolved UID:
        // execute the stock removeTask body as system and restore Binder identity even on failure.
        // Never hook a general permission checker (hot path / affects unrelated callers).
        try {
            var ATMS = Java.use("com.android.server.wm.ActivityTaskManagerService");
            var nativeRemoveTask = ATMS.removeTask.overload('int');
            trackReplacement(nativeRemoveTask);
            nativeRemoveTask.implementation = function (taskId) {
                if (ourUid < 0 || Binder.getCallingUid() !== ourUid) {
                    return nativeRemoveTask.call(this, taskId);
                }
                var identity = Binder.clearCallingIdentity();
                try {
                    return nativeRemoveTask.call(this, taskId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            };
            installed.push("ATMS.removeTask(native-uid)");
        } catch (e) {
            throw e;
        }
        // END_NATIVE_TASK_REMOVAL

        var displayWindowSettings = Java.use("com.android.server.wm.DisplayWindowSettings");
        var shouldShowDisplayIme = displayWindowSettings.shouldShowImeLocked.overload(
                "com.android.server.wm.DisplayContent");
        var imePolicyWarningLogged = false;

        trackReplacement(shouldShowDisplayIme);
        shouldShowDisplayIme.implementation = function (displayContent) {
            try {
                if (!agentFailed && ourUid >= 0 && displayContent !== null) {
                    var displayInfo = displayContent.getDisplayInfo();
                    // Check ownership on every query: virtual displays and their IDs are transient.
                    // TYPE_VIRTUAL is 5; system decorations and physical-display policy stay stock.
                    if (displayInfo !== null && displayInfo.type.value === 5
                            && displayInfo.ownerUid.value === ourUid
                            && String(displayInfo.ownerPackageName.value) === OUR_PKG
                            && displayContent.isTrusted()) {
                        return true;
                    }
                }
            } catch (error) {
                if (!imePolicyWarningLogged) {
                    imePolicyWarningLogged = true;
                    Log.w(TAG, "Native VD IME policy unavailable; using stock policy: " + error);
                }
            }

            return shouldShowDisplayIme.call(this, displayContent);
        };
        installed.push("DisplayWindowSettings.shouldShowImeLocked(native-trusted-vd)");

        // Only Native may inject input into its hosted displays.
        try {
            var IMS = Java.use("com.android.server.input.InputManagerService");
            trackReplacement(IMS.checkInjectEventsPermission);
            IMS.checkInjectEventsPermission.implementation = function (pid, uid) {
                if (ourUid >= 0 && uid === ourUid) return true;
                return this.checkInjectEventsPermission(pid, uid);
            };
            installed.push("IMS.checkInjectEventsPermission");
        } catch (e) {
            throw e;
        }

        // Scope privileged display creation to the resolved Native UID.
        try {
            var BS = Java.use("com.android.server.display.DisplayManagerService$BinderService");
            trackReplacement(BS.checkCallingPermission.overload('java.lang.String', 'java.lang.String'));
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
            throw e;
        }

        // Native launches hosted activities on secondary displays.
        try {
            var ASS = Java.use("com.android.server.wm.ActivityStackSupervisor");
            trackReplacement(ASS.isCallerAllowedToLaunchOnDisplay);
            ASS.isCallerAllowedToLaunchOnDisplay.implementation = function (pid, uid, displayId, aInfo) {
                if (ourUid >= 0 && uid === ourUid) return true;
                return this.isCallerAllowedToLaunchOnDisplay(pid, uid, displayId, aInfo);
            };
            installed.push("ASS.isCallerAllowedToLaunchOnDisplay");
        } catch (e) {
            throw e;
        }

        // Allow secondary-display activities that omit multi-display compatibility declarations.
        try {
            var AR = Java.use("com.android.server.wm.ActivityRecord");
            trackReplacement(AR.canBeLaunchedOnDisplay);
            AR.canBeLaunchedOnDisplay.implementation = function (displayId) {
                if (displayId !== 0) return true;
                return this.canBeLaunchedOnDisplay(displayId);
            };
            installed.push("ActivityRecord.canBeLaunchedOnDisplay");
        } catch (e) {
            throw e;
        }

        // Secondary activity launches depend on this feature flag.
        try {
            var PMS = Java.use("com.android.server.pm.PackageManagerService");
            trackReplacement(PMS.hasSystemFeature.overload('java.lang.String', 'int'));
            PMS.hasSystemFeature.overload('java.lang.String', 'int').implementation = function (name, version) {
                if (name === "android.software.activities_on_secondary_displays") return true;
                return this.hasSystemFeature(name, version);
            };
            installed.push("PMS.hasSystemFeature(secondary_displays)");
        } catch (e) {
            throw e;
        }

        // Physical displays 0/1 use OEM fullscreen tasks with constrained frames and per-app DPI.
        // Cache configuration outside layout callbacks; restore shared fields even on failure.
        // SCREEN_OFF detaches the hot hooks until wake stabilization completes.
        FF = { on: true, screenOn: true, hookEpoch: 0,
                   lastScreenTransitionAt: 0, rapidScreenTransitions: 0,
                   left: 145, top: 45, right: 1920, bottom: 720, compactBottom: 560,
                   liftType: 2, dpi: {}, fullscreen: {} };
        var SettingsGlobal = Java.use('android.provider.Settings$Global');
        var ATh = Java.use('android.app.ActivityThread');
        var ffLayoutMethod = null, ffLayoutImplementation = null, ffLayoutAttached = false;
        var ffConfigMethod = null, ffConfigImplementation = null, ffConfigAttached = false;
        var ffTraversalService = null, ffTraversalMethod = null, ffTraversalWarned = false;
        var ffTaskClass = null, ffConfigurationClass = null, ffTaskField = null;
        var ffDisplayChangedMethod = null, ffDisplayChangedApplying = false;
        var ffDisplayChangedWarned = false;
        var ffRequestedWidthField = null, ffRequestedHeightField = null;
        var ffOriginalRequestedSize = Java.use("java.util.WeakHashMap").$new();
        var ffSizeRecordClass = Java.use("android.graphics.Rect");
        var FF_WINDOW_CACHE_LIMIT = 128;
        var ffCleanupRequested = false, ffCleanupAll = false;
        var ffDpiApplying = Java.use("java.util.WeakHashMap").$new();
        var ffGuardValue = Java.use("java.lang.Boolean").TRUE.value;

        function ffCr() {
            try { return ATh.currentActivityThread().getSystemContext().getContentResolver(); } catch (e) { return null; }
        }
        function ffInt(cr, key, def) {
            var v = SettingsGlobal.getString(cr, key);
            if (v === null || String(v).trim() === "") return def;
            var n = Number(v);
            if (!isFinite(n) || Math.floor(n) !== n) throw new Error("invalid integer " + key);
            return n;
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
        function ffFullscreen(pkg) {
            return !!pkg && FF.fullscreen[pkg] === true;
        }
        function refreshFreeformCfg() {
            try {
                var cr = ffCr(); if (cr === null) throw new Error("ContentResolver unavailable");
                // Build locally; no callback can observe a partly read policy or a cleared DPI cache.
                var next = {};
                Object.keys(FF).forEach(function (key) { next[key] = FF[key]; });
                var enabled = ffInt(cr, "voyahtune_freeform", 1);
                if (enabled !== 0 && enabled !== 1) throw new Error("invalid freeform flag");
                next.on = enabled === 1;
                next.left = ffInt(cr, "voyahtune_win_left", 145);
                next.top = ffInt(cr, "voyahtune_win_top", 45);
                next.right = ffInt(cr, "voyahtune_win_right", 1920);
                next.bottom = ffInt(cr, "voyahtune_win_bottom", 720);
                next.compactBottom = ffInt(cr, "voyahtune_win_compact_bottom", 560);
                next.liftType = readScreenLiftType();
                next.dpi = Object.create(null);
                next.fullscreen = Object.create(null);
                var dpiCsv = SettingsGlobal.getString(cr, "voyahtune_dpi_packages");
                String(dpiCsv || "").split(",").forEach(function (raw) {
                    var pkg = raw.trim();
                    if (pkg) next.dpi[pkg] = ffInt(cr, "voyahtune_dpi_" + pkg, 0);
                });
                var fullscreenCsv = SettingsGlobal.getString(cr, "voyahtune_fullscreen_apps");
                String(fullscreenCsv || "").split(",").forEach(function (raw) {
                    var pkg = raw.trim();
                    if (pkg) next.fullscreen[pkg] = true;
                });
                if (next.left < 0 || next.top < 0 || next.right <= next.left
                        || next.bottom <= next.top || next.compactBottom <= next.top
                        || next.compactBottom > next.bottom
                        || [next.left, next.top, next.right, next.bottom, next.compactBottom]
                                .some(function (n) { return n > 2147483647; })
                        || (next.liftType !== 1 && next.liftType !== 2)) {
                    throw new Error("invalid viewport");
                }
                Object.keys(next.dpi).forEach(function (pkg) {
                    var dpi = next.dpi[pkg];
                    if (!/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(pkg)
                            || (dpi !== 0 && (dpi < 100 || dpi > 640))) {
                        throw new Error("invalid DPI policy " + pkg);
                    }
                });
                FF = next;
                Log.i(TAG, "freeform cfg on=" + FF.on + " liftType=" + FF.liftType + " rect="
                        + FF.left + "," + FF.top + "," + FF.right + "," + ffBottom()
                        + " fullscreen=" + Object.keys(FF.fullscreen).join(","));
                return true;
            } catch (e) {
                Log.e(TAG, "refreshFreeformCfg retained previous snapshot: " + e);
                return false;
            }
        }
        // Exclude system and VoyahTune windows; Settings and DocumentsUI are intentional exceptions.
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
            return FF.dpi[pkg] || 0;
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
            if (ffDpiApplying.containsKey(task)) return false;
            ffDpiApplying.put(task, ffGuardValue);
            try {
                var current = task.getRequestedOverrideConfiguration();
                if (current.densityDpi.value === dpi) return false;
                var requested = ffConfigurationClass.$new(current);
                requested.densityDpi.value = dpi;
                task.onRequestedOverrideConfigurationChanged(requested);
                return true;
            } finally {
                ffDpiApplying.remove(task);
            }
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
            if (agentFailed || !FF.on || !FF.screenOn || ffTraversalTimer !== null) return;
            var epoch = FF.hookEpoch;
            ffTraversalTimer = setTimeout(function () {
                if (agentFailed || epoch !== FF.hookEpoch) return;
                ffTraversalTimer = null;
                if (agentFailed || epoch !== FF.hookEpoch || !FF.on || !FF.screenOn) return;
                Java.perform(function () { performFreeformTraversal(reason); });
            }, 0);
        }

        function performFreeformTraversal(reason) {
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

        // Bound layout diagnostics to 20 package/display/mode combinations per process.
        var ffSeen = {}, ffSeenN = 0;
        function ffNote(why, pkg, displayId, mode) {
            if (ffSeenN >= 20) return;
            var k = why + "|" + pkg + "|" + displayId + "|" + mode;
            if (ffSeen[k]) return;
            ffSeen[k] = 1; ffSeenN++;
            Log.i(TAG, "ff " + why + " pkg=" + pkg + " display=" + displayId + " mode=" + mode);
        }

        function ffRestoreWindow(win) {
            var value = ffOriginalRequestedSize.get(win);
            if (value === null) return true;
            // Rect is only a Java value record: original width/height, last applied width/height.
            // It contains no reference to its weak key.
            var record = Java.cast(value, ffSizeRecordClass);
            var failed = false;
            try {
                if (ffRequestedWidthField.getInt(win) === record.right.value)
                    ffRequestedWidthField.setInt(win, record.left.value);
            } catch (e) { failed = true; }
            try {
                if (ffRequestedHeightField.getInt(win) === record.bottom.value)
                    ffRequestedHeightField.setInt(win, record.top.value);
            } catch (e) { failed = true; }
            if (!failed) ffOriginalRequestedSize.remove(win);
            return !failed;
        }

        function ffCleanupInWmContext() {
            var keys = ffOriginalRequestedSize.keySet().iterator();
            var windows = [];
            while (keys.hasNext() && windows.length < FF_WINDOW_CACHE_LIMIT) windows.push(keys.next());
            var clean = true;
            windows.forEach(function (object) {
                var win = Java.cast(object, Java.use("com.android.server.wm.WindowState"));
                try {
                    if (ffCleanupAll || !FF.on || !ffFullscreen(String(win.getOwningPackage()))) {
                        if (!ffRestoreWindow(win)) clean = false;
                    }
                } catch (e) { clean = false; }
            });
            ffCleanupRequested = !clean;
        }

        function restoreTrackedWindows(all) {
            if (ffOriginalRequestedSize.size() === 0) return true;
            ffCleanupAll = all;
            ffCleanupRequested = true;
            // LocalService enters mGlobalLock, then calls the rare WMS requestTraversal below.
            // Do not acquire a JNI monitor from the Frida timer thread or enumerate the Java heap.
            resolveFreeformTraversalRequester();
            if (ffTraversalService === null || ffTraversalMethod === null) return false;
            ffTraversalMethod.call(ffTraversalService);
            return !ffCleanupRequested;
        }
        restoreAgentWindows = function () { return restoreTrackedWindows(true); };

        var WMS = Java.use("com.android.server.wm.WindowManagerService");
        var ffGlobalLockField = WMS.class.getDeclaredField("mGlobalLock");
        ffGlobalLockField.setAccessible(true);
        var ffThreadClass = Java.use("java.lang.Thread");
        var ffWmTraversal = WMS.requestTraversal.overload();
        trackReplacement(ffWmTraversal);
        ffWmTraversal.implementation = function () {
            try {
                if (ffCleanupRequested && ffThreadClass.holdsLock(ffGlobalLockField.get(this))) {
                    ffCleanupInWmContext();
                }
            } catch (e) { Log.e(TAG, "window cleanup unavailable: " + e); }
            return ffWmTraversal.call(this);
        };
        var ffWindowRemove = Java.use("com.android.server.wm.WindowState").removeImmediately.overload();
        trackReplacement(ffWindowRemove);
        ffWindowRemove.implementation = function () {
            try { if (!ffRestoreWindow(this)) Log.w(TAG, "removed window size restore incomplete"); }
            catch (e) { Log.w(TAG, "removed window size restore skipped: " + e); }
            finally { ffOriginalRequestedSize.remove(this); }
            return ffWindowRemove.call(this);
        };

        if (!refreshFreeformCfg()) throw new Error("initial policy unavailable");
        resolveFreeformTraversalRequester();
        if (SYSTEM_SERVER_FREEFORM_HOT_HOOKS) {
            installed.push("system_server freeform hot hooks enabled");
        }

        // Native broadcasts WIN_RELOAD after changing window bounds or DPI.
        try {
            // An explicit method signature is required to override the OEM abstract receiver slot.
            var WinReceiver = Java.registerClass({
                name: "ru.big.town.vd.WinReloadReceiver",
                superClass: Java.use("android.content.BroadcastReceiver"),
                methods: {
                    onReceive: {
                        returnType: "void",
                        argumentTypes: ["android.content.Context", "android.content.Intent"],
                        implementation: function (c, i) {
                            if (agentFailed) return;
                            if (ffReloadTimer !== null) clearTimeout(ffReloadTimer);
                            var epoch = FF.hookEpoch;
                            ffReloadTimer = setTimeout(function () {
                                if (agentFailed || epoch !== FF.hookEpoch) return;
                                ffReloadTimer = null;
                                if (agentFailed || epoch !== FF.hookEpoch) return;
                                Java.perform(function () {
                                    try {
                                        if (!refreshFreeformCfg()) return;
                                        if (!restoreTrackedWindows(!FF.on)) {
                                            failAgent("window.restore"); return;
                                        }
                                        if (!FF.on) {
                                            cancelFreeformPending();
                                            if (detachFreeformHotHooks("config off")) {
                                                setFreeformHookState("disabled");
                                            }
                                        } else if (FF.screenOn) {
                                            scheduleFreeformConfigReplay("config reload");
                                        }
                                    } catch (e) { failAgent("reload." + e); }
                                });
                            }, 50);
                        }
                    }
                }
            });
            var IF = Java.use("android.content.IntentFilter");
            var sctx = ATh.currentActivityThread().getSystemContext();
            // Only callers holding WRITE_SECURE_SETTINGS may trigger a configuration reload.
            registerAgentReceiver(sctx, WinReceiver.$new(), IF.$new("ru.big.town.anative.WIN_RELOAD"),
                    "android.permission.WRITE_SECURE_SETTINGS");
            installed.push("WIN_RELOAD receiver");
        } catch (e) { throw e; }

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
                                if (agentFailed || (type !== 1 && type !== 2) || type === FF.liftType) return;
                                FF.liftType = type;
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
            registerAgentReceiver(liftCtx, LiftReceiver.$new(), liftFilter);
            installed.push("screen-lift bounds receiver");
        } catch (e) { throw e; }

        // Clamp third-party windows on physical displays 0/1; virtual displays retain stock layout.
        try {
            var DP = Java.use("com.android.server.wm.DisplayPolicy");
            var WindowStateClass = Java.use("com.android.server.wm.WindowState");
            ffRequestedWidthField = WindowStateClass.class.getDeclaredField("mRequestedWidth");
            ffRequestedHeightField = WindowStateClass.class.getDeclaredField("mRequestedHeight");
            ffRequestedWidthField.setAccessible(true);
            ffRequestedHeightField.setAccessible(true);
            ffLayoutMethod = DP.layoutWindowLw;
            trackReplacement(ffLayoutMethod);
            ffLayoutImplementation = function (win, attached, displayFrames) {
                ffLayoutMethod.call(this, win, attached, displayFrames);
                if (!FF.on) return;
                try {
                    var pkg = win.getOwningPackage();
                    if (ffBlacklisted(pkg)) return;
                    var dc = win.getDisplayContent();
                    if (!dc) return;
                    var displayId = dc.getDisplayId();
                    if (displayId !== 0 && displayId !== 1) return;
                    var attrs = win.getAttrs();
                    // Voice is a dedicated translucent full-display activity, independent of the
                    // main app's dock/freeform preference. Never clamp its 50% scrim to app bounds.
                    var title = attrs.getTitle();
                    if (pkg === "ru.big.town.restoremode" && title !== null
                            && String(title).indexOf("ru.big.town.restoremode.VoiceActivity") >= 0) return;
                    var wt = attrs.type.value;
                    if (wt === 2011 || wt === 2012 || wt === 2038 || wt === 2032) return;
                    var fullscreen = ffFullscreen(pkg);
                    var wmode = win.getWindowingMode();
                    // A selected fullscreen app is normalized to WINDOWING_MODE_FULLSCREEN by the
                    // Native launch bridge. If an OEM launch nevertheless leaves the task in real
                    // freeform, its Task bounds still constrain computeFrame. Mutating those bounds
                    // from DisplayPolicy.layoutWindowLw would re-enter configuration/layout while the
                    // global WM lock is held, so leave mode 5 untouched and retry on the next launch.
                    if (wmode == 5) { ffNote("skip-freeform", pkg, displayId, wmode); return; }
                    var df = win.getDisplayFrames(displayFrames);
                    var wf = win.getWindowFrames();
                    if (!df || !wf) return;
                    // DisplayFrames are shared across the layout pass. Restore them after computeFrame;
                    // only this window retains the constrained WindowFrames.
                    var stable = df.mStable.value;
                    var frameNames = ["mStableFrame", "mParentFrame", "mDisplayFrame",
                        "mContentFrame", "mVisibleFrame", "mDecorFrame"];
                    var savedFrames = [];
                    function checkRect(rect) {
                        if (!rect || typeof rect.set !== "function") throw new Error("unsupported Rect");
                        var values = [rect.left.value, rect.top.value, rect.right.value, rect.bottom.value];
                        if (values.some(function (n) { return typeof n !== "number" || !isFinite(n); }))
                            throw new Error("unsupported Rect fields");
                        return values;
                    }
                    var savedStable = checkRect(stable);
                    frameNames.forEach(function (name) {
                        savedFrames.push({ rect: wf[name].value, bounds: checkRect(wf[name].value) });
                    });
                    if (typeof attrs.width.value !== "number" || typeof attrs.height.value !== "number")
                        throw new Error("unsupported LayoutParams");
                    if (typeof win.computeFrame !== "function") throw new Error("unsupported computeFrame");
                    // Read both fields before changing either one.
                    var currentRequestedWidth = ffRequestedWidthField.getInt(win);
                    var currentRequestedHeight = ffRequestedHeightField.getInt(win);
                    var bottom = ffBottom();
                    var targetLeft = fullscreen ? 0 : FF.left;
                    // Fullscreen removes only the left dock reservation. The OEM status bar remains
                    // visible and consumes touches, so placing the app at y=0 would hide an unclickable
                    // strip of its UI underneath that bar. Reuse the configured status-bar top inset.
                    var targetTop = FF.top;
                    if (fullscreen) ffNote("user-fullscreen", pkg, displayId, wmode);
                    // Reuse the cached Rect to avoid allocations during layout.
                    var savedLeft = savedStable[0], savedTop = savedStable[1];
                    var savedRight = savedStable[2], savedBottom = savedStable[3];
                    // Some apps reserve dock space through requested width and END gravity.
                    // Fullscreen overrides LayoutParams only while computing this window frame.
                    var savedAttrWidth = attrs.width.value, savedAttrHeight = attrs.height.value;
                    var sizeRecord = ffOriginalRequestedSize.get(win);
                    if (sizeRecord !== null) sizeRecord = Java.cast(sizeRecord, ffSizeRecordClass);
                    if (fullscreen && wt === 1 && sizeRecord === null) {
                        // At capacity, preserve stock layout and requested size for this new window.
                        if (ffOriginalRequestedSize.size() >= FF_WINDOW_CACHE_LIMIT) return;
                        sizeRecord = ffSizeRecordClass.$new(currentRequestedWidth, currentRequestedHeight,
                                currentRequestedWidth, currentRequestedHeight);
                        ffOriginalRequestedSize.put(win, sizeRecord);
                    }
                    var layoutComplete = false, restoreFailed = false;
                    try {
                        if (fullscreen && wt === 1) {
                            // A later app relayout can replace its request; keep that new original.
                            if (currentRequestedWidth !== sizeRecord.right.value)
                                sizeRecord.left.value = currentRequestedWidth;
                            if (currentRequestedHeight !== sizeRecord.bottom.value)
                                sizeRecord.top.value = currentRequestedHeight;
                            sizeRecord.right.value = FF.right - targetLeft;
                            sizeRecord.bottom.value = bottom - targetTop;
                            ffRequestedWidthField.setInt(win, FF.right - targetLeft);
                            ffRequestedHeightField.setInt(win, bottom - targetTop);
                        } else if (sizeRecord !== null && !ffRestoreWindow(win)) {
                            throw new Error("requested size restore failed");
                        }
                        if (fullscreen) {
                            attrs.width.value = -1;   // WindowManager.LayoutParams.MATCH_PARENT
                            attrs.height.value = -1;
                        }
                        stable.set(targetLeft, targetTop, FF.right, bottom);
                        wf.mStableFrame.value.set(targetLeft, targetTop, FF.right, bottom);
                        wf.mParentFrame.value.set(targetLeft, targetTop, FF.right, bottom);
                        wf.mDisplayFrame.value.set(targetLeft, targetTop, FF.right, bottom);
                        wf.mContentFrame.value.set(targetLeft, targetTop, FF.right, bottom);
                        wf.mVisibleFrame.value.set(targetLeft, targetTop, FF.right, bottom);
                        wf.mDecorFrame.value.set(targetLeft, targetTop, FF.right, bottom);
                        win.computeFrame(df);
                        layoutComplete = true;
                    } finally {
                        // Each shared field gets its own recovery attempt, even if a prior one throws.
                        try { attrs.width.value = savedAttrWidth; } catch (e) { restoreFailed = true; }
                        try { attrs.height.value = savedAttrHeight; } catch (e) { restoreFailed = true; }
                        try { stable.set(savedLeft, savedTop, savedRight, savedBottom); }
                        catch (e) { restoreFailed = true; }
                        if (!layoutComplete) {
                            savedFrames.forEach(function (saved) {
                                try { saved.rect.set.apply(saved.rect, saved.bounds); }
                                catch (e) { restoreFailed = true; }
                            });
                            if (!ffRestoreWindow(win)) restoreFailed = true;
                        }
                        if (restoreFailed) {
                            agentWindowRollbackIncomplete = true;
                            failAgent("window.fields.restore");
                        }
                    }
                } catch (e) {
                    // An incompatible window retains stock layout without disabling other windows.
                    // Log the failure once to keep layout diagnostics bounded.
                    if (!FF._warned) { FF._warned = true; Log.e(TAG, "freeform layout skip (window, once): " + e); }
                }
            };
            installed.push("DisplayPolicy.layoutWindowLw(detachable-freeform)");
        } catch (e) { throw e; }

        // Apply only requested density on physical displays. VD density comes from creation/resize.
        // Copying resolved bounds into requested configuration would prevent later resizing.
        try {
            var ARc = Java.use("com.android.server.wm.ActivityRecord");
            ffTaskClass = Java.use("com.android.server.wm.Task");
            ffConfigurationClass = Java.use("android.content.res.Configuration");
            // Resolve the shared ActivityRecord field once, outside configuration callbacks.
            ffTaskField = ARc.class.getDeclaredField("task");
            ffTaskField.setAccessible(true);
            ffConfigMethod = ARc.ensureActivityConfiguration.overload('int', 'boolean', 'boolean');
            trackReplacement(ffConfigMethod);
            ffConfigImplementation = function (g, p, iv) {
                var result = ffConfigMethod.call(this, g, p, iv);
                if (!FF.on) return result;
                try {
                    var displayId = this.getDisplayId();
                    if (displayId !== 0 && displayId !== 1) return result;
                    var pkg = this.packageName.value;
                    if (ffBlacklisted(pkg)) return result;
                    ffApplyTaskDpi(ffTaskField.get(this), pkg);
                } catch (e) { /* Preserve stock WM behavior on an OEM mismatch. */ }
                return result;
            };
            installed.push("ActivityRecord.ensureActivityConfiguration(detachable-dpi)");
        } catch (e) { throw e; }

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
                trackReplacement(ffDisplayChangedMethod);
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
        } catch (e) { throw e; }

        function detachFreeformHotHooks(reason) {
            var changed = false, complete = true;
            if (ffLayoutAttached && ffLayoutMethod !== null) {
                try {
                    ffLayoutMethod.implementation = null;
                    ffLayoutAttached = false;
                    changed = true;
                } catch (e) { complete = false; Log.e(TAG, "layout detach fail: " + e); }
            }
            if (ffConfigAttached && ffConfigMethod !== null) {
                try {
                    ffConfigMethod.implementation = null;
                    ffConfigAttached = false;
                    changed = true;
                } catch (e) { complete = false; Log.e(TAG, "config detach fail: " + e); }
            }
            if (changed) Log.i(TAG, "freeform hot hooks DETACHED: " + reason);
            if (!complete) failAgent("hot.detach");
            return complete;
        }

        function setFreeformHookState(state) {
            if (agentFailed) return;
            ffHookState = state;
            try {
                publishAgentStatus(state === "pending" ? "preparing" : "active",
                        state === "active" ? "geometry" : "geometry." + state);
            } catch (e) { failAgent("status." + e); }
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
                } catch (e) { failAgent("layout.attach." + e); return; }
            }
            if (!ffConfigAttached && ffConfigMethod !== null && ffConfigImplementation !== null) {
                try {
                    ffConfigMethod.implementation = ffConfigImplementation;
                    ffConfigAttached = true;
                    changed = true;
                } catch (e) { failAgent("config.attach." + e); return; }
            }
            if (changed) {
                Log.i(TAG, "freeform hot hooks ATTACHED: " + reason);
                requestFreeformTraversalOnce(reason);
            }
            if (ffLayoutAttached && ffConfigAttached && !agentFailed) {
                setFreeformHookState("active");
            }
        }

        // Saved-config startup publishes fullscreen, DPI and dock snapshots as separate protected
        // broadcasts. They can produce several WIN_RELOAD events in one looper turn. Never detach a
        // working WindowManager replacement for a cache-only change, and never let config traffic shorten
        // the initial/wake stabilization delay. One bounded traversal applies the last complete snapshot.
        function scheduleFreeformConfigReplay(reason) {
            if (ffConfigReplayTimer !== null) clearTimeout(ffConfigReplayTimer);
            var epoch = FF.hookEpoch;
            ffConfigReplayTimer = setTimeout(function () {
                if (agentFailed || epoch !== FF.hookEpoch) return;
                ffConfigReplayTimer = null;
                if (agentFailed || epoch !== FF.hookEpoch || !FF.on || !FF.screenOn) return;
                Java.perform(function () {
                    if (ffLayoutAttached && ffConfigAttached) {
                        requestFreeformTraversalOnce(reason);
                        return;
                    }
                    if (!ffHotAttachPending) {
                        scheduleFreeformHotAttach(1000, reason + " +1s stabilization");
                    }
                });
            }, 100);
        }

        function scheduleFreeformHotAttach(delayMs, reason) {
            if (agentFailed || !SYSTEM_SERVER_FREEFORM_HOT_HOOKS || !FF.on || !FF.screenOn) return;
            if (ffLayoutAttached && ffConfigAttached) return;
            if (ffHotAttachPending) return;
            var epoch = ++FF.hookEpoch;
            ffHotAttachPending = true;
            setFreeformHookState("pending");
            if (agentFailed) return;
            ffHotAttachTimer = setTimeout(function () {
                if (agentFailed || epoch !== FF.hookEpoch) return;
                ffHotAttachTimer = null;
                if (agentFailed || FF.hookEpoch !== epoch || !FF.screenOn || !FF.on) return;
                ffHotAttachPending = false;
                Java.perform(function () {
                    try { attachFreeformHotHooks(reason); }
                    catch (e) { failAgent("hot.attach." + e); }
                });
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
            // Repeated rapid sleep/wake cycles require a longer stabilization interval.
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
                            if (agentFailed) return;
                            try {
                                var action = i.getAction();
                                if (action === "android.intent.action.SCREEN_OFF") {
                                    if (!FF.screenOn) return;
                                    noteFreeformScreenTransition();
                                    FF.screenOn = false;
                                    cancelFreeformPending();
                                    if (detachFreeformHotHooks("SCREEN_OFF")) {
                                        setFreeformHookState(FF.on ? "sleeping" : "disabled");
                                    }
                                } else if (action === "android.intent.action.SCREEN_ON") {
                                    if (FF.screenOn) return;
                                    var attachDelay = noteFreeformScreenTransition();
                                    FF.screenOn = true;
                                    refreshFreeformCfg();
                                    if (!restoreTrackedWindows(!FF.on)) {
                                        failAgent("window.restore"); return;
                                    }
                                    if (FF.on) scheduleFreeformHotAttach(attachDelay,
                                            "SCREEN_ON +" + attachDelay + "ms");
                                    else setFreeformHookState("disabled");
                                }
                            } catch (e) { failAgent("screen." + e); }
                        }
                    }
                }
            });
            var ScreenFilter = Java.use("android.content.IntentFilter");
            var sf = ScreenFilter.$new("android.intent.action.SCREEN_ON");
            sf.addAction("android.intent.action.SCREEN_OFF");
            registerAgentReceiver(ATh.currentActivityThread().getSystemContext(), ScreenReceiver.$new(), sf);
            installed.push("screen hot-hook attach/detach");
        } catch (e) { throw e; }

        FF.screenOn = ffScreenIsInteractive();
        if (FF.screenOn && FF.on) {
            // Injection may coincide with boot or wake; allow the same stabilization interval.
            scheduleFreeformHotAttach(1000, "initial +1s");
        } else {
            setFreeformHookState(FF.on ? "sleeping" : "disabled");
        }

        installCarPlayMemoryProtection();

        // Root loader converts this generation-specific result to the compatible v1 worker snapshot.
        Log.i(TAG, "hooks installed [" + installed.join(", ") + "] uid=" + ourUid);

    } // installAgent
    preparationPass();
});
