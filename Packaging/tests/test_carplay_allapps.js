#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../od/inject/launcherdock.js'), 'utf8');
const restore = 'ru.big.town.restoremode';
const carplay = restore + '/ru.big.town.restoremode.carplay.CarPlayLauncherActivity';
const prefix = '__voyahtune_allapps__:';
// Run the production All Apps functions against a PackageManager with two activities in one APK.
function production(name) {
    const match = new RegExp('^([ ]*)function ' + name + '\\(', 'm').exec(source);
    assert(match, name);
    const start = match.index;
    const closing = '\n' + match[1] + '}';
    const end = source.indexOf(closing, start) + closing.length;
    return source.slice(start, end);
}
function list(items = []) {
    return { items, size() { return items.length; }, get(i) { return items[i]; }, add(v) { items.push(v); } };
}
function bean(icon, name, pkg) {
    return { icon, name, pkg, subType: '', getPackageName() { return pkg; }, getType() { return 1; },
        getIcon() { return icon; }, getNameRes() { return name; }, getSubType() { return this.subType; },
        setSubType(v) { this.subType = v; } };
}
const launches = [];
let componentEnabled = true;
let componentInstalled = true;
const c = {
    RESTORE_PKG: restore, CARPLAY_ENTRY: carplay, SYNTHETIC_PREFIX: prefix, FLAG_SYSTEM: 1,
    installedSnapshot: null, resourceTemplate: null, iconCache: {}, getAll: null,
    TAG: 'test', Log: {i() {}, e(_, msg) { throw Error(msg); }},
    ApplicationInfo: {}, Java: {cast: value => value, retain: value => value},
    ComponentName: {$new: (pkg, activity) => ({pkg, activity})},
    AppBean: {$new: bean},
    Intent: {$new(action) { return {action, addCategory(v) { this.category = v; return this; },
        setComponent(v) { this.component = v; return this; }, addFlags(v) { this.flags = v; return this; }}; }},
    ctx: () => ({}), isUserFullscreen: () => false,
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
            if (isCarPlay && !componentInstalled) throw Error('Not installed');
            return {enabled: {value: componentEnabled}, exported: {value: true},
                loadLabel: () => isCarPlay ? 'VoyahTune CarPlay' : 'VoyahTune'};
        },
        getActivityIcon: component => component.activity.endsWith('CarPlayLauncherActivity') ? 'green' : 'grey',
        getApplicationIcon: () => 'package icon', getApplicationInfo: pkg => pkg,
        getApplicationLabel: () => 'DiPlay',
    },
};
vm.createContext(c);
vm.runInContext(['cleanJavaString', 'ourInsetActivity', 'entryPackage', 'entryComponent', 'launchAllApp', 'snapshotInstalled',
    'findAppTemplate', 'addMissingAppsImpl', 'syntheticPackage', 'loadIcon', 'loadLabel',
    'invalidateIconCache'].map(production).join('\n'), c);
const apps = list([bean(10, 20, 'oem.app')]);
c.addMissingAppsImpl(apps);
c.addMissingAppsImpl(apps);
assert.equal(apps.size(), 4); // OEM + two VoyahTune entries + ordinary music app.
assert.deepEqual(apps.items.slice(1).map(b => c.syntheticPackage(b)), [restore, carplay, 'app.music']);
assert.equal(apps.get(1).getPackageName(), restore);
assert.equal(apps.get(2).getPackageName(), restore);
assert.equal(c.loadLabel(restore), 'VoyahTune');
assert.equal(c.loadLabel(carplay), 'VoyahTune CarPlay');
assert.equal(c.loadIcon(restore), 'grey');
assert.equal(c.loadIcon(carplay), 'green');
assert.equal(c.ourInsetActivity('com.shilapi.xcertplay.DiPlayActivity'), true);
assert.equal(c.ourInsetActivity('com.shilapi.xcertplay.CarPlayHostActivity'), true);
assert.equal(c.ourInsetActivity('com.shilapi.xcertplay.OtherActivity'), false);
c.invalidateIconCache(restore);
assert.equal(Object.keys(c.iconCache).length, 0);
for (const screen of [0, 1]) {
    assert.equal(c.launchAllApp(restore, screen), true);
    assert.equal(launches.at(-1).intent.component.activity, restore + '.MainActivity');
    assert.equal(launches.at(-1).screen, screen);
    assert.equal(c.launchAllApp(carplay, screen), true);
    assert.equal(launches.at(-1).intent.component.activity, carplay.split('/')[1]);
    assert.equal(launches.at(-1).screen, screen);
}
const existing = list([bean(10, 20, restore)]);
c.addMissingAppsImpl(existing);
assert.equal(existing.size(), 3);
assert.equal(c.syntheticPackage(existing.get(0)), restore);
componentEnabled = false;
c.installedSnapshot = null;
assert.deepEqual(Array.from(c.snapshotInstalled()), [restore, 'app.music']);
componentInstalled = false;
c.installedSnapshot = null;
assert.deepEqual(Array.from(c.snapshotInstalled()), [restore, 'app.music']);
console.log('PASS: OD All Apps keeps Dashboard/CarPlay labels, icons, deduplication and both physical displays');
