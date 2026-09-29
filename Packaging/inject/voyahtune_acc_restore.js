// H97X guest ACC ON: intercept the outgoing reset before ModeSettingTask is queued.
// Do not replace the BCM parser: it also handles power, locks, privacy and other vehicle state.
Java.perform(function () {
    "use strict";
    var TAG = "VoyahAccRestore";
    var READY = "[acc-restore] hook ready v2";
    var SENTINEL = "open_voyah.acc_restore.v2";
    var installed = [];
    var Log = Java.use("android.util.Log");
    var System = Java.use("java.lang.System");
    function log(message) {
        try { Log.i(TAG, message); } catch (_) {}
        try { console.log(message); } catch (_) {}
    }
    function install(method, implementation) {
        method.implementation = implementation;
        installed.push(method);
    }
    var HEALTH_PATH = "/data/local/open_voyah/drive_hooks/voyahtune_acc_restore.health";
    var Clock = Java.use("android.os.SystemClock");
    var Process = Java.use("android.os.Process");
    var FileWriter = Java.use("java.io.FileWriter");
    var pulseTimer = null;
    function healthy() {
        var last = Number(System.getProperty(SENTINEL + ".pulse", "0"));
        return last > 0 && Number(Clock.elapsedRealtime()) - last < 15000;
    }
    function pulse() {
        if (installed.some(function (m) { return m.implementation === null; })) {
            log("agent unhealthy: hook removed");
            if (pulseTimer !== null) clearInterval(pulseTimer);
            return;
        }
        var now = Number(Clock.elapsedRealtime());
        System.setProperty(SENTINEL + ".pulse", String(now));
        try {
            var out = FileWriter.$new(HEALTH_PATH, false);
            try {
                var line = String(Process.myPid()) + "|" + Math.floor(now / 1000) + "|v2\n";
                out.write.overload("java.lang.String", "int", "int").call(out, line, 0, line.length);
            }
            finally { out.close(); }
        } catch (e) { log("health write unavailable: " + e); }
    }
    try {
        if (healthy() && String(System.getProperty(SENTINEL, "")) === "installed") {
            log(READY + " already_installed");
            return;
        }
        var Component = Java.use("com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl");
        var Base = Java.use("com.qinggan.canbus.service.BaseCanBusComponent");
        var Bundle = Java.use("android.os.Bundle");
        var Uri = Java.use("android.net.Uri");
        var scope = Java.use("java.lang.ThreadLocal").$new();
        var query = Java.use("android.content.ContentResolver").query.overload(
            "android.net.Uri", "[Ljava.lang.String;", "java.lang.String",
            "[Ljava.lang.String;", "java.lang.String");
        var systemInt = Java.use("com.qinggan.provider.QGSettings$System").getInt.overload(
            "android.content.ContentResolver", "java.lang.String", "int");
        var parser = Component.onBCM_PEPSChangeData.overload("[I", "boolean");
        var setter = Component.setVehicleAndAirConditionBundleState.overload(
            "android.os.Bundle", "android.os.Bundle");
        var feedback = Base.onVehicleStateChanged.overload("com.qinggan.canbus.VehicleState", "int");
        var single = Component.setVehicleState.overload("com.qinggan.canbus.VehicleState", "int");
        var Binder = Java.use("android.os.Binder");
        var ActivityThread = Java.use("android.app.ActivityThread");
        var State = Java.use("com.qinggan.canbus.VehicleState");
        var Task = Java.use("com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl$ModeSettingTask");
        var taskInit = Task.$init.overload("com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl", "com.qinggan.canbus.service.BaseCanBusComponent");
        var taskSend = Task.setVehicleMode.overload("java.util.HashMap");
        var taskMeta = Java.use("java.util.WeakHashMap").$new();
        var sending = Java.use("java.lang.ThreadLocal").$new();
        var lock = Java.use("java.util.concurrent.locks.ReentrantLock").$new();
        var call = Java.use("android.content.ContentResolver").call.overload(
            "android.net.Uri", "java.lang.String", "java.lang.String", "android.os.Bundle");
        var selectedComponent = null;
        var bootstrapDone = false;
        var settingsDispatchDone = false;
        function hook(resolver, action, args) {
            // Provider authorization must see the CAN service, not its original Binder client.
            var identity = Binder.clearCallingIdentity();
            try {
                var result = call.call(resolver, Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                    "driveHookV2", action, args || Bundle.$new());
                if (result === null || result.getInt("protocol") !== 2) throw new Error("driveHookV2 unavailable");
                return result;
            } finally { Binder.restoreCallingIdentity(identity); }
        }
        function observeAcc(component, value) {
            var args = Bundle.$new(); args.putInt("acc", value);
            return hook(component.contentResolver.value, "acc", args);
        }
        function dispatchSettings(component) {
            if (settingsDispatchDone) return;
            var result = hook(component.contentResolver.value, "dispatchSettings");
            settingsDispatchDone = result.getBoolean("settingsDispatched")
                || String(result.getString("settingsStartup")) !== "pending";
        }
        function trusted(packageName) {
            try { return Binder.getCallingUid() === ActivityThread.currentApplication()
                .getPackageManager().getApplicationInfo(packageName, 0).uid.value; }
            catch (_) { return false; }
        }
        function user(component, vehicle) {
            var args = Bundle.$new();
            var drive = vehicle.getInt("DRIVING_MODE_SET", -1);
            var energy = vehicle.getInt("IVI_SOC_MODESET", -1);
            if (drive > 0 && drive <= 6) args.putString("mode", ["", "ECO", "COMFORT", "SPORT", "OUTING", "INDIVIDUAL", "SNOW"][drive]);
            if (energy > 0 && energy <= 5) args.putString("energy", ["", "SMART", "EV", "REV", "SREV", "FORCE_EV"][energy]);
            var result = hook(component.contentResolver.value, "user", args);
            log("user request drive=" + drive + " energy=" + energy + " revision=" + result.getLong("revision"));
            return result;
        }
        function metadata(component, automatic) {
            var snap = hook(component.contentResolver.value, "snapshot");
            var meta = Bundle.$new(); meta.putBoolean("automatic", automatic);
            meta.putLong("revision", snap.getLong("revision"));
            return meta;
        }
        function applyTargets(outgoing, targets) {
            if (targets.drive !== null) {
                outgoing.putInt("DRIVING_MODE_SET", targets.drive[0]);
                outgoing.putInt("EPS_MODE_SET", targets.drive[1]);
                outgoing.putInt("PROP_MODE_SET", targets.drive[2]);
                if (targets.drive[0] === 6) outgoing.remove("HUM_ENERGY_PTREGEN_LEVL");
            }
            if (targets.energy !== null) outgoing.putInt("IVI_SOC_MODESET", targets.energy);
        }
        install(taskInit, function (outer, component) {
            var result = taskInit.call(this, outer, component);
            var meta = sending.get();
            if (meta !== null) taskMeta.put(this, Bundle.$new(Java.cast(meta, Bundle)));
            return result;
        });
        install(taskSend, function (values) {
            lock.lock();
            try {
                var raw = taskMeta.remove(this);
                if (raw !== null) {
                    var meta = Java.cast(raw, Bundle);
                    log("mode task origin=" + (meta.getBoolean("automatic") ? "automatic" : "user"));
                }
                var result = taskSend.call(this, values);
                if (raw !== null) log("mode task send result=" + result + " (not physical confirmation)");
                return result;
            } finally { lock.unlock(); }
        });
        function invocation(receiver) {
            var value = scope.get();
            if (value === null) return null;
            var state = Java.cast(value, Bundle);
            return state.getInt("owner") === System.identityHashCode(receiver) ? state : null;
        }
        function individualValue(resolver, key, fallback) {
            var c = query.call(resolver, Uri.parse("content://qinggan.settings/global"),
                Java.array("java.lang.String", ["value"]), "name=?",
                Java.array("java.lang.String", [key]), null);
            if (c === null) throw new Error("Individual provider unavailable");
            try {
                if (!c.moveToFirst() || c.isNull(0)) return fallback;
                var raw = String(c.getString(0)).trim();
                if (!/^-?\d+$/.test(raw)) throw new Error("Invalid Individual value");
                return Number(raw);
            } finally { c.close(); }
        }

        function selectedTargets(resolver) {
            var c = query.call(resolver,
                Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                null, null, null, null);
            if (c === null) return null;
            var mode, driveEnabled, energyEnabled, energy, forcedEv, maintenance;
            try {
                if (!c.moveToFirst() || c.getColumnCount() <= 32 || c.isNull(32)
                        || c.getInt(12) === 1) return null; // Missing settings or debug: stock behavior.
                mode = String(c.getString(0));
                driveEnabled = c.getInt(6) === 1;
                energyEnabled = c.getInt(8) === 1;
                energy = String(c.getString(1));
                forcedEv = c.getInt(19) === 1;
                var maintenanceRaw = c.getInt(32);
                if (maintenanceRaw !== 0 && maintenanceRaw !== 1) return null;
                maintenance = maintenanceRaw === 1 ? 2 : 1; // HintSwitch: on=2, off=1.
            } finally { c.close(); }
            var targets = {drive: null, energy: null, maintenance: maintenance};
            if (forcedEv) targets.energy = 5;
            else if (energyEnabled) {
                var energies = {SMART: 1, Smart: 1, EV: 2, REV: 3, SREV: 4, FORCE_EV: 5};
                if (!Object.prototype.hasOwnProperty.call(energies, energy)) return null;
                targets.energy = energies[energy];
            }
            if (!driveEnabled) return targets;
            var profiles = {
                ECO: [1, 2, 1], COMFORT: [2, 2, 2], SPORT: [3, 3, 3],
                OUTING: [4, 2, 3], SNOW: [6, 2, 2]
            };
            if (mode === "INDIVIDUAL") {
                var Accounts = Java.use("com.qinggan.account.AccountUserManager");
                var bean = Accounts.getInstance().AccountInfoBean();
                var account = bean === null || bean.isGuest.value || bean.getName() === null
                    || String(bean.getName()).length === 0 ? "guest" : bean.accountId.value;
                if (account === null || String(account).length === 0) return null;
                var steering = individualValue(resolver, "drive_mode_steeringWheelAssist" + account, 2);
                var pedal = individualValue(resolver, "drive_mode_runState" + account, 1);
                if ((steering !== 2 && steering !== 3) || pedal < 1 || pedal > 3) return null;
                targets.drive = [5, steering, pedal];
                return targets;
            }
            if (!Object.prototype.hasOwnProperty.call(profiles, mode)) return null;
            targets.drive = profiles[mode];
            return targets;
        }

        install(setter, function (air, vehicle) {
            lock.lock();
            var oldSending = sending.get();
            try {
                var state = invocation(this);
                var outgoing = vehicle;
                var automatic = false, targetDrive = 0;
                var nativeCaller = trusted("ru.big.town.anative");
                var stockCaller = trusted("com.qinggan.app.vehiclesetting");
                if (vehicle !== null) {
                    var taggedAuto = stockCaller && vehicle.getBoolean("__vt_auto", false);
                    var taggedUser = stockCaller && vehicle.getBoolean("__vt_user", false);
                    var startup = sending.get() !== null;
                    var guest = state !== null && state.getInt("submitted") === 0 && air === null
                        && systemInt.call(null, this.contentResolver.value, "VehicleAccountInfo", 1) === 1
                        && vehicle.getInt("DRIVING_MODE_SET", -1) === 1
                        && vehicle.getInt("EPS_MODE_SET", -1) === 2 && vehicle.getInt("PROP_MODE_SET", -1) === 1;
                    automatic = guest || taggedAuto || startup;
                    outgoing = Bundle.$new(vehicle);
                    outgoing.remove("__vt_auto"); outgoing.remove("__vt_user");
                    if (automatic) {
                        try {
                            var meta = metadata(this, true);
                            var targets = selectedTargets(this.contentResolver.value);
                            if (targets !== null) {
                                applyTargets(outgoing, targets);
                                if (guest) outgoing.putInt("ASC_MAINTAIN_SWITCH", targets.maintenance);
                                targetDrive = targets.drive === null ? 0 : targets.drive[0];
                            }
                            sending.set(meta);
                        } catch (e) {
                            // Never claim success or remember an automatic event if settings are unavailable.
                            sending.remove(); log("automatic target unavailable: " + e);
                        }
                    } else if ((taggedUser || nativeCaller) && (vehicle.containsKey("DRIVING_MODE_SET") || vehicle.containsKey("IVI_SOC_MODESET"))) {
                        try { user(this, vehicle); } catch (e) { log("user intent persistence failed: " + e); }
                        sending.remove();
                    } else {
                        sending.remove();
                        if (vehicle.containsKey("DRIVING_MODE_SET") || vehicle.containsKey("IVI_SOC_MODESET")) log("unknown mode request; passthrough");
                    }
                }
                var result = setter.call(this, air, outgoing);
                if (state !== null && automatic) {
                    state.putInt("submitted", 1);
                    if (result === 0) state.putInt("drive", targetDrive);
                    log("guest ACC bundle queued=" + (result === 0) + " drive=" + targetDrive);
                }
                return result;
            } finally {
                if (oldSending === null) sending.remove(); else sending.set(oldSending);
                lock.unlock();
            }
        });
        install(feedback, function (vehicle, value) {
            var state = invocation(this);
            var drive = state === null ? 0 : state.getInt("drive");
            // The guest branch explicitly publishes Eco after queuing its bundle. Match the
            // substituted target so Native/UI do not remember a false Eco event.
            if (drive > 0 && value === 1 && String(vehicle) === "DRIVING_MODE_SET") value = drive;
            return feedback.call(this, vehicle, value);
        });
        install(single, function (vehicle, value) {
            var state = invocation(this);
            var drive = state === null ? 0 : state.getInt("drive");
            // Only correct the guest's explicit Eco color when drive-linked lighting is active.
            var colors = {1: 19, 2: 5, 3: 64, 4: 54, 5: 10, 6: 9};
            if (drive > 0 && value === 19 && String(vehicle) === "VEHICLE_AMBIENT_LIGHT_COLOR") {
                value = colors[drive];
            }
            lock.lock();
            try {
                if (state === null && trusted("ru.big.town.anative")
                        && (String(vehicle) === "DRIVING_MODE_SET" || String(vehicle) === "IVI_SOC_MODESET")) {
                    var request = Bundle.$new(); request.putInt(String(vehicle), value);
                    try { user(this, request); } catch (e) { log("single intent failed: " + e); }
                }
                return single.call(this, vehicle, value);
            } finally { lock.unlock(); }
        });
        // Install the entry point last. No resolver query, service start or prefetch delays hook readiness.
        install(parser, function (data, notify) {
            var entering = false;
            try {
                entering = data !== null && data.length > 0 && (data[0] & 7) === 2
                    && this.getAccStatus() !== 2 && this.isH97X();
            } catch (_) {} // Unknown firmware state: preserve the complete stock parser.
            try {
                var acc = data !== null && data.length > 0 ? data[0] & 7 : -1;
                if (this.isH97X() && (entering || (acc === 0 && this.getAccStatus() !== 0))) {
                    observeAcc(this, acc);
                    if (entering) { bootstrapDone = false; settingsDispatchDone = false; }
                }
            } catch (e) { log("ACC state unavailable: " + e); }
            var previous = scope.get();
            // Clear an outer scope even for nested non-ACC frames, then restore it in finally.
            if (entering) {
                var state = Bundle.$new();
                state.putInt("owner", System.identityHashCode(this));
                scope.set(state);
            } else scope.remove();
            try { return parser.call(this, data, notify); }
            finally {
                if (previous === null) scope.remove();
                else scope.set(previous);
                // Start Native's remaining settings only after the OEM ACC handler has returned.
                if (entering) {
                    try { dispatchSettings(this); }
                    catch (e) { log("ACC settings dispatch unavailable: " + e); }
                }
            }
        });
        function bootstrap(component) {
            if (bootstrapDone || component === null || !component.isH97X()) return;
            lock.lock();
            try {
                var snap = observeAcc(component, component.getAccStatus());
                if (snap.getInt("acc") !== 2) return;
                // Also covers a late attach after the ACC parser already ran.
                try { dispatchSettings(component); }
                catch (e) { log("startup settings dispatch unavailable: " + e); }
                var startupState = String(snap.getString("startup"));
                if (startupState !== "pending") { bootstrapDone = true; return; }
                var targets = selectedTargets(component.contentResolver.value);
                if (targets === null || (targets.drive === null && targets.energy === null)) return;
                var b = Bundle.$new(); applyTargets(b, targets);
                var claim = Bundle.$new(); claim.putLong("revision", snap.getLong("revision"));
                var claimed = hook(component.contentResolver.value, "claim", claim);
                if (!claimed.getBoolean("claimed")) return;
                bootstrapDone = true; // Uncertain Binder results must not cause blind replay.
                var meta = Bundle.$new(); meta.putBoolean("automatic", true);
                meta.putLong("revision", snap.getLong("revision"));
                var previous = sending.get(); sending.set(meta);
                var accepted = false;
                try { accepted = component.setVehicleAndAirConditionBundleState(null, b) === 0; }
                finally {
                    if (previous === null) sending.remove(); else sending.set(previous);
                    claim.putBoolean("accepted", accepted);
                    hook(component.contentResolver.value, "complete", claim);
                    log("startup modes queued=" + accepted + " drive=" + targets.drive + " energy=" + targets.energy);
                }
            } finally { lock.unlock(); }
        }
        function discoverAndBootstrap() {
            if (bootstrapDone) return;
            if (selectedComponent !== null) { bootstrap(selectedComponent); return; }
            // H97X uses 32-bit ART: heap enumeration (Java.choose/GetInstances) crashes it.
            // ActivityThread already owns the live service; follow that reference instead.
            var thread = ActivityThread.currentActivityThread();
            if (thread === null) return;
            var services = thread.mServices.value.values().iterator();
            while (services.hasNext()) {
                var service = services.next();
                if (String(service.getClass().getName()) !== "com.qinggan.canbus.service.CanBusService") continue;
                var owner = Java.cast(service, Java.use("com.qinggan.canbus.service.CanBusService"));
                var component = owner.mCanBusComponent.value;
                if (component !== null) selectedComponent = Java.retain(Java.cast(component, Component));
                break;
            }
            if (selectedComponent !== null) bootstrap(selectedComponent);
        }
        setTimeout(function retryReadiness() {
            Java.perform(function () {
                try { discoverAndBootstrap(); } catch (e) { log("startup waiting: " + e); }
                if (!settingsDispatchDone && selectedComponent !== null) {
                    try { dispatchSettings(selectedComponent); }
                    catch (e) { log("settings dispatch waiting: " + e); }
                }
            });
            // A new ACC edge clears bootstrapDone. This timer checks readiness only, not current modes.
            setTimeout(retryReadiness, 5000);
        }, 0);
        System.setProperty(SENTINEL, "installed");
        pulse();
        pulseTimer = setInterval(function () { Java.perform(pulse); }, 2000);
        log(READY);
    } catch (e) {
        for (var i = installed.length - 1; i >= 0; i--) {
            try { installed[i].implementation = null; } catch (_) {}
        }
        log("[acc-restore] hook failed stage=install reason=" + e);
    }
});
