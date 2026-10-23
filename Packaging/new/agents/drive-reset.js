// H97X VehicleSettings integration and VehicleAir user drive selections.
// The original reset methods retain their ambient/retain behavior. Ordinary
// setters, account sync and the separate CanBusService ACC path are outside this hook.
Java.perform(function () {
    "use strict";
    var TAG = "VoyahDriveReset";
    var READY = "[drive-reset] hook ready v2";
    var SENTINEL = "open_voyah.drive_reset.v2";
    var installed = [];
    var cleanupActions = [];
    var Log = Java.use("android.util.Log");
    var System = Java.use("java.lang.System");
    function log(message) {
        try {
            Log.i(TAG, message);
        } catch (_) { }
        try {
            console.log(message);
        } catch (_) { }
    }
    function install(method, implementation) {
        method.implementation = implementation;
        installed.push(method);
    }
    var HEALTH_PATH =
        "/data/local/open_voyah/new/drive_hooks/voyahtune_drive_reset.health";
    var Clock = Java.use("android.os.SystemClock");
    var Process = Java.use("android.os.Process");
    var FileWriter = Java.use("java.io.FileWriter");
    var pulseTimer = null;
    function healthy() {
        var last = Number(System.getProperty(SENTINEL + ".pulse", "0"));
        return last > 0 && Number(Clock.elapsedRealtime()) - last < 15000;
    }
    function pulse() {
        if (
            installed.some(function (m) {
                return m.implementation === null;
            })
        ) {
            log("agent unhealthy: hook removed");
            if (pulseTimer !== null) clearInterval(pulseTimer);
            return;
        }
        var now = Number(Clock.elapsedRealtime());
        System.setProperty(SENTINEL + ".pulse", String(now));
        try {
            var out = FileWriter.$new(HEALTH_PATH, false);
            try {
                var line =
                    String(Process.myPid()) +
                    "|" +
                    Math.floor(now / 1000) +
                    "|v2\n";
                out.write
                    .overload("java.lang.String", "int", "int")
                    .call(out, line, 0, line.length);
            } finally {
                out.close();
            }
        } catch (e) {
            log("health write unavailable: " + e);
        }
    }
    // VehicleAir has a separate process and heartbeat, but uses the same packaged agent.
    var app = Java.use("android.app.ActivityThread").currentApplication();
    var vehicleAir =
        app !== null &&
        String(app.getPackageName()) === "com.qinggan.app.vehicle";
    if (vehicleAir)
        HEALTH_PATH = "/data/local/open_voyah/new/drive_hooks/vehicle.health";
    function installVehicleAir() {
        var scope = Java.use("java.lang.ThreadLocal").$new();
        var JString = Java.use("java.lang.String");
        var Bundle = Java.use("android.os.Bundle");
        var Uri = Java.use("android.net.Uri");
        var call = Java.use("android.content.ContentResolver").call.overload(
            "android.net.Uri",
            "java.lang.String",
            "java.lang.String",
            "android.os.Bundle",
        );
        var setter = Java.use(
            "com.qinggan.canbus.CanBusManager",
        ).setVehicleAndAirConditionBundleState.overload(
            "android.os.Bundle",
            "android.os.Bundle",
        );
        // Resolve every required method before installation; other firmware stays untouched.
        var handler = Java.use(
            "com.qinggan.app.vehiclebase.ui97.DriveModeViewManager$1",
        ).handleMessage.overload("android.os.Message");
        var confirm = Java.use(
            "com.qinggan.app.vehiclebase.ui97.DriveModeViewManager$2",
        ).onClick.overload("android.view.View");
        function inUserScope(original, receiver, argument, user) {
            var previous = scope.get();
            if (user) scope.set(JString.$new("vehicle menu"));
            else scope.remove();
            try {
                return original.call(receiver, argument);
            } finally {
                if (previous === null) scope.remove();
                else scope.set(previous);
            }
        }
        install(handler, function (message) {
            return inUserScope(
                handler,
                this,
                message,
                message.what.value === 3,
            );
        });
        install(confirm, function (view) {
            return inUserScope(confirm, this, view, true);
        });
        install(setter, function (air, vehicle) {
            var mode =
                vehicle === null ? -1 : vehicle.getInt("DRIVING_MODE_SET", -1);
            var user = scope.get() !== null && mode >= 1 && mode <= 6;
            // OEM returns 0 after IPC acceptance, -1 on disconnect/error. Never replay a send.
            var result = setter.call(this, air, vehicle);
            if (user && result === 0) {
                try {
                    var args = Bundle.$new();
                    args.putString(
                        "mode",
                        [
                            "",
                            "ECO",
                            "COMFORT",
                            "SPORT",
                            "OUTING",
                            "INDIVIDUAL",
                            "SNOW",
                        ][mode],
                    );
                    var saved = call.call(
                        app.getContentResolver(),
                        Uri.parse(
                            "content://ru.big.town.restoremode.restoremodecontentprovider/",
                        ),
                        "driveHookV2",
                        "user",
                        args,
                    );
                    if (saved === null || saved.getInt("protocol", -1) !== 2)
                        throw new Error("provider protocol unavailable");
                    log("vehicle user drive=" + mode + " saved");
                } catch (e) {
                    log(
                        "vehicle user drive=" +
                        mode +
                        " persistence failed: " +
                        e,
                    );
                }
            }
            return result;
        });
    }
    function installEnergyStatisticsMenu() {
        try {
            var activity = Java.use(
                "com.qinggan.app.vehiclesetting.CarSettingActivity",
            );
            var resume = activity.onResume.overload();
            var platform = Java.use("com.qinggan.utils.AppCommonUtils");
            var uri = Java.use("android.net.Uri").parse(
                "content://ru.big.town.restoremode.restoremodecontentprovider/",
            );
            var providerCall = Java.use(
                "android.content.ContentResolver",
            ).call.overload(
                "android.net.Uri",
                "java.lang.String",
                "java.lang.String",
                "android.os.Bundle",
            );
            install(resume, function () {
                var result = resume.call(this);
                try {
                    var menuId = this.getResources().getIdentifier(
                        "rb_menu_energy",
                        "id",
                        "com.qinggan.app.vehiclesetting",
                    );
                    var menu = menuId === 0 ? null : this.findViewById(menuId);
                    if (menu === null) return result;
                    var enabled = false;
                    try {
                        var settings = providerCall.call(
                            this.getContentResolver(),
                            uri,
                            "stockEnergyStatistics",
                            null,
                            null,
                        );
                        enabled =
                            settings !== null &&
                            settings.getBoolean("enabled", false);
                    } catch (error) {
                        log("energy menu settings unavailable: " + error);
                    }
                    // Restore OEM visibility when disabled or the provider is unavailable.
                    menu.setVisibility(enabled || platform.is97C() ? 0 : 8);
                } catch (error) {
                    log("energy menu update unavailable: " + error);
                }
                return result;
            });
        } catch (error) {
            log("energy menu unsupported: " + error);
        }
    }

    function installPowerHoldBridge() {
        var Outcome = {
            ACCEPTED: 1,
            NOT_IN_PARK: 2,
            LOW_BATTERY: 3,
            STATE_UNAVAILABLE: 4,
            TRANSPORT_FAILURE: 5,
            ARMED: 6,
            ACTIVE: 7,
            RESULT_UNKNOWN: 8,
        };

        var managerClass = Java.use(
            "com.qinggan.scene.powerhold.PowerHoldModeManager",
        );
        var stateClass = Java.use("com.qinggan.canbus.VehicleState");
        var gearClass = Java.use("com.qinggan.canbus.GearState");
        var Intent = Java.use("android.content.Intent");
        var Bundle = Java.use("android.os.Bundle");
        var permission =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
        var requestAction = "ru.big.town.anative.POWER_HOLD_OEM_REQUEST";
        var statusAction = "ru.big.town.anative.POWER_HOLD_OEM_STATUS";
        var notification =
            managerClass.notifyActiveStatusChanged.overload("boolean");

        function readyManager() {
            var manager = managerClass.getInstance();
            if (
                manager.mContext.value === null ||
                manager.mCanBusManager.value === null
            ) {
                throw new Error("Power Hold manager is not initialized");
            }
            return manager;
        }

        function publishStatus(manager) {
            var intent = Intent.$new(statusAction);
            intent.setPackage("ru.big.town.anative");
            intent.putExtra
                .overload("java.lang.String", "int")
                .call(intent, "status", manager.getPowerHoldModeStatus());
            app.sendBroadcast
                .overload("android.content.Intent", "java.lang.String")
                .call(app, intent, permission);
        }

        function validationOutcome(manager) {
            var canBus = manager.mCanBusManager.value;
            var gear = canBus.getGearStatus();
            if (gear === null) return Outcome.STATE_UNAVAILABLE;
            if (!gear.equals(gearClass.Parking.value))
                return Outcome.NOT_IN_PARK;
            var soc = canBus.getVehicleState(stateClass.BMS_SOC_DISPLAY.value);
            if (soc < 0 || soc > 100) return Outcome.STATE_UNAVAILABLE;
            return soc < 15 ? Outcome.LOW_BATTERY : Outcome.ACCEPTED;
        }

        var Receiver = Java.registerClass({
            name: "ru.big.town.voyahtune.PowerHoldReceiver_" + Date.now(),
            superClass: Java.use("android.content.BroadcastReceiver"),
            methods: {
                onReceive: {
                    returnType: "void",
                    argumentTypes: [
                        "android.content.Context",
                        "android.content.Intent",
                    ],
                    implementation: function (context, intent) {
                        if (
                            intent === null ||
                            String(intent.getAction()) !== requestAction
                        )
                            return;
                        var response = Bundle.$new();
                        response.putInt("protocol", 1);
                        response.putInt("outcome", Outcome.TRANSPORT_FAILURE);
                        var commandStarted = false;
                        try {
                            var operation = String(
                                intent.getStringExtra("operation"),
                            );
                            var deadline = Number(
                                intent.getLongExtra("deadline", 0),
                            );
                            if (deadline <= Number(Clock.elapsedRealtime())) {
                                this.setResultExtras(response);
                                return;
                            }
                            var manager = readyManager();
                            if (operation === "activate") {
                                var outcome = validationOutcome(manager);
                                if (outcome === Outcome.ACCEPTED) {
                                    var status =
                                        manager.getPowerHoldModeStatus();
                                    if (status === 0) {
                                        if (
                                            deadline <=
                                            Number(Clock.elapsedRealtime())
                                        ) {
                                            this.setResultExtras(response);
                                            return;
                                        }
                                        // The manager owns arming, cancellation and the stock system properties.
                                        commandStarted = true;
                                        manager.enterPowerHoldMode(
                                            intent.getBooleanExtra(
                                                "engineExtender",
                                                false,
                                            ),
                                            -1,
                                        );
                                        status =
                                            manager.getPowerHoldModeStatus();
                                    }
                                    outcome =
                                        status === 1
                                            ? Outcome.ACTIVE
                                            : status === 2
                                                ? Outcome.ARMED
                                                : Outcome.RESULT_UNKNOWN;
                                }
                                response.putInt("outcome", outcome);
                            } else if (operation !== "status") {
                                this.setResultExtras(response);
                                return;
                            }
                            response.putInt(
                                "status",
                                manager.getPowerHoldModeStatus(),
                            );
                        } catch (error) {
                            response.putInt(
                                "outcome",
                                commandStarted
                                    ? Outcome.RESULT_UNKNOWN
                                    : Outcome.STATE_UNAVAILABLE,
                            );
                            log("Power Hold request failed: " + error);
                        }
                        this.setResultExtras(response);
                    },
                },
            },
        });
        var receiver = Receiver.$new();
        app.registerReceiver
            .overload(
                "android.content.BroadcastReceiver",
                "android.content.IntentFilter",
                "java.lang.String",
                "android.os.Handler",
            )
            .call(
                app,
                receiver,
                Java.use("android.content.IntentFilter").$new(requestAction),
                permission,
                null,
            );
        cleanupActions.push(function () {
            app.unregisterReceiver(receiver);
        });
        install(notification, function (active) {
            var result = notification.call(this, active);
            try {
                publishStatus(this);
            } catch (error) {
                log("Power Hold status unavailable: " + error);
            }
            return result;
        });
        try {
            publishStatus(readyManager());
        } catch (error) {
            log("Power Hold initial status unavailable: " + error);
        }
        log("Power Hold manager bridge ready");
    }

    try {
        if (
            healthy() &&
            String(System.getProperty(SENTINEL, "")) === "installed"
        ) {
            log(READY + " already_installed");
            return;
        }
        if (vehicleAir) {
            installVehicleAir();
            System.setProperty(SENTINEL, "installed");
            pulse();
            pulseTimer = setInterval(function () {
                Java.perform(pulse);
            }, 2000);
            log(READY + " vehicleair");
            return;
        }
        installEnergyStatisticsMenu();
        var firmwareAdapter = VoyahAdapters.driveReset();
        var Memory = firmwareAdapter.memory;
        var CanBus = Java.use("com.qinggan.canbus.CanBusManager");
        var Platform = Java.use("com.qinggan.utils.AppCommonUtils");
        var ActivityThread = Java.use("android.app.ActivityThread");
        var Utils = Java.use("com.qinggan.app.vehiclesetting.utils.Utils");
        var Uri = Java.use("android.net.Uri");
        var Bundle = Java.use("android.os.Bundle");
        var scope = Java.use("java.lang.ThreadLocal").$new();
        var JString = Java.use("java.lang.String");
        var query = Java.use("android.content.ContentResolver").query.overload(
            "android.net.Uri",
            "[Ljava.lang.String;",
            "java.lang.String",
            "[Ljava.lang.String;",
            "java.lang.String",
        );
        var uiScope = Java.use("java.lang.ThreadLocal").$new();
        var Fragment = firmwareAdapter.fragment;
        var setter = CanBus.setVehicleAndAirConditionBundleState.overload(
            "android.os.Bundle",
            "android.os.Bundle",
        );

        function individualValue(resolver, key, fallback) {
            var c = query.call(
                resolver,
                Uri.parse("content://qinggan.settings/global"),
                Java.array("java.lang.String", ["value"]),
                "name=?",
                Java.array("java.lang.String", [key]),
                null,
            );
            if (c === null) throw new Error("Individual provider unavailable");
            try {
                if (!c.moveToFirst() || c.isNull(0)) return fallback;
                var raw = String(c.getString(0)).trim();
                if (!/^-?\d+$/.test(raw))
                    throw new Error("Invalid Individual value");
                return Number(raw);
            } finally {
                c.close();
            }
        }

        function selectedTargets() {
            var app = ActivityThread.currentApplication();
            if (app === null) return null;
            var resolver = app.getContentResolver();
            var c = query.call(
                resolver,
                Uri.parse(
                    "content://ru.big.town.restoremode.restoremodecontentprovider/",
                ),
                null,
                null,
                null,
                null,
            );
            if (c === null) return null;
            var mode,
                driveEnabled,
                energyEnabled,
                energy,
                forcedEv,
                maintenance,
                recycle;
            try {
                if (
                    !c.moveToFirst() ||
                    c.getColumnCount() <= 32 ||
                    c.isNull(32)
                )
                    return null; // Missing settings: stock behavior.
                mode = String(c.getString(0));
                driveEnabled = c.getInt(6) === 1;
                energyEnabled = c.getInt(8) === 1;
                energy = String(c.getString(1));
                recycle =
                    c.getInt(7) === 1 && mode !== "SNOW"
                        ? { LOW: 2, MEDIUM: 3, HIGH: 4 }[String(c.getString(2))]
                        : null;
                if (recycle === undefined) return null;
                forcedEv = c.getInt(19) === 1;
                var maintenanceRaw = c.getInt(32);
                if (maintenanceRaw !== 0 && maintenanceRaw !== 1) return null;
                maintenance = maintenanceRaw === 1 ? 2 : 1; // HintSwitch: on=2, off=1.
            } finally {
                c.close();
            }
            var targets = {
                drive: null,
                energy: null,
                maintenance: maintenance,
                recycle: recycle,
            };
            if (forcedEv) targets.energy = 5;
            else if (energyEnabled) {
                var energies = {
                    SMART: 1,
                    Smart: 1,
                    EV: 2,
                    REV: 3,
                    SREV: 4,
                    FORCE_EV: 5,
                };
                if (!Object.prototype.hasOwnProperty.call(energies, energy))
                    return null;
                targets.energy = energies[energy];
            }
            if (!driveEnabled) return targets;
            var profiles = {
                ECO: [1, 2, 1],
                COMFORT: [2, 2, 2],
                SPORT: [3, 3, 3],
                OUTING: [4, 2, 3],
                SNOW: [6, 2, 2],
            };
            if (mode === "INDIVIDUAL") {
                var account = Utils.getAccountId();
                if (account === null || String(account).length === 0)
                    return null;
                var steering = individualValue(
                    resolver,
                    "drive_mode_steeringWheelAssist" + account,
                    2,
                );
                var pedal = individualValue(
                    resolver,
                    "drive_mode_runState" + account,
                    1,
                );
                if (
                    (steering !== 2 && steering !== 3) ||
                    pedal < 1 ||
                    pedal > 3
                )
                    return null;
                targets.drive = [5, steering, pedal];
                return targets;
            }
            if (!Object.prototype.hasOwnProperty.call(profiles, mode))
                return null;
            targets.drive = profiles[mode];
            return targets;
        }

        install(setter, function (air, vehicle) {
            var outgoing = vehicle;
            if (uiScope.get() !== null && vehicle !== null) {
                outgoing = Bundle.$new(vehicle);
                outgoing.putBoolean("__vt_user", true);
                log(
                    "screen request drive=" +
                    vehicle.getInt("DRIVING_MODE_SET", -1) +
                    " energy=" +
                    vehicle.getInt("IVI_SOC_MODESET", -1),
                );
            }
            if (scope.get() !== null && vehicle !== null) {
                outgoing = Bundle.$new(vehicle);
                outgoing.putBoolean("__vt_auto", true);
                // Catch preparation errors only: never replay a setter whose Binder call failed.
                try {
                    var targets = selectedTargets();
                    if (
                        targets !== null &&
                        vehicle.containsKey("DRIVING_MODE_SET")
                    ) {
                        outgoing = Bundle.$new(vehicle);
                        outgoing.putBoolean("__vt_auto", true);
                        var profile = targets.drive;
                        if (profile !== null) {
                            outgoing.putInt("DRIVING_MODE_SET", profile[0]);
                            outgoing.putInt("EPS_MODE_SET", profile[1]);
                            outgoing.putInt("PROP_MODE_SET", profile[2]);
                        }
                        if (targets.recycle !== null)
                            outgoing.putInt(
                                "HUM_ENERGY_PTREGEN_LEVL",
                                targets.recycle,
                            );
                        if (targets.energy !== null)
                            outgoing.putInt("IVI_SOC_MODESET", targets.energy);
                        outgoing.putInt(
                            "ASC_MAINTAIN_SWITCH",
                            targets.maintenance,
                        );
                        // Snow owns recuperation in the OEM drive-mode handler. Do not let the
                        // reset's high-regen field race it in the unordered TX77 bundle.
                        if (profile !== null && profile[0] === 6)
                            outgoing.remove("HUM_ENERGY_PTREGEN_LEVL");
                        log(
                            "replace " +
                            scope.get() +
                            " drive=" +
                            (profile === null ? "stock" : profile[0]) +
                            " energy=" +
                            (targets.energy === null
                                ? "stock"
                                : targets.energy) +
                            " suspension=" +
                            targets.maintenance,
                        );
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
                if (!firmwareAdapter.supportsReset(Platform)) return method.call(this);
                var previous = scope.get();
                scope.set(JString.$new(name));
                try {
                    return method.call(this);
                } finally {
                    if (previous === null) scope.remove();
                    else scope.set(previous);
                }
            });
        });
        ["setDriveMode", "setPowerMode"].forEach(function (name) {
            var method = Fragment[name].overload("int");
            install(method, function (value) {
                if (!firmwareAdapter.supportsReset(Platform)) return method.call(this, value);
                var previous = uiScope.get();
                uiScope.set(JString.$new(name));
                try {
                    return method.call(this, value);
                } finally {
                    if (previous === null) uiScope.remove();
                    else uiScope.set(previous);
                }
            });
        });
        var hint = Fragment.onHintSwitchClick.overload(
            "com.qinggan.canbus.VehicleState",
            "int",
        );
        install(hint, function (state, value) {
            var previous = uiScope.get();
            if (firmwareAdapter.supportsReset(Platform) && String(state) === "IVI_SOC_MODESET")
                uiScope.set(JString.$new("energy switch"));
            try {
                return hint.call(this, state, value);
            } finally {
                if (previous === null) uiScope.remove();
                else uiScope.set(previous);
            }
        });
        var single = CanBus.setVehicleState.overload(
            "com.qinggan.canbus.VehicleState",
            "int",
        );
        var providerCall = Java.use(
            "android.content.ContentResolver",
        ).call.overload(
            "android.net.Uri",
            "java.lang.String",
            "java.lang.String",
            "android.os.Bundle",
        );
        install(single, function (state, value) {
            if (
                uiScope.get() !== null &&
                String(state) === "IVI_SOC_MODESET" &&
                value >= 1 &&
                value <= 5
            ) {
                try {
                    var args = Bundle.$new();
                    args.putString(
                        "energy",
                        ["", "SMART", "EV", "REV", "SREV", "FORCE_EV"][value],
                    );
                    providerCall.call(
                        ActivityThread.currentApplication().getContentResolver(),
                        Uri.parse(
                            "content://ru.big.town.restoremode.restoremodecontentprovider/",
                        ),
                        "driveHookV2",
                        "user",
                        args,
                    );
                    log("screen single energy=" + value);
                } catch (e) {
                    log("screen energy persistence unavailable: " + e);
                }
            }
            return single.call(this, state, value);
        });
        // This method is the stock UI's explicit recuperation selection. A drive-profile
        // bundle also contains regen, but must never be mistaken for this independent choice.
        var recycler = Fragment.setEnergyRecyclerMode.overload("int");
        install(recycler, function (value) {
            var result = recycler.call(this, value);
            if (firmwareAdapter.supportsReset(Platform) && value >= 2 && value <= 4) {
                try {
                    var args = Bundle.$new();
                    args.putString(
                        "recycle",
                        ["LOW", "MEDIUM", "HIGH"][value - 2],
                    );
                    providerCall.call(
                        ActivityThread.currentApplication().getContentResolver(),
                        Uri.parse(
                            "content://ru.big.town.restoremode.restoremodecontentprovider/",
                        ),
                        "driveHookV2",
                        "user",
                        args,
                    );
                    log("screen recuperation selection=" + value);
                } catch (e) {
                    log("screen recuperation persistence unavailable: " + e);
                }
            }
            return result;
        });
        var powerHoldHookStart = installed.length;
        var powerHoldCleanupStart = cleanupActions.length;
        try {
            installPowerHoldBridge();
        } catch (error) {
            while (cleanupActions.length > powerHoldCleanupStart) {
                try {
                    cleanupActions.pop()();
                } catch (_) { }
            }
            while (installed.length > powerHoldHookStart) {
                try {
                    installed.pop().implementation = null;
                } catch (_) { }
            }
            log("Power Hold manager bridge unavailable: " + error);
        }
        System.setProperty(SENTINEL, "installed");
        pulse();
        pulseTimer = setInterval(function () {
            Java.perform(pulse);
        }, 2000);
        log(READY);
    } catch (e) {
        cleanupActions.forEach(function (cleanup) {
            try {
                cleanup();
            } catch (_) { }
        });
        for (var i = installed.length - 1; i >= 0; i--) {
            try {
                installed[i].implementation = null;
            } catch (_) { }
        }
        log("[drive-reset] hook failed stage=install reason=" + e);
    }
});
