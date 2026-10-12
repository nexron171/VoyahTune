"use strict";
// Execute the production agent against a synchronous OEM reset/Binder facade.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const source = fs.readFileSync(
    path.join(__dirname, "../od/inject/voyahtune_acc_restore.js"),
    "utf8"
);

function fixture(options = {}) {
    const scenario = Object.assign(
        {
            mode: "SPORT",
            enabled: 1,
            energyEnabled: 0,
            energy: "EV",
            forcedEv: 0,
            maintenance: 0,
            debug: 0,
            platform: "H97X",
            guest: 1,
            acc: 0,
            events: [],
            singles: [],
            calls: [],
            thread: 1,
            queries: 0,
            closes: 0,
            originals: 0,
            sends: [],
            logs: [],
            individual: {},
            dispatches: 0
        },
        options
    );
    const properties = new Map();
    const methods = [];
    function method(original) {
        const m = {
            implementation: null,
            overload() {
                return m;
            },
            call(receiver, ...args) {
                return original.apply(receiver, args);
            },
            invoke(receiver, ...args) {
                return (m.implementation || original).apply(receiver, args);
            }
        };
        methods.push(m);
        return m;
    }
    class Bundle {
        constructor(other) {
            this.values = { ...(other ? other.values : {}) };
        }
        getInt(key, fallback = 0) {
            return this.values[key] ?? fallback;
        }
        containsKey(key) {
            return Object.hasOwn(this.values, key);
        }
        putInt(key, value) {
            this.values[key] = value;
        }
        getBoolean(key, fallback = false) {
            return this.values[key] ?? fallback;
        }
        putBoolean(key, v) {
            this.values[key] = v;
        }
        getLong(key, fallback = 0) {
            const value = this.values[key] ?? fallback;
            return { toString: () => String(value) };
        }
        putLong(key, v) {
            this.values[key] = v;
        }
        getString(key) {
            return this.values[key] ?? null;
        }
        putString(key, v) {
            this.values[key] = v;
        }
        remove(key) {
            delete this.values[key];
        }
    }
    const taskInit = method(function () {});
    const taskSend = method(function (values) {
        scenario.executed = values;
        return true;
    });
    scenario.tasks = [];
    const setter = method(function (air, vehicle) {
        scenario.sends.push({ air, vehicle });
        if (scenario.sendError) throw new Error("Binder failure");
        const task = {};
        taskInit.invoke(task, receiver, receiver);
        scenario.tasks.push({ task, values: new Map(Object.entries(vehicle.values)) });
        return scenario.rejected ? -1 : 0;
    });
    const feedback = method(function (vehicle, value) {
        scenario.events.push([vehicle, value]);
    });
    const single = method(function (vehicle, value) {
        scenario.singles.push([vehicle, value]);
    });
    const receiver = {
        contentResolver: { value: {} },
        id: 1,
        getAccStatus() {
            return scenario.acc;
        },
        isH97X() {
            return scenario.platform === "H97X";
        },
        isH97Y() {
            return scenario.platform === "H97Y";
        }
    };
    const parser = method(function (data, notify) {
        scenario.originals++;
        scenario.calls.push([Array.from(data), notify]);
        if (scenario.beforeSend) scenario.beforeSend();
        if ((data[0] & 7) === 2 && scenario.acc !== 2) {
            single.invoke(this, "IVI_PRIVACY_SETTING", 1);
            const b = new Bundle();
            b.values = {
                DRIVING_MODE_SET: scenario.guest === 1 ? 1 : 2,
                EPS_MODE_SET: 2,
                PROP_MODE_SET: scenario.guest === 1 ? 1 : 2,
                HUM_ENERGY_PTREGEN_LEVL: 4
            };
            scenario.originalBundle = b;
            setter.invoke(this, null, b);
            feedback.invoke(this, "DRIVING_MODE_SET", b.getInt("DRIVING_MODE_SET"));
            single.invoke(this, "VEHICLE_AMBIENT_LIGHT_COLOR", scenario.guest === 1 ? 19 : 5);
            scenario.acc = 2;
        }
        if ((data[0] & 7) === 0 || (data[0] & 7) === 1) scenario.acc = 0;
        scenario.otherBcMWork = true;
        feedback.invoke(this, "CHILD_LOCK_CONTROL", 2);
        return 123;
    });
    function cursor(value, individual) {
        return {
            moveToFirst() {
                return individual ? value !== undefined : !scenario.empty;
            },
            getColumnCount() {
                return scenario.columns ?? 33;
            },
            getInt(i) {
                return i === 6
                    ? scenario.enabled
                    : i === 12
                      ? scenario.debug
                      : i === 7
                        ? scenario.recycleEnabled || 0
                        : i === 8
                          ? scenario.energyEnabled
                          : i === 19
                            ? scenario.forcedEv
                            : i === 32
                              ? scenario.maintenance
                              : 0;
            },
            getString(i) {
                return individual
                    ? value
                    : i === 1
                      ? scenario.energy
                      : i === 2
                        ? scenario.recycle || "LOW"
                        : scenario.mode;
            },
            isNull() {
                return individual ? value === null : scenario.maintenance === null;
            },
            close() {
                scenario.closes++;
            }
        };
    }
    const query = method(function (uri, projection, selection, args) {
        scenario.queries++;
        if (uri.includes("qinggan.settings")) {
            if (scenario.individualError) throw new Error("OEM provider failure");
            return cursor(scenario.individual[args[0]], true);
        }
        if (scenario.queryError) throw new Error("VoyahTune provider failure");
        return scenario.nullCursor ? null : cursor();
    });
    const timers = [];
    scenario.revision = 0;
    scenario.startup = "pending";
    scenario.providerAcc = scenario.acc;
    scenario.accObservations = [];
    scenario.cycle = 0;
    scenario.settingsStartup = "pending";
    scenario.settingsPasses = 0;
    const provider = method(function (uri, name, action, args) {
        if (scenario.queryError) throw new Error("provider unavailable");
        if (action === "acc") {
            const observedAcc = args.getInt("acc", -1);
            scenario.accObservations.push(observedAcc);
            if (observedAcc === 0 || observedAcc === 2) {
                if (scenario.providerAcc === 0 && observedAcc === 2) {
                    scenario.cycle++;
                    scenario.revision++;
                    scenario.startup = "pending";
                    scenario.settingsStartup = "pending";
                }
                scenario.providerAcc = observedAcc;
            }
        }
        if (action === "dispatchSettings") scenario.dispatches++;
        if (action === "user") {
            if (args.containsKey("mode")) scenario.mode = args.getString("mode");
            if (args.containsKey("energy")) scenario.energy = args.getString("energy");
            scenario.revision++;
            scenario.startup = "selected";
        }
        const result = new Bundle();
        result.putInt("protocol", 2);
        result.putInt("acc", scenario.providerAcc);
        result.putLong("revision", scenario.revision);
        result.putLong("cycle", scenario.cycle);
        result.putString("energy", scenario.energy);
        result.putBoolean("forcedEv", !!scenario.forcedEv);
        result.putBoolean("smartSaveEnabled", !!scenario.smartSaveEnabled);
        result.putString("startup", scenario.startup);
        if (action === "dispatchSettings") {
            const dispatched =
                scenario.providerAcc === 2 &&
                scenario.settingsStartup === "pending" &&
                !scenario.dispatchRejected;
            if (dispatched) {
                scenario.settingsPasses++;
                scenario.settingsStartup = "submitted";
            }
            result.putString("settingsStartup", scenario.settingsStartup);
            result.putBoolean("settingsDispatched", dispatched);
        }
        if (action === "claim") {
            const claimed =
                scenario.startup === "pending" && String(args.getLong("revision")) === String(scenario.revision);
            result.putBoolean("claimed", claimed);
            if (claimed) scenario.startup = "claimed";
        }
        if (action === "complete")
            scenario.startup = args.getBoolean("accepted") ? "submitted" : "uncertain";
        return result;
    });
    const classes = {
        "android.os.SystemClock": {
            elapsedRealtime() {
                return 100000;
            }
        },
        "android.os.Process": {
            myPid() {
                return 1;
            }
        },
        "java.io.FileWriter": {
            $new() {
                return {
                    write: {
                        overload() {
                            return {
                                call(out, line) {
                                    scenario.health = line;
                                }
                            };
                        }
                    },
                    close() {}
                };
            }
        },
        "android.os.Binder": {
            getCallingUid() {
                return scenario.caller ?? -1;
            },
            clearCallingIdentity() {
                return 0;
            },
            restoreCallingIdentity() {}
        },
        "com.qinggan.canbus.service.CanBusService": {},
        "android.app.ActivityThread": {
            currentActivityThread() {
                let seen = false;
                return {
                    mServices: {
                        value: {
                            values() {
                                return {
                                    iterator() {
                                        return {
                                            hasNext() {
                                                return !seen;
                                            },
                                            next() {
                                                seen = true;
                                                return {
                                                    getClass() {
                                                        return {
                                                            getName() {
                                                                return "com.qinggan.canbus.service.CanBusService";
                                                            }
                                                        };
                                                    },
                                                    mCanBusComponent: { value: receiver }
                                                };
                                            }
                                        };
                                    }
                                };
                            }
                        }
                    }
                };
            },
            currentApplication() {
                return {
                    getContentResolver() {
                        return {};
                    },
                    getPackageManager() {
                        return {
                            getApplicationInfo(name) {
                                return { uid: { value: name === "ru.big.town.anative" ? 10 : 20 } };
                            }
                        };
                    }
                };
            }
        },
        "com.qinggan.canbus.VehicleState": {
            valueOf(s) {
                return s;
            }
        },
        "com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl$ModeSettingTask":
            { $init: taskInit, setVehicleMode: taskSend },
        "java.util.WeakHashMap": {
            $new() {
                const m = new Map();
                return {
                    put(k, v) {
                        m.set(k, v);
                    },
                    remove(k) {
                        const v = m.get(k) ?? null;
                        m.delete(k);
                        return v;
                    }
                };
            }
        },
        "java.util.HashMap": {
            $new(v) {
                const m = new Map(v);
                m.remove = (k) => m.delete(k);
                return m;
            }
        },
        "java.util.concurrent.locks.ReentrantLock": {
            $new() {
                return { lock() {}, unlock() {} };
            }
        },
        "android.util.Log": { i() {} },
        "java.lang.System": {
            getProperty(k, fallback) {
                return properties.get(k) ?? fallback;
            },
            setProperty(k, v) {
                properties.set(k, v);
            },
            identityHashCode(o) {
                return o.id;
            }
        },
        "java.lang.String": {
            $new(v) {
                return v;
            }
        },
        "java.lang.ThreadLocal": {
            $new() {
                const values = new Map();
                return {
                    get() {
                        return values.get(scenario.thread) ?? null;
                    },
                    set(v) {
                        values.set(scenario.thread, v);
                    },
                    remove() {
                        values.delete(scenario.thread);
                    }
                };
            }
        },
        "com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl": {
            onBCM_PEPSChangeData: scenario.missingMethod ? undefined : parser,
            setVehicleAndAirConditionBundleState: setter,
            setVehicleState: single
        },
        "com.qinggan.canbus.service.BaseCanBusComponent": { onVehicleStateChanged: feedback },
        "com.qinggan.provider.QGSettings$System": { getInt: method(() => scenario.guest) },
        "com.qinggan.account.AccountUserManager": {
            getInstance() {
                return {
                    AccountInfoBean() {
                        return {
                            isGuest: { value: true },
                            getName() {
                                return "guest";
                            }
                        };
                    }
                };
            }
        },
        "android.net.Uri": {
            parse(s) {
                return s;
            }
        },
        "android.os.Bundle": {
            $new(b) {
                return new Bundle(b);
            }
        },
        "android.content.ContentResolver": { query, call: provider }
    };
    const context = vm.createContext({
        setInterval(fn) {
            return 1;
        },
        clearInterval() {},
        setTimeout(fn) {
            timers.push(fn);
        },
        Java: {
            perform(fn) {
                fn();
            },
            use(name) {
                assert.ok(classes[name], name);
                return classes[name];
            },
            array(type, values) {
                return values;
            },
            cast(value) {
                return value;
            },
            retain(v) {
                return v;
            },
            choose(name, callbacks) {
                callbacks.onMatch(receiver);
                callbacks.onComplete();
            }
        },
        console: {
            log(s) {
                scenario.logs.push(s);
            }
        }
    });
    scenario.install = () => vm.runInContext(source, context);
    scenario.frame = (data = [2, 0, 0, 0, 0, 0, 0, 0], notify = true) =>
        parser.invoke(receiver, data, notify);
    scenario.plain = (owner = receiver) => {
        const b = new Bundle();
        b.values = { DRIVING_MODE_SET: 1, EPS_MODE_SET: 2, PROP_MODE_SET: 1 };
        return setter.invoke(owner, null, b);
    };
    scenario.feedback = () => feedback.invoke(receiver, "DRIVING_MODE_SET", 1);
    receiver.setVehicleAndAirConditionBundleState = (a, b) => setter.invoke(receiver, a, b);
    scenario.bootstrap = () => timers.shift()();
    scenario.execute = (index) =>
        taskSend.invoke(scenario.tasks[index].task, scenario.tasks[index].values);
    scenario.request = (values, caller = -1) => {
        scenario.caller = caller;
        const b = new Bundle();
        b.values = values;
        try {
            return setter.invoke(receiver, null, b);
        } finally {
            scenario.caller = -1;
        }
    };
    scenario.install();
    if (!scenario.missingMethod) assert.equal(scenario.health, "1|100|v2\n");
    assert.equal(scenario.queries, 0, "Installation must not wait for a settings provider");
    if (!scenario.missingMethod) assert.ok(scenario.logs.includes("[acc-restore] hook ready v2"));
    scenario.methods = methods;
    return scenario;
}

