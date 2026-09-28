"use strict";
// Execute the production agent against a synchronous OEM reset/Binder facade.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const source = fs.readFileSync(path.join(__dirname, "../inject/voyahtune_acc_restore.js"), "utf8");

function fixture(options = {}) {
    const f = Object.assign({mode: "SPORT", enabled: 1, energyEnabled: 0, energy: "EV",
        forcedEv: 0, maintenance: 0, debug: 0, platform: true, guest: 1, acc: 0,
        events: [], singles: [], calls: [],
        thread: 1, queries: 0, closes: 0, originals: 0, sends: [], logs: [], individual: {}}, options);
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
    const taskInit = method(function () {});
    const taskSend = method(function (values) { f.executed = values; return true; });
    f.tasks = [];
    const setter = method(function (air, vehicle) {
        f.sends.push({air, vehicle});
        if (f.sendError) throw new Error("Binder failure");
        const task = {};
        taskInit.invoke(task, receiver, receiver);
        f.tasks.push({task, values: new Map(Object.entries(vehicle.values))});
        return f.rejected ? -1 : 0;
    });
    const feedback = method(function (vehicle, value) { f.events.push([vehicle, value]); });
    const single = method(function (vehicle, value) { f.singles.push([vehicle, value]); });
    const receiver = {contentResolver: {value: {}}, id: 1,
        getAccStatus() { return f.acc; }, isH97X() { return f.platform; }};
    const parser = method(function (data, notify) {
        f.originals++;
        f.calls.push([Array.from(data), notify]);
        if (f.beforeSend) f.beforeSend();
        if ((data[0] & 7) === 2 && f.acc !== 2) {
            single.invoke(this, "IVI_PRIVACY_SETTING", 1);
            const b = new Bundle();
            b.values = {DRIVING_MODE_SET: f.guest === 1 ? 1 : 2,
                EPS_MODE_SET: 2, PROP_MODE_SET: f.guest === 1 ? 1 : 2,
                HUM_ENERGY_PTREGEN_LEVL: 4};
            f.originalBundle = b;
            setter.invoke(this, null, b);
            feedback.invoke(this, "DRIVING_MODE_SET", b.getInt("DRIVING_MODE_SET"));
            single.invoke(this, "VEHICLE_AMBIENT_LIGHT_COLOR", f.guest === 1 ? 19 : 5);
            f.acc = 2;
        }
        f.otherBcMWork = true;
        feedback.invoke(this, "CHILD_LOCK_CONTROL", 2);
        return 123;
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
        f.queries++;
        if (uri.includes("qinggan.settings")) {
            if (f.individualError) throw new Error("OEM provider failure");
            return cursor(f.individual[args[0]], true);
        }
        if (f.queryError) throw new Error("VoyahTune provider failure");
        return f.nullCursor ? null : cursor();
    });
    const timers = [];
    f.revision = 0; f.startup = "pending";
    const provider = method(function (uri, name, action, args) {
        if (f.queryError) throw new Error("provider unavailable");
        if (action === "user") {
            if (args.containsKey("mode")) f.mode = args.getString("mode");
            if (args.containsKey("energy")) f.energy = args.getString("energy");
            f.revision++;
        }
        const result = new Bundle();
        result.putInt("protocol", 2); result.putInt("acc", f.acc);
        result.putLong("revision", f.revision); result.putString("startup", f.startup);
        if (action === "claim") {
            const claimed = f.startup === "pending" && args.getLong("revision") === f.revision;
            result.putBoolean("claimed", claimed); if (claimed) f.startup = "claimed";
        }
        if (action === "complete") f.startup = args.getBoolean("accepted") ? "submitted" : "uncertain";
        return result;
    });
    const classes = {
        "android.os.SystemClock": {elapsedRealtime() { return 100000; }},
        "android.os.Process": {myPid() { return 1; }},
        "java.io.FileWriter": {$new() { return {write: {overload() { return {call(out, line) { f.health = line; }}; }}, close() {}}; }},
        "android.os.Binder": {getCallingUid() { return f.caller ?? -1; }, clearCallingIdentity() { return 0; }, restoreCallingIdentity() {}},
        "com.qinggan.canbus.service.CanBusService": {},
        "android.app.ActivityThread": {currentActivityThread() {
            let seen = false;
            return {mServices: {value: {values() { return {iterator() { return {
                hasNext() { return !seen; }, next() { seen = true; return {
                    getClass() { return {getName() { return "com.qinggan.canbus.service.CanBusService"; }}; },
                    mCanBusComponent: {value: receiver}}; }
            }; }}; }}}};
        }, currentApplication() { return {getContentResolver() { return {}; },
            getPackageManager() { return {getApplicationInfo(name) { return {uid: {value: name === "ru.big.town.anative" ? 10 : 20}}; }}; }}; }},
        "com.qinggan.canbus.VehicleState": {valueOf(s) { return s; }},
        "com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl$ModeSettingTask": {$init: taskInit, setVehicleMode: taskSend},
        "java.util.WeakHashMap": {$new() { const m = new Map(); return {put(k,v) { m.set(k,v); }, remove(k) { const v=m.get(k) ?? null; m.delete(k); return v; }}; }},
        "java.util.HashMap": {$new(v) { const m = new Map(v); m.remove = k => m.delete(k); return m; }},
        "java.util.concurrent.locks.ReentrantLock": {$new() { return {lock() {}, unlock() {}}; }},
        "android.util.Log": {i() {}},
        "java.lang.System": {getProperty(k, fallback) { return properties.get(k) ?? fallback; },
            setProperty(k, v) { properties.set(k, v); }, identityHashCode(o) { return o.id; }},
        "java.lang.String": {$new(v) { return v; }},
        "java.lang.ThreadLocal": {$new() {
            const values = new Map();
            return {get() { return values.get(f.thread) ?? null; },
                set(v) { values.set(f.thread, v); }, remove() { values.delete(f.thread); }};
        }},
        "com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl": {
            onBCM_PEPSChangeData: f.missingMethod ? undefined : parser,
            setVehicleAndAirConditionBundleState: setter, setVehicleState: single},
        "com.qinggan.canbus.service.BaseCanBusComponent": {onVehicleStateChanged: feedback},
        "com.qinggan.provider.QGSettings$System": {getInt: method(() => f.guest)},
        "com.qinggan.account.AccountUserManager": {getInstance() { return {
            AccountInfoBean() { return {isGuest: {value: true}, getName() { return "guest"; }}; }
        }; }},
        "android.net.Uri": {parse(s) { return s; }},
        "android.os.Bundle": {$new(b) { return new Bundle(b); }},
        "android.content.ContentResolver": {query, call: provider}
    };
    const context = vm.createContext({setInterval(fn) { return 1; }, clearInterval() {}, setTimeout(fn) { timers.push(fn); }, Java: {perform(fn) { fn(); }, use(name) {
        assert.ok(classes[name], name); return classes[name];
    }, array(type, values) { return values; }, cast(value) { return value; }, retain(v) { return v; }, choose(name, callbacks) { callbacks.onMatch(receiver); callbacks.onComplete(); }}, console: {log(s) { f.logs.push(s); }}});
    f.install = () => vm.runInContext(source, context);
    f.frame = (data = [2,0,0,0,0,0,0,0], notify = true) => parser.invoke(receiver, data, notify);
    f.plain = (owner = receiver) => { const b = new Bundle(); b.values = {DRIVING_MODE_SET: 1,
        EPS_MODE_SET: 2, PROP_MODE_SET: 1}; return setter.invoke(owner, null, b); };
    f.feedback = () => feedback.invoke(receiver, "DRIVING_MODE_SET", 1);
    receiver.setVehicleAndAirConditionBundleState = (a,b) => setter.invoke(receiver,a,b);
    f.bootstrap = () => timers.shift()();
    f.execute = index => taskSend.invoke(f.tasks[index].task, f.tasks[index].values);
    f.request = (values, caller = -1) => { f.caller = caller; const b = new Bundle(); b.values = values;
        try { return setter.invoke(receiver, null, b); } finally { f.caller = -1; }};
    f.install();
    if (!f.missingMethod) assert.equal(f.health, "1|100|v2\n");
    assert.equal(f.queries, 0, "Installation must not wait for a settings provider");
    if (!f.missingMethod) assert.ok(f.logs.includes("[acc-restore] hook ready v2"));
    f.methods = methods;
    return f;
}

