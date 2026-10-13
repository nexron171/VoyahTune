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
vm.runInContext(['openVoyahTune', 'openVoyahTuneOnDisplay', 'launchFreeform'].map(production).join('\n'), c);
for (const displayId of [0, 1, 20]) {
    assert.equal(c.openVoyahTune({getDisplay: () => ({getDisplayId: () => displayId})}), true);
    const {intent, bundle} = launches.at(-1);
    assert.equal(intent.activity, 'ru.big.town.restoremode.MainActivity');
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
    assert.equal(intent.activity, 'ru.big.town.restoremode.MainActivity');
    assert.equal(intent.action, 'ru.big.town.restoremode.OPEN_DASHBOARD');
    assert.equal(intent.flags, 0x10000000 | 0x04000000 | 0x20000000);
    assert.equal(bundle.displayId, displayId);
}
console.log('PASS: OD dock gestures and app slots explicitly open Dashboard with CLEAR_TOP on the physical display');
