// H97X guest ACC ON: intercept the outgoing reset before ModeSettingTask is queued.
// Do not replace the BCM parser: it also handles power, locks, privacy and other vehicle state.
Java.perform(function () {
    "use strict";
    var TAG = "VoyahAccRestore";
    var READY = "[acc-restore] hook ready v1";
    var SENTINEL = "open_voyah.acc_restore.v1";
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
    try {
        if (String(System.getProperty(SENTINEL, "")) === "installed") {
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
                var energies = {SMART: 1, Smart: 1, EV: 2, REV: 3, SREV: 4};
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
            var state = invocation(this);
            var outgoing = vehicle;
            var targetDrive = 0;
            if (state !== null && state.getInt("submitted") === 0 && air === null && vehicle !== null) {
                try {
                    // Match the actual guest branch, not ordinary bundles or the logged-in restore.
                    if (systemInt.call(null, this.contentResolver.value, "VehicleAccountInfo", 1) === 1
                            && vehicle.getInt("DRIVING_MODE_SET", -1) === 1
                            && vehicle.getInt("EPS_MODE_SET", -1) === 2
                            && vehicle.getInt("PROP_MODE_SET", -1) === 1) {
                        var targets = selectedTargets(this.contentResolver.value);
                        if (targets !== null) {
                            outgoing = Bundle.$new(vehicle);
                            if (targets.drive !== null) {
                                targetDrive = targets.drive[0];
                                outgoing.putInt("DRIVING_MODE_SET", targetDrive);
                                outgoing.putInt("EPS_MODE_SET", targets.drive[1]);
                                outgoing.putInt("PROP_MODE_SET", targets.drive[2]);
                                if (targetDrive === 6) outgoing.remove("HUM_ENERGY_PTREGEN_LEVL");
                            }
                            if (targets.energy !== null) outgoing.putInt("IVI_SOC_MODESET", targets.energy);
                            outgoing.putInt("ASC_MAINTAIN_SWITCH", targets.maintenance);
                        }
                    }
                } catch (e) {
                    outgoing = vehicle;
                    targetDrive = 0;
                    log("stock fallback: saved targets unavailable");
                }
            }
            // Never catch/replay this call: success means queued, not CAN-confirmed.
            var result = setter.call(this, air, outgoing);
            if (state !== null && outgoing !== vehicle) {
                state.putInt("submitted", 1);
                if (result === 0) state.putInt("drive", targetDrive);
                log("guest ACC bundle queued=" + (result === 0) + " drive=" + targetDrive);
            }
            return result;
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
            return single.call(this, vehicle, value);
        });
        // Install the entry point last. No resolver query, service start or prefetch delays hook readiness.
        install(parser, function (data, notify) {
            var entering = false;
            try {
                entering = data !== null && data.length > 0 && (data[0] & 7) === 2
                    && this.getAccStatus() !== 2 && this.isH97X();
            } catch (_) {} // Unknown firmware state: preserve the complete stock parser.
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
            }
        });
        System.setProperty(SENTINEL, "installed");
        log(READY);
    } catch (e) {
        for (var i = installed.length - 1; i >= 0; i--) {
            try { installed[i].implementation = null; } catch (_) {}
        }
        log("[acc-restore] hook failed stage=install");
    }
});