for (const [mode, values] of Object.entries({ECO: [1,2,1], COMFORT: [2,2,2],
    SPORT: [3,3,3], OUTING: [4,2,3], INDIVIDUAL: [5,2,1], SNOW: [6,2,2]})) {
    const f = fixture({mode, energyEnabled: 1, energy: "REV", maintenance: 1});
    assert.equal(f.frame(), 123);
    assert.equal(f.originals, 1);
    assert.equal(f.sends.length, 1);
    const v = f.sends[0].vehicle.values;
    assert.deepEqual([v.DRIVING_MODE_SET, v.EPS_MODE_SET, v.PROP_MODE_SET], values);
    assert.equal(v.IVI_SOC_MODESET, 3);
    assert.equal(v.ASC_MAINTAIN_SWITCH, 2);
    assert.equal(v.HUM_ENERGY_PTREGEN_LEVL, mode === "SNOW" ? undefined : 4);
    assert.equal(f.originalBundle.values.DRIVING_MODE_SET, 1);
    assert.deepEqual(f.events, [["DRIVING_MODE_SET", values[0]], ["CHILD_LOCK_CONTROL", 2]]);
    assert.equal(f.singles[0][0], "IVI_PRIVACY_SETTING");
    assert.equal(f.singles[1][1], {1:19,2:5,3:64,4:54,5:10,6:9}[values[0]]);
    assert.equal(f.acc, 2);
    assert.equal(f.otherBcMWork, true);
    f.frame(); // Repeated ON frame is not another ACC edge.
    assert.equal(f.sends.length, 1);
    assert.equal(f.originals, 2);
}
for (const options of [{debug: 1}, {mode: "bad"}, {mode: "__proto__"},
    {queryError: true}, {nullCursor: true}, {empty: true}, {columns: 6},
    {platform: false}, {guest: 2}, {maintenance: null}, {maintenance: 8},
    {mode: "INDIVIDUAL", individualError: true},
    {mode: "INDIVIDUAL", individual: {drive_mode_runStateguest: "8"}},
    {mode: "INDIVIDUAL", individual: {drive_mode_steeringWheelAssistguest: "bad"}}]) {
    const f = fixture(options); f.frame();
    assert.equal(f.sends.length, 1);
    assert.deepEqual(f.sends[0].vehicle.values, f.originalBundle.values, JSON.stringify(options));
    assert.equal(f.events[0][1], options.guest === 2 ? 2 : 1);
}
{
    const f = fixture({sendError: true});
    assert.throws(f.frame, /Binder failure/);
    assert.equal(f.sends.length, 1);
    f.sendError = false; f.plain(); f.feedback();
    assert.equal(f.sends[1].vehicle.values.DRIVING_MODE_SET, 1); // Scope cleared after failure.
    assert.deepEqual(f.events, [["DRIVING_MODE_SET", 1]]);
}
{
    const f = fixture({rejected: true}); f.frame();
    assert.equal(f.sends.length, 1);
    assert.equal(f.events[0][1], 1); // Do not claim a target when queuing was rejected.
}
{
    const f = fixture();
    f.beforeSend = () => {
        f.thread = 2; f.plain(); f.thread = 1;
        f.plain({id: 99}); // An unrelated component on the same thread is also untouched.
    };
    f.frame();
    assert.deepEqual(f.sends.map(s => s.vehicle.values.DRIVING_MODE_SET), [1,1,3]);
    f.install(); f.beforeSend = null; f.acc = 0; f.frame();
    assert.equal(f.originals, 2);
    assert.ok(f.logs.includes("[acc-restore] hook ready v2 already_installed"));
}
{
    const f = fixture({missingMethod: true});
    assert.ok(f.methods.every(m => m.implementation === null));
    assert.ok(f.logs.some(l => l.startsWith("[acc-restore] hook failed stage=install")));
    f.frame(); assert.equal(f.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
}
for (const [energy, expected] of Object.entries({SMART: 1, EV: 2, REV: 3, SREV: 4})) {
    const f = fixture({energyEnabled: 1, energy, enabled: 0, maintenance: 0}); f.frame();
    assert.equal(f.sends[0].vehicle.values.IVI_SOC_MODESET, expected);
    assert.equal(f.sends[0].vehicle.values.ASC_MAINTAIN_SWITCH, 1);
    assert.equal(f.events[0][1], 1);
    assert.equal(f.singles[1][1], 19);
}
{
    const f = fixture({forcedEv: 1, energyEnabled: 1, energy: "SREV"}); f.frame();
    assert.equal(f.sends[0].vehicle.values.IVI_SOC_MODESET, 5);
}
{
    const f = fixture(); f.frame([0,0,0,0,0,0,0,0], false);
    assert.equal(f.sends.length, 0); assert.equal(f.originals, 1);
    assert.equal(f.calls[0][1], false); assert.equal(f.otherBcMWork, true);
    f.plain(); assert.equal(f.sends[0].vehicle.values.DRIVING_MODE_SET, 1);
}
{
    const f = fixture(); f.frame(); f.acc = 0;
    f.mode = "COMFORT"; f.energyEnabled = 1; f.energy = "SREV"; f.frame();
    assert.equal(f.sends[1].vehicle.values.DRIVING_MODE_SET, 2);
    assert.equal(f.sends[1].vehicle.values.IVI_SOC_MODESET, 4);
}
console.log("PASS: guest ACC edge, modes, feedback/color, stock parser, isolation, fallback and no replay");

{
    const f = fixture({acc: 2, energyEnabled: 1, energy: "SREV"}); f.bootstrap();
    assert.equal(f.sends.length, 1); assert.equal(f.startup, "submitted");
    assert.equal(f.sends[0].vehicle.values.DRIVING_MODE_SET, 3);
    assert.equal(f.sends[0].vehicle.values.IVI_SOC_MODESET, 4);
    f.bootstrap(); assert.equal(f.sends.length, 1, "readiness timer cannot resend a submitted startup");
}
{
    const f = fixture(); f.frame();
    f.request({DRIVING_MODE_SET: 1, __vt_user: true}, 20);
    f.execute(0);
    assert.equal(f.executed.get("DRIVING_MODE_SET"), 3, "already queued packets are not revalidated or rewritten");
    f.execute(1); assert.equal(f.executed.get("DRIVING_MODE_SET"), 1);
    assert.equal(f.mode, "ECO");
}
{
    const f = fixture({acc: 2, energyEnabled: 1});
    f.request({IVI_SOC_MODESET: 4}, 10);
    assert.equal(f.energy, "SREV");
    f.request({DRIVING_MODE_SET: 1, EPS_MODE_SET: 2, PROP_MODE_SET: 1, __vt_auto: true}, 20);
    assert.equal(f.sends[1].vehicle.values.DRIVING_MODE_SET, 3);
    assert.equal(f.sends[1].vehicle.values.IVI_SOC_MODESET, 4);
    assert.equal(f.sends[1].vehicle.values.__vt_auto, undefined);
}

assert.ok(!source.includes("Java.choose("), "Never enumerate the heap of the 32-bit OEM service");
