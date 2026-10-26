'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../agents/windows.js'), 'utf8');
const block = source.split('// BEGIN_CARPLAY_WIFI_SCAN_PAUSE')[1]
    .split('// END_CARPLAY_WIFI_SCAN_PAUSE')[0];
const settingName = 'voyahtune_carplay_wifi_scan_lease';

function fixture(options = {}) {
    let now = 1000;
    let inWifiThread = false;
    let lease = options.lease ?? '7:1000';
    let readFails = false;
    let failMutation = false;
    const pending = [];
    const writes = [];
    const logs = [];
    const handler = {
        post(task) { pending.push({ at: now, task }); return true; },
        postDelayed(task, delay) { pending.push({ at: now + delay, task }); return true; },
    };
    const manager = {
        mAutoJoinEnabledExternal: { value: options.enabled ?? true },
        mEventHandler: { value: handler },
        equals(other) { return other === this; },
    };
    const setter = {
        implementation: null,
        call(target, enabled) {
            assert.equal(inWifiThread, true, 'autojoin must run on the Wi-Fi thread');
            if (enabled || !options.ignorePause) target.mAutoJoinEnabledExternal.value = enabled;
            writes.push(enabled);
            if (failMutation) {
                failMutation = false;
                throw new Error('vendor failure after mutation');
            }
        },
        overload() { return this; },
    };
    const managerClass = {
        class: { getDeclaredField() {
            if (options.unsupported) throw new Error('OEM field missing');
        } },
        setAutoJoinEnabledExternal: setter,
    };
    const settings = {
        getInt: { overload: () => ({ call: () => 7 }) },
        getString(_, key) {
            assert.equal(key, settingName);
            if (readFails) throw new Error('settings temporarily unavailable');
            return lease;
        },
    };
    const service = {
        getClass: () => ({ getClassLoader: () => ({}) }),
        mClientModeImpl: { value: { mWifiConnectivityManager: { value: manager } } },
    };
    const sandbox = {
        TAG: 'test', agentFailed: false, installed: [], trackReplacement() {},
        Log: { i: (_, line) => logs.push(line), w: (_, line) => logs.push(line) },
        ATh: { currentActivityThread: () => ({ getSystemContext: () => ({ getContentResolver: () => ({}) }) }) },
        SystemClock: { elapsedRealtime: () => now },
        setTimeout: (callback, delay) => pending.push({ at: now + delay, task: { run: callback } }),
        Java: {
            ClassFactory: { get: () => ({ use: name => name.endsWith('WifiConnectivityManager') ? managerClass : {} }) },
            use(name) {
                if (name === 'android.provider.Settings$Global') return settings;
                if (name === 'android.os.ServiceManager') {
                    return { getService: () => now < (options.readyAt || 0) ? null : service };
                }
                return {};
            },
            cast: value => value,
            retain: value => value,
            perform: callback => callback(),
            registerClass: definition => ({ $new: () => ({ run: definition.methods.run }) }),
        },
    };
    vm.runInNewContext(block + '\ninstallCarPlayWifiScanPause();', sandbox);

    function advance(millis = 0) {
        const until = now + millis;
        for (let count = 0; count < 100; count++) {
            pending.sort((left, right) => left.at - right.at);
            if (!pending.length || pending[0].at > until) { now = until; return; }
            const next = pending.shift();
            now = next.at;
            inWifiThread = true;
            try { next.task.run(); } finally { inWifiThread = false; }
        }
        throw new Error('unbounded polling');
    }

    return {
        writes, logs, advance, pending,
        enabled: () => manager.mAutoJoinEnabledExternal.value,
        renew: () => { lease = '7:' + now; },
        release: () => { lease = ''; },
        failAgent: () => { sandbox.agentFailed = true; },
        failSettings: () => { readFails = true; },
        failNextMutation: () => { failMutation = true; },
        external(enabled) {
            inWifiThread = true;
            try { setter.implementation.call(manager, enabled); }
            finally { inWifiThread = false; }
        },
    };
}

const normal = fixture();
normal.advance();
assert.equal(normal.enabled(), false);
normal.advance(4000);
normal.renew();
normal.advance(7000);
assert.deepEqual(normal.writes, [false]);
normal.release();
normal.advance(1000);
assert.deepEqual(normal.writes, [false, true]);
normal.advance(3000);
assert.deepEqual(normal.writes, [false, true]);

const crashed = fixture();
crashed.advance(9999);
assert.equal(crashed.enabled(), false);
crashed.advance(1);
assert.equal(crashed.enabled(), true, 'a dead Native/RestoreMode must not keep scans disabled');

const alreadyDisabled = fixture({ enabled: false });
alreadyDisabled.advance(10000);
assert.equal(alreadyDisabled.enabled(), false, 'preserve a pre-existing autojoin policy');

for (const lease of ['', null, '6:1000', '7:1001', '7:0', 'invalid', '7:Infinity']) {
    const invalid = fixture({ lease: lease === null ? '' : lease });
    invalid.advance();
    assert.deepEqual(invalid.writes, [], String(lease));
}

for (const requested of [false, true]) {
    const changed = fixture();
    changed.advance();
    changed.external(requested);
    assert.equal(changed.enabled(), false);
    changed.release();
    changed.advance(1000);
    assert.equal(changed.enabled(), requested, 'restore the latest external policy');
}

const failed = fixture();
failed.failNextMutation();
failed.advance();
assert.deepEqual(failed.writes, [false, true], 'restore after an uncertain partial mutation');

const ignored = fixture({ ignorePause: true });
ignored.advance();
assert.equal(ignored.enabled(), true);
assert.deepEqual(ignored.writes, [false, true]);
assert.match(ignored.logs.join('\n'), /autojoin pause not confirmed/);
assert.doesNotMatch(ignored.logs.join('\n'), /scans paused;/);

const unavailableSettings = fixture();
unavailableSettings.advance();
unavailableSettings.failSettings();
unavailableSettings.advance(1000);
assert.equal(unavailableSettings.enabled(), true);

const rolledBack = fixture();
rolledBack.advance();
rolledBack.failAgent();
rolledBack.advance(1000);
assert.equal(rolledBack.enabled(), true);
assert.equal(rolledBack.pending.length, 0);

const unsupported = fixture({ unsupported: true });
unsupported.advance();
assert.deepEqual(unsupported.writes, []);
assert.match(unsupported.logs.join('\n'), /unavailable/);

const booting = fixture({ readyAt: 3000 });
booting.advance();
assert.deepEqual(booting.writes, []);
booting.advance(2000);
assert.equal(booting.enabled(), false, 'initialize after the Wi-Fi service starts');

const cancelledStartup = fixture({ readyAt: 3000 });
cancelledStartup.failAgent();
cancelledStartup.advance(5000);
assert.deepEqual(cancelledStartup.writes, []);

console.log('PASS: OD Wi-Fi scan pause, heartbeat expiry, boot isolation, prior/external policy and failure recovery');
