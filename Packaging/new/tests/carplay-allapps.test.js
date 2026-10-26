#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../adapters/launcher/split-navigation.js'), 'utf8');
const restore = 'ru.big.town.restoremode';
const carplay = restore + '/ru.big.town.restoremode.carplay.CarPlayLauncherActivity';
const passenger = restore + '/ru.big.town.restoremode.passenger.PassengerDashboardActivity';
const prefix = '__voyahtune_allapps__:';
// Run production All Apps routing for both explicit RestoreMode activities.
function production(name) {
    const match = new RegExp('^([ ]*)function ' + name + '\\(', 'm').exec(source);
    assert(match, name);
    const start = match.index;
    const closing = '\n' + match[1] + '}';
    const end = source.indexOf(closing, start) + closing.length;
    return source.slice(start, end);
}
function list(items = []) {
    return { items, size() { return items.length; }, get(i) { return items[i]; },
        add(v) { items.push(v); }, remove(i) { return items.splice(i, 1)[0]; } };
}
function bean(icon, name, pkg) {
    return { icon, name, pkg, subType: '', getPackageName() { return pkg; }, getType() { return 1; },
        getIcon() { return icon; }, getNameRes() { return name; }, getSubType() { return this.subType; },
        setSubType(v) { this.subType = v; } };
}
const launches = [];
let carPlayEnabled = true;
const c = {
    RESTORE_PKG: restore, CARPLAY_ENTRY: carplay, PASSENGER_ENTRY: passenger,
    SYNTHETIC_PREFIX: prefix, FLAG_SYSTEM: 1,
    installedSnapshot: null, resourceTemplate: null, iconCache: {}, getAll: null,
    TAG: 'test', Log: {i() {}, e(_, msg) { throw Error(msg); }},
    ApplicationInfo: {}, Java: {cast: value => value, retain: value => value},
    ComponentName: {$new: (pkg, activity) => ({pkg, activity})},
    AppBean: {$new: bean},
    Intent: {$new(action) { return {action, addCategory(v) { this.category = v; return this; },
        setComponent(v) { this.component = v; return this; }, addFlags(v) { this.flags = v; return this; }}; }},
    ctx: () => ({}), isUserFullscreen: () => false,
    openVoyahTuneOnDisplay(screen) { launches.push({dashboard: true, screen}); return true; },
    AppLauncher: {startApp(_, intent, screen) { launches.push({intent, screen}); }},
    pm: {
        getInstalledApplications: () => list([
            {packageName: {value: restore}, flags: {value: 1}, enabled: {value: true}},
            {packageName: {value: 'other.system'}, flags: {value: 1}, enabled: {value: true}},
            {packageName: {value: 'app.music'}, flags: {value: 0}, enabled: {value: true}},
        ]),
        getLaunchIntentForPackage: pkg => ({package: pkg, addFlags() {}}),
        getActivityInfo(component) {
            const isCarPlay = component.activity.endsWith('CarPlayLauncherActivity');
            const isPassenger = component.activity.endsWith('PassengerDashboardActivity');
            return {enabled: {value: !isCarPlay || carPlayEnabled}, exported: {value: true},
                loadLabel: () => isCarPlay ? 'VoyahTune CarPlay'
                        : isPassenger ? 'VoyahTune Пассажир' : 'VoyahTune'};
        },
        getActivityIcon: component => component.activity.endsWith('CarPlayLauncherActivity') ? 'dark-carplay'
                : component.activity.endsWith('PassengerDashboardActivity') ? 'passenger-icon' : 'grey',
        getApplicationIcon: () => 'package icon', getApplicationInfo: pkg => pkg,
        getApplicationLabel: () => 'DiPlay',
    },
};
vm.createContext(c);
vm.runInContext(['cleanJavaString', 'ourInsetActivity', 'entryPackage', 'entryComponent', 'carPlayEntryEnabled', 'launchAllApp', 'snapshotInstalled',
    'findAppTemplate', 'addMissingAppsImpl', 'syntheticPackage', 'loadIcon', 'loadLabel',
    'invalidateIconCache'].map(production).join('\n'), c);
