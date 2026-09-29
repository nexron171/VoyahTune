"use strict";
// Execute the production agent against a synchronous OEM reset/Binder facade.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const source = fs.readFileSync(path.join(__dirname, "../inject/voyahtune_drive_reset.js"), "utf8");

function fixture(options = {}) {
    const f = Object.assign({mode: "SPORT", enabled: 1, energyEnabled: 0, energy: "EV",
        forcedEv: 0, maintenance: 0, debug: 0, platform: true,
        thread: 1, closes: 0, originals: 0, sends: [], logs: [], individual: {}}, options);
    const properties = new Map();
    const methods = [];
    function method(original) {
        const m = {implementation: null, overload() { return m; },
            call(receiver, ...args) { return original.apply(receiver, args); },
            invoke(receiver, ...args) { return (m.implementation || original).apply(receiver, args); }};
        methods.push(m);
        return m;
    }
    class Bundle {
        constructor(other) { this.values = {...(other ? other.values : {})}; }
        getInt(key, fallback = 0) { return this.values[key] ?? fallback; }
        containsKey(key) { return Object.hasOwn(this.values, key); }
        putInt(key, value) { this.values[key] = value; }
        getBoolean(key, fallback = false) { return this.values[key] ?? fallback; }
        putBoolean(key, v) { this.values[key] = v; }
        getLong(key, fallback = 0) { return this.values[key] ?? fallback; }
        putLong(key, v) { this.values[key] = v; }
        getString(key) { return this.values[key] ?? null; }
        putString(key, v) { this.values[key] = v; }
        remove(key) { delete this.values[key]; }
    }
    const setter = method(function (air, vehicle) {
        f.sends.push({air, vehicle});
        if (f.sendError) throw new Error("Binder failure");
        return 77;
    });
    function originalReset() {
        f.originals++;
        if (f.beforeSend) f.beforeSend();
        const b = new Bundle();
        b.values = {DRIVING_MODE_SET: 1, EPS_MODE_SET: 2, PROP_MODE_SET: 1,
            IVI_SOC_MODESET: 1, HUM_ENERGY_PTREGEN_LEVL: 4,
            VEHICLE_AMBIENT_LIGHT_COLOR: 19, DRIVING_MODE_RETAIN: 1};
        f.originalBundle = b;
        setter.invoke({}, f.air, b);
    }
    const reset = method(originalReset);
    const overseas = method(function () {
        f.originals++;
        const b = new Bundle();
        b.values = {DRIVING_MODE_SET: 1, IVI_SOC_MODESET: 1, HUM_ENERGY_PTREGEN_LEVL: 4};
        f.originalBundle = b;
        setter.invoke({}, null, b);
    });
    function cursor(value, individual) {
        return {moveToFirst() { return individual ? value !== undefined : !f.empty; },
            getColumnCount() { return f.columns ?? 33; },
            getInt(i) { return i === 6 ? f.enabled : i === 12 ? f.debug :
                i === 8 ? f.energyEnabled : i === 19 ? f.forcedEv : i === 32 ? f.maintenance : 0; },
            getString(i) { return individual ? value : i === 1 ? f.energy : f.mode; },
            isNull() { return individual ? value === null : f.maintenance === null; },
            close() { f.closes++; }};
    }
    const query = method(function (uri, projection, selection, args) {
        if (uri.includes("qinggan.settings")) {
            if (f.individualError) throw new Error("OEM provider failure");
            return cursor(f.individual[args[0]], true);
        }
        if (f.queryError) throw new Error("VoyahTune provider failure");
        return f.nullCursor ? null : cursor();
    });
    const timers = [];
    const classes = {
        "android.os.SystemClock": {elapsedRealtime() { return 100000; }},
        "android.os.Process": {myPid() { return 1; }},
        "java.io.FileWriter": {$new() { return {write: {overload() { return {call(out, line) { f.health = line; }}; }}, close() {}}; }},
        "android.util.Log": {i() {}},
        "java.lang.System": {getProperty(k, fallback) { return properties.get(k) ?? fallback; },
            setProperty(k, v) { properties.set(k, v); }},
        "java.lang.String": {$new(v) { return v; }},
        "java.lang.ThreadLocal": {$new() {
            const values = new Map();
            return {get() { return values.get(f.thread) ?? null; },
                set(v) { values.set(f.thread, v); }, remove() { values.delete(f.thread); }};
        }},
        "com.qinggan.app.vehiclesetting.accountdata.VehicleMemoryManager":
            {resetSettings: reset, resetOverseaDriveMode: f.missingMethod ? undefined : overseas},
        "com.qinggan.app.vehiclesetting.fragments.drivepreference.DrivePreferenceFragment": {
            onHintSwitchClick: method(function () {}),
            setDriveMode: method(function (v) { const b = new Bundle(); b.putInt("DRIVING_MODE_SET", v); setter.invoke({}, null, b); }),
            setPowerMode: method(function (v) { const b = new Bundle(); b.putInt("IVI_SOC_MODESET", v); setter.invoke({}, null, b); })},
        "com.qinggan.canbus.CanBusManager": {setVehicleAndAirConditionBundleState: setter, setVehicleState: method(function () {})},
        "com.qinggan.utils.AppCommonUtils": {is97X() { return f.platform; }},
        "com.qinggan.app.vehiclesetting.utils.Utils": {getAccountId() { return "guest"; }},
        "android.app.ActivityThread": {currentApplication() {
            return f.noApp ? null : {getPackageName() { return "com.qinggan.app.vehiclesetting"; }, getContentResolver() { return {}; }};
        }},
        "android.net.Uri": {parse(s) { return s; }},
        "android.os.Bundle": {$new(b) { return new Bundle(b); }},
        "android.content.ContentResolver": {query, call: method(function () { return new Bundle(); })}
    };
    const context = vm.createContext({setInterval(fn) { return 1; }, clearInterval() {}, setTimeout(fn) { timers.push(fn); }, Java: {perform(fn) { fn(); }, use(name) {
        assert.ok(classes[name], name); return classes[name];
    }, array(type, values) { return values; }}, console: {log(s) { f.logs.push(s); }}});
    f.install = () => vm.runInContext(source, context);
    f.reset = () => reset.invoke({});
    f.overseas = () => overseas.invoke({});
    f.plain = () => { const b = new Bundle(); b.values = {DRIVING_MODE_SET: 1};
        return setter.invoke({}, null, b); };
    f.screen = (value, energy = false) => classes["com.qinggan.app.vehiclesetting.fragments.drivepreference.DrivePreferenceFragment"]
        [energy ? "setPowerMode" : "setDriveMode"].invoke({}, value);
    f.install();
    if (!f.missingMethod) assert.equal(f.health, "1|100|v2\n");
    f.methods = methods;
    return f;
}

