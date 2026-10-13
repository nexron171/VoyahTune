'use strict';
const assert = require('node:assert/strict');
const {fixture} = require('./vd_fixture');
const restorePackage = 'ru.big.town.restoremode';
const sessionService = 'com.shilapi.xcertplay.DiPlaySessionService';
const projectionActivity = 'com.shilapi.xcertplay.CarPlayHostActivity';
const adjustmentMethod = 'com.android.server.am.OomAdjuster.applyOomAdjLocked';
const destroyMethod = 'com.android.server.wm.ActivityRecord.isDestroyable';
const component = (className, packageName = restorePackage) => ({
    getPackageName: () => packageName,
    getClassName: () => className,
});
const service = (className = sessionService, foreground = true) => ({
    name: {value: component(className)},
    isForeground: {value: foreground},
});
function processModel(services, processName = restorePackage) {
    const windowProcess = {};
    return {
        processName: {value: processName}, curAdj: {value: 200}, services,
        numberOfRunningServices() { return this.services.length; },
        getRunningServiceAt(index) { return this.services[index]; },
        getWindowProcessController: () => windowProcess,
    };
}
const agent = fixture();
const adjust = process => agent.methods.get(adjustmentMethod).implementation.call({}, process, true, 1, 1);
const activity = (process, className = projectionActivity) => ({
    mActivityComponent: {value: component(className)},
    finishing: {value: false}, app: {value: process.getWindowProcessController()},
});
const destroyable = view => agent.methods.get(destroyMethod).implementation.call(view);
const active = processModel([service()]);
assert.equal(adjust(active), true);
assert.equal(active.curAdj.value, 0);
assert.equal(destroyable(activity(active)), false);
assert.equal(destroyable(activity(active, 'com.shilapi.xcertplay.DiPlayActivity')), true);
assert.equal(destroyable(activity(active, 'ru.big.town.restoremode.MainActivity')), true);
const finishing = activity(active);
finishing.finishing.value = true;
assert.equal(destroyable(finishing), true);

// Ending the foreground session releases both protections on the next stock adjustment.
active.services[0].isForeground.value = false;
active.curAdj.value = 200;
adjust(active);
assert.equal(active.curAdj.value, 200);
assert.equal(destroyable(activity(active)), true);
active.services = [];
active.curAdj.value = 900;
adjust(active);
assert.equal(active.curAdj.value, 900);

for (const process of [
    processModel([service('OtherService')]),
    processModel([service(sessionService, false)]),
    processModel([service()], 'other.package'),
    processModel([service()], restorePackage + ':ota'),
]) {
    adjust(process);
    assert.equal(process.curAdj.value, 200);
    assert.equal(destroyable(activity(process)), true);
}
const replacement = processModel([service()]);
replacement.curAdj.value = -100;
adjust(replacement);
assert.equal(replacement.curAdj.value, -100);
assert.equal(destroyable(activity(replacement)), false);
assert.equal(destroyable(activity(active)), true);

for (const missingMethod of [adjustmentMethod, destroyMethod]) {
    const unsupported = fixture({missingMethod});
    unsupported.advance(1500);
    assert.match(unsupported.status(), /\|active\|/);
    assert.equal(unsupported.methods.get(adjustmentMethod)?.implementation ?? null, null);
    assert.equal(unsupported.methods.get(destroyMethod)?.implementation ?? null, null);
}
const rollbackFailure = fixture({failInstall: destroyMethod, failRollback: adjustmentMethod});
assert.match(rollbackFailure.status(), /\|failed\|partial\./);

const legacy = fixture({missingMethod: 'com.android.server.am.ProcessRecord.numberOfRunningServices'});
const legacyProcess = processModel([]);
legacyProcess.services = {size: () => 1, valueAt: () => service()};
legacy.methods.get(adjustmentMethod).implementation.call({}, legacyProcess, true, 1, 1);
assert.equal(legacyProcess.curAdj.value, 0);
assert.equal(legacy.methods.get(destroyMethod).implementation.call(activity(legacyProcess)), false);

replacement.numberOfRunningServices = () => { throw new Error('OEM record unavailable'); };
adjust(replacement);
assert.equal(destroyable(activity(replacement)), true);
replacement.curAdj.value = 200;
adjust(replacement);
assert.equal(replacement.curAdj.value, 200);

console.log('PASS: session-scoped OOM priority, projection retention, release, process isolation and optional ABI fallback');