const apps = list([bean(10, 20, 'oem.app')]);
c.addMissingAppsImpl(apps);
c.addMissingAppsImpl(apps);
assert.equal(apps.size(), 4); // OEM + VoyahTune + CarPlay + music.
assert.deepEqual(apps.items.slice(1).map(b => c.syntheticPackage(b)), [restore, carplay, 'app.music']);
assert.equal(apps.get(1).getPackageName(), restore);
assert.equal(c.loadLabel(restore), 'VoyahTune');
assert.equal(c.loadIcon(restore), 'grey');
assert.equal(c.ourInsetActivity('com.shilapi.xcertplay.DiPlayActivity'), true);
assert.equal(c.ourInsetActivity('com.shilapi.xcertplay.CarPlayHostActivity'), true);
assert.equal(c.ourInsetActivity('com.shilapi.xcertplay.OtherActivity'), false);
assert.equal(c.ourInsetActivity('ru.big.town.restoremode.passenger.PassengerDashboardActivity'), true);
c.invalidateIconCache(restore);
assert.equal(Object.keys(c.iconCache).length, 0);
for (const screen of [0, 1]) {
    assert.equal(c.launchAllApp(restore, screen), true);
    assert.equal(launches.at(-1).dashboard, true);
    assert.equal(launches.at(-1).screen, screen);
}
const existing = list([bean(10, 20, restore)]);
c.addMissingAppsImpl(existing);
assert.equal(existing.size(), 3);
assert.equal(c.syntheticPackage(existing.get(0)), restore);
const legacy = [bean(10, 20, restore), bean(10, 20, restore)];
for (const entry of legacy) entry.setSubType(prefix + carplay);
const cached = list([bean(10, 20, 'oem.app'), ...legacy, bean(10, 20, restore)]);
c.addMissingAppsImpl(cached);
c.addMissingAppsImpl(cached);
assert.deepEqual(cached.items.map(b => c.syntheticPackage(b) || b.getPackageName()),
    ['oem.app', carplay, restore, 'app.music']);
assert.equal(c.loadIcon(carplay), 'dark-carplay');
for (const screen of [0, 1]) {
    assert.equal(c.launchAllApp(carplay, screen), true);
    assert.equal(launches.at(-1).intent.component.activity, restore + '.carplay.CarPlayLauncherActivity');
    assert.equal(launches.at(-1).screen, screen);
}
const passengerList = list([bean(10, 20, 'oem.app'), bean(10, 20, restore)]);
c.addMissingAppsImpl(passengerList, 1);
c.addMissingAppsImpl(passengerList, 1);
assert.deepEqual(passengerList.items.map(b => c.syntheticPackage(b) || b.getPackageName()),
    ['oem.app', carplay, 'app.music', passenger]);
assert.equal(c.loadLabel(passenger), 'VoyahTune Пассажир');
assert.equal(c.loadIcon(passenger), 'passenger-icon');
assert.equal(c.launchAllApp(passenger, 1), true);
assert.equal(launches.at(-1).intent.component.activity,
    'ru.big.town.restoremode.passenger.PassengerDashboardActivity');
assert.equal(launches.at(-1).screen, 1);
carPlayEnabled = false;
c.addMissingAppsImpl(cached);
assert.deepEqual(cached.items.map(b => c.syntheticPackage(b) || b.getPackageName()),
    ['oem.app', restore, 'app.music']);
c.addMissingAppsImpl(passengerList, 1);
assert.deepEqual(passengerList.items.map(b => c.syntheticPackage(b) || b.getPackageName()),
    ['oem.app', 'app.music', passenger]);
const count = launches.length;
assert.equal(c.launchAllApp(carplay, 0), false);
assert.equal(launches.length, count);
assert.equal(c.launchAllApp(restore, 1), true);
assert.equal(launches.at(-1).dashboard, true);
carPlayEnabled = true;
c.installedSnapshot = null;
c.addMissingAppsImpl(cached);
c.addMissingAppsImpl(cached);
assert.equal(cached.items.filter(b => c.syntheticPackage(b) === carplay).length, 1);
console.log('PASS: OD All Apps gates CarPlay, separates passenger dashboard and preserves independent launch routing');
