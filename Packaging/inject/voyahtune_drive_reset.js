// Sport+ H97X: apply saved drive/energy/suspension settings in the two account-reset requests.
// The original reset methods retain their ambient/retain behavior. Ordinary
// setters, account sync and the separate CanBusService ACC path are outside this hook.
Java.perform(function () {
    "use strict";
    var TAG = "VoyahDriveReset";
    var READY = "[drive-reset] hook ready v1";
    var SENTINEL = "open_voyah.drive_reset.v1";
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
        var Memory = Java.use("com.qinggan.app.vehiclesetting.accountdata.VehicleMemoryManager");
        var CanBus = Java.use("com.qinggan.canbus.CanBusManager");
        var Platform = Java.use("com.qinggan.utils.AppCommonUtils");
        var ActivityThread = Java.use("android.app.ActivityThread");
        var Utils = Java.use("com.qinggan.app.vehiclesetting.utils.Utils");
        var Uri = Java.use("android.net.Uri");
        var Bundle = Java.use("android.os.Bundle");
        var scope = Java.use("java.lang.ThreadLocal").$new();
        var JString = Java.use("java.lang.String");
        var query = Java.use("android.content.ContentResolver").query.overload(
            "android.net.Uri", "[Ljava.lang.String;", "java.lang.String",
            "[Ljava.lang.String;", "java.lang.String");
        var setter = CanBus.setVehicleAndAirConditionBundleState.overload(
            "android.os.Bundle", "android.os.Bundle");

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

        function selectedTargets() {
            var app = ActivityThread.currentApplication();
            if (app === null) return null;
            var resolver = app.getContentResolver();
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
                var account = Utils.getAccountId();
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
            var outgoing = vehicle;
            if (scope.get() !== null && vehicle !== null) {
                // Catch preparation errors only: never replay a setter whose Binder call failed.
                try {
                    var targets = selectedTargets();
                    if (targets !== null && vehicle.containsKey("DRIVING_MODE_SET")) {
                        outgoing = Bundle.$new(vehicle);
                        var profile = targets.drive;
                        if (profile !== null) {
                            outgoing.putInt("DRIVING_MODE_SET", profile[0]);
                            outgoing.putInt("EPS_MODE_SET", profile[1]);
                            outgoing.putInt("PROP_MODE_SET", profile[2]);
                        }
                        if (targets.energy !== null) outgoing.putInt("IVI_SOC_MODESET", targets.energy);
                        outgoing.putInt("ASC_MAINTAIN_SWITCH", targets.maintenance);
                        // Snow owns recuperation in the OEM drive-mode handler. Do not let the
                        // reset's high-regen field race it in the unordered TX77 bundle.
                        if (profile !== null && profile[0] === 6) outgoing.remove("HUM_ENERGY_PTREGEN_LEVL");
                        log("replace " + scope.get() + " drive=" + (profile === null ? "stock" : profile[0])
                            + " energy=" + (targets.energy === null ? "stock" : targets.energy)
                            + " suspension=" + targets.maintenance);
                    }
                } catch (e) {
                    outgoing = vehicle;
                    log("stock fallback: saved profile unavailable");
                }
            }
            return setter.call(this, air, outgoing);
        });
        ["resetSettings", "resetOverseaDriveMode"].forEach(function (name) {
            var method = Memory[name].overload();
            install(method, function () {
                if (!Platform.is97X()) return method.call(this);
                var previous = scope.get();
                scope.set(JString.$new(name));
                try { return method.call(this); }
                finally {
                    if (previous === null) scope.remove();
                    else scope.set(previous);
                }
            });
        });
        System.setProperty(SENTINEL, "installed");
        log(READY);
    } catch (e) {
        for (var i = installed.length - 1; i >= 0; i--) {
            try { installed[i].implementation = null; } catch (_) {}
        }
        log("[drive-reset] hook failed stage=install");
    }
});