for (const [mode, values] of Object.entries({
    ECO: [1, 2, 1],
    COMFORT: [2, 2, 2],
    SPORT: [3, 3, 3],
    OUTING: [4, 2, 3],
    INDIVIDUAL: [5, 2, 1],
    SNOW: [6, 2, 2]
})) {
    const scenario = fixture({ mode, energyEnabled: 1, energy: "REV", maintenance: 1 });
    assert.equal(scenario.frame(), 123);
    assert.equal(scenario.originals, 1);
    assert.equal(scenario.sends.length, 1);
    const v = scenario.sends[0].vehicle.values;
    assert.deepEqual([v.DRIVING_MODE_SET, v.EPS_MODE_SET, v.PROP_MODE_SET], values);
    assert.equal(v.IVI_SOC_MODESET, 3);
    assert.equal(v.ASC_MAINTAIN_SWITCH, 2);
    assert.equal(v.HUM_ENERGY_PTREGEN_LEVL, mode === "SNOW" ? undefined : 4);
    assert.equal(scenario.originalBundle.values.DRIVING_MODE_SET, 1);
    assert.deepEqual(scenario.events, [
        ["DRIVING_MODE_SET", values[0]],
        ["CHILD_LOCK_CONTROL", 2]
    ]);
    assert.equal(scenario.singles[0][0], "IVI_PRIVACY_SETTING");
    assert.equal(scenario.singles[1][1], { 1: 19, 2: 5, 3: 64, 4: 54, 5: 10, 6: 9 }[values[0]]);
    assert.equal(scenario.acc, 2);
    assert.equal(scenario.otherBcMWork, true);
    assert.equal(scenario.dispatches, 1, "remaining settings are dispatched after the ACC parser");
    scenario.frame(); // Repeated ON frame is not another ACC edge.
    assert.equal(scenario.sends.length, 1);
    assert.equal(scenario.originals, 2);
    assert.equal(scenario.dispatches, 1);
}
{
    const scenario = fixture({ debug: 1 });
    scenario.frame();
    assert.equal(
        scenario.sends[0].vehicle.values.DRIVING_MODE_SET,
        3,
        "diagnostic visibility must not suppress ACC restoration"
    );
}
for (const options of [
    { mode: "bad" },
    { mode: "__proto__" },
    { queryError: true },
    { nullCursor: true },
    { empty: true },
    { columns: 6 },
    { platform: "H97C" },
    { guest: 2 },
    { maintenance: null },
    { maintenance: 8 },
    { mode: "INDIVIDUAL", individualError: true },
    { mode: "INDIVIDUAL", individual: { drive_mode_runStateguest: "8" } },
    { mode: "INDIVIDUAL", individual: { drive_mode_steeringWheelAssistguest: "bad" } }
]) {
    const scenario = fixture(options);
    scenario.frame();
    assert.equal(scenario.sends.length, 1);
    assert.deepEqual(
        scenario.sends[0].vehicle.values,
        scenario.originalBundle.values,
        JSON.stringify(options)
    );
    assert.equal(scenario.events[0][1], options.guest === 2 ? 2 : 1);
}
{
    const scenario = fixture({ sendError: true });
    assert.throws(scenario.frame, /Binder failure/);
    assert.equal(scenario.sends.length, 1);
    scenario.sendError = false;
    scenario.plain();
    scenario.feedback();
    assert.equal(scenario.sends[1].vehicle.values.DRIVING_MODE_SET, 1); // Scope cleared after failure.
    assert.deepEqual(scenario.events, [["DRIVING_MODE_SET", 1]]);
}
{
    const scenario = fixture({ rejected: true });
    scenario.frame();
    assert.equal(scenario.sends.length, 1);
    assert.equal(scenario.events[0][1], 1); // Do not claim a target when queuing was rejected.
}
{
    const scenario = fixture();
    scenario.beforeSend = () => {
        scenario.thread = 2;
        scenario.plain();
        scenario.thread = 1;
        scenario.plain({ id: 99 }); // An unrelated component on the same thread is also untouched.
    };
    scenario.frame();
    assert.deepEqual(
        scenario.sends.map((s) => s.vehicle.values.DRIVING_MODE_SET),
        [1, 1, 3]
    );
    scenario.install();
    scenario.beforeSend = null;
    scenario.acc = 0;
    scenario.frame();
    assert.equal(scenario.originals, 2);
    assert.ok(scenario.logs.includes("[acc-restore] hook ready v2 already_installed"));
}
{
    const scenario = fixture({ missingMethod: true });
    assert.ok(scenario.methods.every((m) => m.implementation === null));
    assert.ok(scenario.logs.some((l) => l.startsWith("[acc-restore] hook failed stage=install")));
    scenario.frame();
    assert.equal(scenario.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
}
for (const [energy, expected] of Object.entries({ SMART: 1, EV: 2, REV: 3, SREV: 4 })) {
    const scenario = fixture({ energyEnabled: 1, energy, enabled: 0, maintenance: 0 });
    scenario.frame();
    assert.equal(scenario.sends[0].vehicle.values.IVI_SOC_MODESET, expected);
    assert.equal(scenario.sends[0].vehicle.values.ASC_MAINTAIN_SWITCH, 1);
    assert.equal(scenario.events[0][1], 1);
    assert.equal(scenario.singles[1][1], 19);
}
{
    const scenario = fixture({ forcedEv: 1, energyEnabled: 1, energy: "SREV" });
    scenario.frame();
    assert.equal(scenario.sends[0].vehicle.values.IVI_SOC_MODESET, 5);
}
{
    const scenario = fixture();
    scenario.frame([0, 0, 0, 0, 0, 0, 0, 0], false);
    assert.equal(scenario.sends.length, 0);
    assert.equal(scenario.originals, 1);
    assert.equal(scenario.calls[0][1], false);
    assert.equal(scenario.otherBcMWork, true);
    scenario.plain();
    assert.equal(scenario.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
}
{
    const scenario = fixture();
    scenario.frame();
    scenario.acc = 0;
    scenario.mode = "COMFORT";
    scenario.energyEnabled = 1;
    scenario.energy = "SREV";
    scenario.frame();
    assert.equal(scenario.sends[1].vehicle.values.DRIVING_MODE_SET, 2);
    assert.equal(scenario.sends[1].vehicle.values.IVI_SOC_MODESET, 4);
}
console.log(
    "PASS: guest ACC edge, modes, feedback/color, stock parser, isolation, fallback and no replay"
);

{
    const scenario = fixture({ acc: 2, energyEnabled: 1, energy: "SREV" });
    scenario.bootstrap();
    assert.equal(scenario.dispatches, 1, "late attach also dispatches the ACC settings pass");
    assert.equal(scenario.sends.length, 1);
    assert.equal(scenario.startup, "submitted");
    assert.equal(scenario.sends[0].vehicle.values.DRIVING_MODE_SET, 3);
    assert.equal(scenario.sends[0].vehicle.values.IVI_SOC_MODESET, 4);
    scenario.bootstrap();
    assert.equal(scenario.sends.length, 1, "readiness timer cannot resend a submitted startup");
}
{
    const scenario = fixture({ acc: 2, dispatchRejected: true });
    scenario.bootstrap();
    assert.equal(scenario.dispatches, 2, "failed settings start is retried by readiness check");
    scenario.dispatchRejected = false;
    scenario.bootstrap();
    assert.equal(scenario.dispatches, 3);
    scenario.bootstrap();
    assert.equal(scenario.dispatches, 3, "successful settings dispatch is not repeated");
}
{
    const scenario = fixture();
    scenario.frame();
    scenario.request({ DRIVING_MODE_SET: 1, __vt_user: true }, 20);
    scenario.execute(0);
    assert.equal(
        scenario.executed.get("DRIVING_MODE_SET"),
        3,
        "already queued packets are not revalidated or rewritten"
    );
    scenario.execute(1);
    assert.equal(scenario.executed.get("DRIVING_MODE_SET"), 1);
    assert.equal(scenario.mode, "ECO");
}
{
    const scenario = fixture({ acc: 2, energyEnabled: 1 });
    scenario.request({ IVI_SOC_MODESET: 4 }, 10);
    assert.equal(scenario.energy, "SREV");
    scenario.request(
        { DRIVING_MODE_SET: 1, EPS_MODE_SET: 2, PROP_MODE_SET: 1, __vt_auto: true },
        20
    );
    assert.equal(scenario.sends[1].vehicle.values.DRIVING_MODE_SET, 3);
    assert.equal(scenario.sends[1].vehicle.values.IVI_SOC_MODESET, 4);
    assert.equal(scenario.sends[1].vehicle.values.__vt_auto, undefined);
}

assert.ok(!source.includes("Java.choose("), "Never enumerate the heap of the 32-bit OEM service");

function temporaryEnergy(scenario, mode) {
    return {
        IVI_SOC_MODESET: mode,
        __vt_temporary_energy: true,
        __vt_energy_revision: scenario.revision,
        __vt_energy_cycle: scenario.cycle
    };
}

{
    const scenario = fixture({ acc: 2, energy: "SREV", smartSaveEnabled: true });
    for (const mode of [2, 4, 2]) {
        assert.equal(scenario.request(temporaryEnergy(scenario, mode), 10), 0);
        scenario.execute(scenario.tasks.length - 1);
        assert.equal(scenario.executed.get("IVI_SOC_MODESET"), mode);
        assert.equal(scenario.energy, "SREV", "temporary modes cannot replace user intent");
        assert.equal(scenario.revision, 0);
        for (const key of ["__vt_temporary_energy", "__vt_energy_revision", "__vt_energy_cycle"]) {
            assert.equal(scenario.sends.at(-1).vehicle.values[key], undefined);
        }
    }
    scenario.smartSaveEnabled = false;
    assert.equal(scenario.request(temporaryEnergy(scenario, 2), 10), -1);
    assert.equal(scenario.request(temporaryEnergy(scenario, 4), 10), 0);
    scenario.execute(scenario.tasks.length - 1);
    assert.equal(scenario.executed.get("IVI_SOC_MODESET"), 4);
}

for (const cancel of [
    scenario => scenario.request({ IVI_SOC_MODESET: 3, __vt_user: true }, 20),
    scenario => { scenario.smartSaveEnabled = false; },
    scenario => { scenario.forcedEv = true; },
    scenario => { scenario.providerAcc = 0; },
    scenario => { scenario.cycle++; },
    scenario => { scenario.queryError = true; }
]) {
    const scenario = fixture({ acc: 2, energy: "SREV", smartSaveEnabled: true });
    assert.equal(scenario.request(temporaryEnergy(scenario, 2), 10), 0);
    cancel(scenario);
    assert.equal(scenario.execute(0), false, "stale queued EV must be cancelled");
    assert.equal(scenario.executed, undefined);
}

{
    const scenario = fixture({ acc: 2, energy: "SREV", smartSaveEnabled: true });
    scenario.request(temporaryEnergy(scenario, 2), 20);
    assert.equal(scenario.energy, "SREV", "only Native can mark a command temporary");
    scenario.request({ IVI_SOC_MODESET: 3 }, 10);
    assert.equal(scenario.energy, "REV", "explicit Native selections still persist");
    assert.equal(scenario.request(temporaryEnergy(scenario, 4), 10), -1);
}
console.log("PASS: temporary energy, immediate disable restore and queued command cancellation");

{
    const scenario = fixture({ acc: 2 });
    scenario.startup = "selected"; // durable provider state from VehicleAir or widget, including reattach
    scenario.bootstrap();
    assert.equal(scenario.sends.length, 0, "recorded user selection suppresses startup restore");
}
{
    const scenario = fixture({ acc: 2 });
    scenario.request({ DRIVING_MODE_SET: 2, __vt_user: true }, 20);
    scenario.bootstrap();
    assert.equal(
        scenario.sends.length,
        1,
        "screen selection before readiness cancels startup restore"
    );
    assert.equal(scenario.mode, "COMFORT");
}

for (const [recycle, level] of Object.entries({ LOW: 2, MEDIUM: 3, HIGH: 4 })) {
    const scenario = fixture({ recycleEnabled: 1, recycle });
    scenario.frame();
    assert.equal(scenario.sends[0].vehicle.values.HUM_ENERGY_PTREGEN_LEVL, level);
    assert.equal(
        scenario.originalBundle.values.HUM_ENERGY_PTREGEN_LEVL,
        4,
        "never mutate OEM input"
    );
    const late = fixture({ acc: 2, enabled: 0, energyEnabled: 0, recycleEnabled: 1, recycle });
    late.bootstrap();
    assert.equal(late.sends[0].vehicle.values.HUM_ENERGY_PTREGEN_LEVL, level);
    late.bootstrap();
    assert.equal(late.sends.length, 1);
    const snow = fixture({ mode: "SNOW", recycleEnabled: 1, recycle });
    snow.frame();
    assert.equal(snow.sends[0].vehicle.values.HUM_ENERGY_PTREGEN_LEVL, undefined);
}
console.log("PASS: early ACC and late bootstrap recuperation, including Snow");

for (const platform of ["H97Y", "H97X"]) {
    const early = fixture({ platform, energyEnabled: 1, energy: "SREV" });
    early.frame();
    assert.equal(
        early.sends[0].vehicle.values.DRIVING_MODE_SET,
        3,
        platform + " guest reset restores Sport"
    );
    assert.equal(early.sends[0].vehicle.values.IVI_SOC_MODESET, 4);
    assert.deepEqual(early.accObservations, [2]);
    assert.equal(early.settingsPasses, 1);

    const late = fixture({ platform, acc: 2, energyEnabled: 1, energy: "SREV" });
    late.bootstrap();
    assert.equal(late.sends.length, 1, platform + " late attach submits startup modes");
    assert.equal(late.sends[0].vehicle.values.DRIVING_MODE_SET, 3);
    assert.equal(late.sends[0].vehicle.values.IVI_SOC_MODESET, 4);
    assert.equal(late.settingsPasses, 1);
    late.bootstrap();
    assert.equal(late.sends.length, 1);
    assert.equal(late.settingsPasses, 1);
}

for (const platform of ["H97Y", "H97X"]) {
    for (const rawOff of [0, 1]) {
        const cycle = fixture({ platform, energyEnabled: 1, energy: "SREV" });
        for (let expectedCycle = 1; expectedCycle <= 2; expectedCycle++) {
            cycle.frame([0x40 | rawOff]);
            assert.equal(cycle.acc, 0);
            cycle.frame([0x42]);
            assert.equal(cycle.cycle, expectedCycle, platform + " creates a fresh ACC cycle");
            assert.equal(cycle.providerAcc, 2);
            assert.equal(cycle.settingsPasses, expectedCycle);
            assert.equal(cycle.sends.at(-1).vehicle.values.DRIVING_MODE_SET, 3);
            assert.equal(cycle.sends.at(-1).vehicle.values.IVI_SOC_MODESET, 4);
            cycle.bootstrap();
            assert.equal(cycle.startup, "submitted");
            assert.equal(
                cycle.sends.length,
                expectedCycle * 2,
                "one OEM reset and one startup command per cycle"
            );
            cycle.frame([0x42]);
            cycle.bootstrap();
            assert.equal(
                cycle.sends.length,
                expectedCycle * 2,
                "repeated ON and timer do not resend"
            );
            assert.equal(
                cycle.settingsPasses,
                expectedCycle,
                "remaining settings run once per cycle"
            );
        }
        assert.ok(cycle.accObservations.includes(0), "OEM OFF is persisted before the next ON");
    }
}

{
    const unsupported = fixture({ platform: "H97C" });
    unsupported.frame();
    unsupported.bootstrap();
    assert.deepEqual(
        unsupported.accObservations,
        [],
        "unsupported platforms cannot create ACC cycles"
    );
    assert.equal(unsupported.sends.length, 1);
    assert.equal(unsupported.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
    assert.equal(unsupported.dispatches, 0);
}
console.log(
    "PASS: H97Y/H97X guest reset, late attach, repeated ACC cycles and unsupported platform isolation"
);
