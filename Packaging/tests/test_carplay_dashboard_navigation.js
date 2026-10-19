#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../od/inject/launcherdock.js'), 'utf8');
function production(name) {
    const match = new RegExp('^([ ]*)function ' + name + '\\(', 'm').exec(source);
    assert(match, name);
    const closing = '\n' + match[1] + '}';
    return source.slice(match.index, source.indexOf(closing, match.index) + closing.length);
}
const launches = [];
const c = {
    RESTORE_PKG: 'ru.big.town.restoremode', TAG: 'test',
    Log: {i() {}, e(_, message) { throw Error(message); }},
    Intent: {ACTION_MAIN: {value: 'MAIN'}, CATEGORY_LAUNCHER: {value: 'LAUNCHER'},
        $new(action) { return {action, addCategory(value) { this.category = value; },
            setClassName(pkg, activity) { this.pkg = pkg; this.activity = activity; },
            addFlags(flags) { this.flags = flags; }}; }},
    Java: {
        use(name) {
            if (name === 'android.app.ActivityOptions') return {makeBasic() { return {
                displayId: -1, setLaunchDisplayId(value) { this.displayId = value; },
                toBundle() { return {displayId: this.displayId, putInt(key, value) { this[key] = value; }}; }
            }; }};
            if (name === 'android.util.Log') return c.Log;
            return {};
        },
        registerClass(spec) { return {$new: () => ({onLongClick: spec.methods.onLongClick.implementation})}; }
    },
    ctx: () => ({startActivity(intent, bundle) { launches.push({intent, bundle}); }})
};
vm.createContext(c);
vm.runInContext(['cleanJavaString', 'dashboardActivityForDisplay', 'isDashboardActivity',
    'openVoyahTune', 'openVoyahTuneOnDisplay', 'launchFreeform'].map(production).join('\n'), c);
for (const displayId of [0, 1, 20]) {
    assert.equal(c.openVoyahTune({getDisplay: () => ({getDisplayId: () => displayId})}), true);
    const {intent, bundle} = launches.at(-1);
    assert.equal(intent.activity, displayId === 1
        ? 'ru.big.town.restoremode.passenger.PassengerDashboardActivity'
        : 'ru.big.town.restoremode.MainActivity');
    assert.equal(intent.action, 'ru.big.town.restoremode.OPEN_DASHBOARD');
    assert.equal(intent.category, undefined);
    assert.equal(intent.flags, 0x10000000 | 0x04000000 | 0x20000000);
    assert.equal(bundle.displayId, displayId === 1 ? 1 : 0);
    assert.equal(bundle['android.activity.windowingMode'], 1);
}
assert.equal(c.openVoyahTune({getDisplay: () => null}), true);
assert.equal(launches.at(-1).bundle.displayId, 0);

for (const displayId of [0, 1]) {
    assert.equal(c.launchFreeform(c.RESTORE_PKG, displayId), true);
    const {intent, bundle} = launches.at(-1);
    assert.equal(intent.activity, displayId === 1
        ? 'ru.big.town.restoremode.passenger.PassengerDashboardActivity'
        : 'ru.big.town.restoremode.MainActivity');
    assert.equal(intent.action, 'ru.big.town.restoremode.OPEN_DASHBOARD');
    assert.equal(intent.flags, 0x10000000 | 0x04000000 | 0x20000000);
    assert.equal(bundle.displayId, displayId);
}
const originalLaunches = [];
const intentMethod = {call(_, context, intent, displayId) { originalLaunches.push({intent, displayId}); }};
const componentMethod = {call(_, context, pkg, activity, displayId) { originalLaunches.push({pkg, activity, displayId}); }};
c.AppLauncher = {startApp: {overload(...types) { return types.length === 3 ? intentMethod : componentMethod; }}};
c.packageFromIntent = intent => intent.getComponent().getPackageName();
c.returnDockAppToScreen = () => false;
c.isUserFullscreen = () => false;
const hooksStart = source.indexOf('var startAppIntent = AppLauncher.startApp.overload(');
const hooksEnd = source.indexOf('Log.i(TAG, "[allapps] fullscreen ActivityOptions routing installed");', hooksStart);
assert(hooksStart >= 0 && hooksEnd > hooksStart);
vm.runInContext(source.slice(hooksStart, hooksEnd), c);
for (const displayId of [0, 1]) {
    for (const activity of [c.dashboardActivityForDisplay(0), c.dashboardActivityForDisplay(1), '.MainActivity']) {
        const intent = {getComponent: () => ({getPackageName: () => c.RESTORE_PKG, getClassName: () => activity})};
        intentMethod.implementation({}, intent, displayId);
        assert.equal(launches.at(-1).intent.activity, c.dashboardActivityForDisplay(displayId));
        componentMethod.implementation({}, c.RESTORE_PKG, activity, displayId);
        assert.equal(launches.at(-1).intent.activity, c.dashboardActivityForDisplay(displayId));
    }
    const count = launches.length;
    for (const activity of ['com.shilapi.xcertplay.DiPlayActivity', 'ru.big.town.restoremode.carplay.CarPlayLauncherActivity']) {
        const intent = {getComponent: () => ({getPackageName: () => c.RESTORE_PKG, getClassName: () => activity})};
        intentMethod.implementation({}, intent, displayId);
        assert.equal(originalLaunches.at(-1).intent, intent);
        componentMethod.implementation({}, c.RESTORE_PKG, activity, displayId);
        assert.equal(originalLaunches.at(-1).activity, activity);
    }
    assert.equal(launches.length, count);
}
console.log('PASS: OD dock and OEM icon launches reuse independent dashboard tasks; CarPlay entries are preserved');
