#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
const source = fs.readFileSync(path.join(root, 'Packaging/inject/vd_bypass.js'), 'utf8');
const block = source.split('// BEGIN_NATIVE_TASK_REMOVAL')[1].split('// END_NATIVE_TASK_REMOVAL')[0];

function install(ourUid, callerUid, shouldThrow = false) {
    const trace = [];
    let identity = callerUid;
    const original = { call(owner, taskId) {
        trace.push(['remove', taskId, identity, owner.name]);
        if (shouldThrow) throw new Error('OEM removal failure');
        return identity === 1000;
    }};
    const context = {
        ourUid, TAG: 'test', installed: [], Log: {e: (_, message) => {throw new Error(message);}},
        Java: {use(name) {
            assert.equal(name, 'com.android.server.wm.ActivityTaskManagerService');
            return {removeTask: {overload(signature) { assert.equal(signature, 'int'); return original; }}};
        }},
        Binder: {
            getCallingUid: () => identity,
            clearCallingIdentity() { const previous = identity; identity = 1000; trace.push(['clear']); return previous; },
            restoreCallingIdentity(token) { identity = token; trace.push(['restore', token]); }
        }
    };
    vm.runInNewContext(block, context);
    assert.equal(context.installed[0], 'ATMS.removeTask(native-uid)');
    return {trace, invoke: () => original.implementation.call({name: 'ATMS'}, 42), identity: () => identity};
}

let hook = install(10060, 10060);
assert.equal(hook.invoke(), true);
assert.deepEqual(hook.trace, [['clear'], ['remove', 42, 1000, 'ATMS'], ['restore', 10060]]);
assert.equal(hook.identity(), 10060);

hook = install(10060, 10123);
assert.equal(hook.invoke(), false);
assert.deepEqual(hook.trace, [['remove', 42, 10123, 'ATMS']]);

hook = install(-1, 10060);
assert.equal(hook.invoke(), false);
assert.deepEqual(hook.trace, [['remove', 42, 10060, 'ATMS']]);

hook = install(10060, 10060, true);
assert.throws(() => hook.invoke(), /OEM removal failure/);
assert.equal(hook.identity(), 10060);
assert.deepEqual(hook.trace.at(-1), ['restore', 10060]);

// Catch the original asynchronous crash path, which a synchronous try/catch around show cannot fix.
const host = fs.readFileSync(path.join(root,
    'Native/app/src/main/java/ru/big/town/anative/ClusterMediaHostActivity.java'), 'utf8');
assert(!host.includes('Toast.makeText(this,'));
assert(host.includes('manager.getDisplay(Display.DEFAULT_DISPLAY)'));
assert(host.includes('Toast.makeText(app.createDisplayContext(physical),'));
console.log('PASS: Native task-removal identity scope, restoration, and physical-display error notifications');
