"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const source = fs.readFileSync(
    path.join(__dirname, "../od/inject/voyahtune_drive_reset.js"),
    "utf8",
);
const start = source.indexOf("    function installPowerHoldBridge() {");
const end = source.indexOf("\n\n    try {", start);
assert.ok(start >= 0 && end > start);
const bridge = source.slice(start, end);

function fixture(options = {}) {
    const state = {
        status: 0,
        soc: 80,
        parking: true,
        now: 100_000,
        ...options,
    };
    const calls = [];
    const events = [];
    const cleanups = [];
    const permission = "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    let receiver;
    let originalNotifications = 0;

    function overload(original) {
        const method = {
            overload() {
                return method;
            },
            call(target, ...args) {
                return original.apply(target, args);
            },
        };
        return method;
    }

    class Bundle {
        constructor() {
            this.values = {};
        }
        putInt(key, value) {
            this.values[key] = value;
        }
    }

    class Intent {
        constructor(action) {
            this.action = action;
            this.values = {};
            this.putExtra = overload(function (key, value) {
                this.values[key] = value;
            });
        }
        setPackage(value) {
            this.packageName = value;
        }
        getAction() {
            return this.action;
        }
        getStringExtra(key) {
            return this.values[key] ?? null;
        }
        getBooleanExtra(key, fallback) {
            return this.values[key] ?? fallback;
        }
        getLongExtra(key, fallback) {
            return this.values[key] ?? fallback;
        }
    }

    const notification = overload(function () {
        originalNotifications++;
    });
    const manager = {
        mContext: { value: state.uninitialized ? null : {} },
        mCanBusManager: {
            value: {
                getGearStatus() {
                    return {
                        equals() {
                            return state.parking;
                        },
                    };
                },
                getVehicleState() {
                    if (state.slowValidation) state.now += 4_000;
                    return state.soc;
                },
            },
        },
        getPowerHoldModeStatus() {
            return state.status;
        },
        enterPowerHoldMode(engineExtender, duration) {
            calls.push({ engineExtender, duration });
            state.status = 2;
            notification.implementation.call(manager, true);
            if (state.lostReply) throw new Error("Reply lost after arming");
        },
    };
    const application = {
        registerReceiver: overload(
            function (registered, filter, requiredPermission) {
                assert.equal(requiredPermission, permission);
                assert.equal(
                    filter.action,
                    "ru.big.town.anative.POWER_HOLD_OEM_REQUEST",
                );
                receiver = registered;
            },
        ),
        unregisterReceiver(registered) {
            assert.equal(registered, receiver);
        },
        sendBroadcast: overload(function (intent, requiredPermission) {
            assert.equal(intent.packageName, "ru.big.town.anative");
            assert.equal(
                intent.action,
                "ru.big.town.anative.POWER_HOLD_OEM_STATUS",
            );
            assert.equal(requiredPermission, permission);
            events.push(intent.values.status);
        }),
    };
    const classes = {
        "com.qinggan.scene.powerhold.PowerHoldModeManager": {
            getInstance() {
                return manager;
            },
            notifyActiveStatusChanged: notification,
        },
        "com.qinggan.canbus.VehicleState": { BMS_SOC_DISPLAY: { value: 615 } },
        "com.qinggan.canbus.GearState": { Parking: { value: "Parking" } },
        "android.content.Intent": { $new: (action) => new Intent(action) },
        "android.os.Bundle": { $new: () => new Bundle() },
        "android.content.BroadcastReceiver": {},
        "android.content.IntentFilter": { $new: (action) => ({ action }) },
    };
    vm.runInNewContext(bridge + "\ninstallPowerHoldBridge();", {
        Java: {
            use(name) {
                assert.ok(classes[name], name);
                return classes[name];
            },
            registerClass(specification) {
                return {
                    $new() {
                        return {
                            onReceive:
                                specification.methods.onReceive.implementation,
                            setResultExtras(result) {
                                this.response = result.values;
                            },
                        };
                    },
                };
            },
        },
        app: application,
        Clock: { elapsedRealtime: () => state.now },
        cleanupActions: cleanups,
        install(method, implementation) {
            method.implementation = implementation;
        },
        log() {},
    });
    return {
        state,
        calls,
        events,
        request(operation = "activate", extras = {}) {
            const intent = new Intent(
                "ru.big.town.anative.POWER_HOLD_OEM_REQUEST",
            );
            intent.values = {
                operation,
                deadline: state.now + 3_000,
                ...extras,
            };
            receiver.onReceive(application, intent);
            return receiver.response;
        },
        cancel() {
            state.status = 0;
            notification.implementation.call(manager, false);
            assert.ok(originalNotifications > 0);
        },
        close() {
            cleanups.forEach((cleanup) => cleanup());
        },
    };
}

const standard = fixture();
assert.equal(standard.request().outcome, 6);
assert.deepEqual(standard.calls, [{ engineExtender: false, duration: -1 }]);
assert.equal(standard.request().outcome, 6);
assert.equal(standard.calls.length, 1);
standard.cancel();
assert.deepEqual(standard.events, [0, 2, 0]);
assert.equal(standard.request("status").status, 0);
assert.equal(standard.calls.length, 1);
standard.close();

const optedIn = fixture();
assert.equal(optedIn.request("activate", { engineExtender: true }).outcome, 6);
assert.deepEqual(optedIn.calls, [{ engineExtender: true, duration: -1 }]);

for (const [options, outcome] of [
    [{ parking: false }, 2],
    [{ soc: 14 }, 3],
    [{ soc: -1 }, 4],
    [{ soc: 255 }, 4],
    [{ uninitialized: true }, 4],
    [{ slowValidation: true }, 5],
    [{ status: 1 }, 7],
]) {
    const current = fixture(options);
    assert.equal(current.request().outcome, outcome);
    assert.equal(current.calls.length, 0);
}

const expired = fixture();
assert.equal(
    expired.request("activate", { deadline: expired.state.now }).outcome,
    5,
);
assert.equal(expired.request("unexpected").outcome, 5);
assert.equal(expired.calls.length, 0);

const lostReply = fixture({ lostReply: true });
assert.equal(lostReply.request().outcome, 8);
assert.equal(lostReply.calls.length, 1);
assert.deepEqual(lostReply.events, [0, 2]);

console.log(
    "PASS: OD Power Hold manager lifecycle, opt-in, validation, deadlines and lost replies",
);
