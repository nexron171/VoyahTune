'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../inject/voyahtune_drive_reset.js'), 'utf8');
function fixture(options = {}) {
    const f = {thread: 1, sends: [], saves: [], logs: [], ...options};
    const methods = [], properties = new Map(), scopes = new Map();
    function method(original) {
        const m = {implementation: null, overload() { return m; },
            call(receiver, ...args) { return original.apply(receiver, args); },
            invoke(...args) { return (m.implementation || original).apply({}, args); }};
        methods.push(m); return m;
    }
    class Bundle {
        constructor(mode) { this.values = mode === undefined ? {} : {DRIVING_MODE_SET: mode}; }
        getInt(key, fallback = 0) { return this.values[key] ?? fallback; }
        putString(key, value) { this.values[key] = value; }
    }
    const setter = method(function (air, vehicle) {
        f.sends.push(vehicle);
        if (f.sendError) throw Error('Binder failed');
        return f.result ?? 0;
    });
    // Model the two OEM TX77 packets; only the first carries the selected drive profile.
    f.sendMode = mode => {
        setter.invoke(null, new Bundle(mode));
        setter.invoke(null, new Bundle());
    };
    const handler = method(function (message) {
        if (f.otherThread) { f.thread = 2; f.sendMode(1); f.thread = 1; }
        f.sendMode(message.mode);
    });
    const confirm = method(function () { f.sendMode(1); });
    const provider = method(function (uri, protocol, action, args) {
        if (f.providerError) throw Error('Provider unavailable');
        assert.equal(protocol, 'driveHookV2'); assert.equal(action, 'user');
        f.saves.push(args.values.mode);
        return {getInt() { return 2; }};
    });
    const app = {getPackageName() { return 'com.qinggan.app.vehicle'; }, getContentResolver() { return {}; }};
    const classes = {
        'android.util.Log': {i(tag, text) { f.logs.push(text); }},
        'java.lang.System': {getProperty(k, d) { return properties.get(k) ?? d; }, setProperty(k,v) { properties.set(k,v); }},
        'android.os.SystemClock': {elapsedRealtime() { return 100000; }},
        'android.os.Process': {myPid() { return 55; }},
        'java.io.FileWriter': {$new(name) { f.healthPath = name; return {write: {overload() { return {call(out,line) { f.health = line; }}; }}, close() {}}; }},
        'android.app.ActivityThread': {currentApplication() { return app; }},
        'java.lang.ThreadLocal': {$new() { return {get() { return scopes.get(f.thread) ?? null; }, set(v) { scopes.set(f.thread,v); }, remove() { scopes.delete(f.thread); }}; }},
        'java.lang.String': {$new(s) { return s; }},
        'android.os.Bundle': {$new() { return new Bundle(); }},
        'android.net.Uri': {parse(s) { return s; }},
        'android.content.ContentResolver': {call: provider},
        'com.qinggan.canbus.CanBusManager': {setVehicleAndAirConditionBundleState: setter},
        'com.qinggan.app.vehiclebase.ui97.DriveModeViewManager$1': {handleMessage: handler},
        'com.qinggan.app.vehiclebase.ui97.DriveModeViewManager$2': {onClick: confirm}
    };
    const context = vm.createContext({Java: {perform(fn) { fn(); }, use(name) {
        if (f.missing && name.endsWith('$2')) throw Error('class not found');
        assert.ok(classes[name], name); return classes[name];
    }}, console: {log() {}}, setInterval(fn) { f.pulse = fn; return 1; }, clearInterval() {}});
    f.install = () => vm.runInContext(source, context);
    f.menu = mode => handler.invoke({what: {value: 3}, mode});
    f.otherMessage = mode => handler.invoke({what: {value: 1}, mode});
    f.confirm = () => confirm.invoke(null);
    f.methods = methods;
    f.install(); return f;
}
for (const [mode, name] of [[1,'ECO'],[2,'COMFORT'],[3,'SPORT'],[4,'OUTING'],[5,'INDIVIDUAL'],[6,'SNOW']]) {
    const f = fixture(); f.menu(mode);
    assert.deepEqual(f.saves, [name]); assert.equal(f.sends.length, 2);
    assert.equal(f.sends[0].getInt('DRIVING_MODE_SET'), mode);
    assert.equal(f.healthPath, '/data/local/open_voyah/drive_hooks/vehicle.health');
    assert.equal(f.health, '55|100|v2\n');
    f.install(); f.menu(mode); assert.deepEqual(f.saves, [name,name]);
}
{
    const f = fixture(); f.sendMode(2); // automatic Outing speed fallback: no user scope
    f.otherMessage(1); assert.deepEqual(f.saves, []);
    f.confirm(); assert.deepEqual(f.saves, ['ECO']);
}
{
    const f = fixture({otherThread: true}); f.menu(5);
    assert.deepEqual(f.saves, ['INDIVIDUAL']); assert.equal(f.sends.length, 4);
}
for (const options of [{result: -1}, {providerError: true}]) {
    const f = fixture(options); f.menu(2);
    assert.equal(f.sends.length, 2); assert.deepEqual(f.saves, []);
}
{
    const f = fixture({sendError: true}); assert.throws(() => f.menu(2), /Binder failed/);
    assert.equal(f.sends.length, 1); assert.deepEqual(f.saves, []);
    f.sendError = false; f.sendMode(1); assert.deepEqual(f.saves, []); // scope cleared
}
{
    const f = fixture({missing: true});
    assert.ok(f.methods.every(m => m.implementation === null));
    assert.ok(f.logs.some(s => s.includes('hook failed'))); assert.equal(f.health, undefined);
}
for (const value of [-1, 0, 7]) {
    const f = fixture(); f.menu(value); assert.deepEqual(f.saves, []);
}
console.log('PASS: VehicleAir user modes, automatic exclusion, acceptance, thread isolation, cleanup and no replay');