for (const [mode, values] of Object.entries({ECO: [1,2,1], COMFORT: [2,2,2],
    SPORT: [3,3,3], OUTING: [4,2,3], INDIVIDUAL: [5,2,1], SNOW: [6,2,2]})) {
    const f = fixture({mode, air: {untouched: true}});
    f.reset();
    assert.equal(f.originals, 1);
    assert.equal(f.sends.length, 1);
    const v = f.sends[0].vehicle.values;
    assert.deepEqual([v.DRIVING_MODE_SET, v.EPS_MODE_SET, v.PROP_MODE_SET], values);
    assert.equal(v.IVI_SOC_MODESET, 1);
    assert.equal(v.VEHICLE_AMBIENT_LIGHT_COLOR, 19);
    assert.equal(v.DRIVING_MODE_RETAIN, 1);
    assert.equal(v.HUM_ENERGY_PTREGEN_LEVL, mode === "SNOW" ? undefined : 4);
    assert.equal(f.sends[0].air, f.air);
    assert.equal(f.originalBundle.values.DRIVING_MODE_SET, 1); // Copy, not mutation.
    assert.equal(f.closes, mode === "INDIVIDUAL" ? 3 : 1);
}
for (const options of [{debug: 1}, {mode: "bad"}, {mode: "__proto__"},
    {queryError: true}, {nullCursor: true}, {empty: true}, {columns: 6}, {noApp: true},
    {platform: false}, {maintenance: null}, {maintenance: 8},
    {mode: "INDIVIDUAL", individualError: true},
    {mode: "INDIVIDUAL", individual: {drive_mode_runStateguest: "8"}},
    {mode: "INDIVIDUAL", individual: {drive_mode_steeringWheelAssistguest: "bad"}}]) {
    const f = fixture(options); f.reset();
    assert.equal(f.sends.length, 1);
    const sent = {...f.sends[0].vehicle.values}; delete sent.__vt_auto;
    assert.deepEqual(sent, f.originalBundle.values, JSON.stringify(options));
}
{
    const f = fixture({mode: "INDIVIDUAL", individual: {
        drive_mode_runStateguest: "3", drive_mode_steeringWheelAssistguest: "3"}});
    f.overseas();
    assert.deepEqual(f.sends[0].vehicle.values, {DRIVING_MODE_SET: 5,
        IVI_SOC_MODESET: 1, HUM_ENERGY_PTREGEN_LEVL: 4, EPS_MODE_SET: 3, PROP_MODE_SET: 3,
        ASC_MAINTAIN_SWITCH: 1});
    f.mode = "COMFORT"; f.overseas();
    assert.equal(f.sends[1].vehicle.values.DRIVING_MODE_SET, 2); // Fresh saved target every call.
    f.enabled = 0; f.overseas();
    assert.equal(f.sends[2].vehicle.values.DRIVING_MODE_SET, 1);
}
{
    const f = fixture({sendError: true});
    assert.throws(f.reset, /Binder failure/);
    assert.equal(f.sends.length, 1); // Never retry an uncertain Binder transaction.
    f.sendError = false; assert.equal(f.plain(), 77);
    assert.equal(f.sends[1].vehicle.values.DRIVING_MODE_SET, 1); // Scope cleared on exception.
}
{
    const f = fixture();
    f.beforeSend = () => { f.thread = 2; f.plain(); f.thread = 1; };
    f.reset();
    assert.equal(f.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
    assert.equal(f.sends[1].vehicle.values.DRIVING_MODE_SET, 3);
    f.install(); f.beforeSend = null; f.reset();
    assert.equal(f.originals, 2); // Re-injection does not wrap the wrapper.
    assert.ok(f.logs.includes("[drive-reset] hook ready v2 already_installed"));
}
{
    const f = fixture({missingMethod: true});
    assert.ok(f.methods.every(m => m.implementation === null));
    assert.ok(f.logs.some(l => l.startsWith("[drive-reset] hook failed stage=install")));
    f.reset(); assert.equal(f.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
}
for (const [energy, expected] of Object.entries({SMART: 1, EV: 2, REV: 3, SREV: 4})) {
    for (const enabled of [0, 1]) {
        const f = fixture({energyEnabled: 1, energy, enabled});
        f.reset(); f.overseas();
        for (const send of f.sends) {
            assert.equal(send.vehicle.values.IVI_SOC_MODESET, expected);
            assert.equal(send.vehicle.values.DRIVING_MODE_SET, enabled ? 3 : 1);
        }
    }
}
for (const energyEnabled of [0, 1]) {
    const f = fixture({energyEnabled, energy: "SREV", forcedEv: 1});
    f.overseas(); assert.equal(f.sends[0].vehicle.values.IVI_SOC_MODESET, 5);
}
{
    const f = fixture({energyEnabled: 1, energy: "bad"});
    f.reset(); assert.equal(f.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
    assert.equal(f.sends[0].vehicle.values.__vt_auto, true);
}
for (const maintenance of [0, 1]) {
    const f = fixture({enabled: 0, energyEnabled: 0, maintenance});
    f.reset(); f.overseas();
    for (const send of f.sends) {
        assert.equal(send.vehicle.values.ASC_MAINTAIN_SWITCH, maintenance ? 2 : 1);
        assert.equal(send.vehicle.values.DRIVING_MODE_SET, 1);
        assert.equal(send.vehicle.values.IVI_SOC_MODESET, 1);
    }
}
console.log("PASS: drive/energy/suspension targets, stock fields, fallback, thread scope, rollback and no replay");

{
    const f = fixture(); f.screen(1); f.screen(4, true);
    assert.equal(f.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
    assert.equal(f.sends[0].vehicle.values.__vt_user, true);
    assert.equal(f.sends[1].vehicle.values.IVI_SOC_MODESET, 4);
    assert.equal(f.sends[1].vehicle.values.__vt_user, true);
    f.plain(); assert.equal(f.sends[2].vehicle.values.__vt_user, undefined);
}
