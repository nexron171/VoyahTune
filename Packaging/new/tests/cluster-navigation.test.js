'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const entry = 'function installClusterNavigation() {';
const profiles = ['agents/cluster-navigation.js'];

function loadHook(source, {setting = null, late = false, formatter = true, writer = true} = {}) {
    const calls = [];
    const messages = [];
    const scheduled = [];
    const descriptor = 'com.qinggan.cluster.IInstrumentClusterManagerService';
    const implementationName = 'com.qinggan.cluster.service.InstrumentClusterService$17';
    let observer;
    let retained = 0;
    const method = (types, body) => ({
        overload: (...actual) => {
            assert.deepEqual(actual, types);
            return body;
        }
    });
    const distanceUnit = {};
    const update = {call: (instance, info) => calls.push(info)};
    const binder = {
        getClass: () => ({getName: () => implementationName}),
        equals: instance => instance === binder,
        $dispose: () => {}
    };
    const transact = {call: (instance, code, info) => {
        if (code === 105) update.implementation.call(instance, info);
        return true;
    }};
    const serviceClass = {
        getName: () => 'com.qinggan.cluster.service.InstrumentClusterService',
        getSuperclass: () => null,
        getDeclaredFields: () => [{setAccessible: () => {}, get: () => binder}]
    };
    const services = {
        size: () => late ? 0 : 1,
        valueAt: () => ({getClass: () => serviceClass})
    };
    const resolver = {
        registerContentObserver: (uri, descendants, instance) => { observer = instance; }
    };
    const application = {getContentResolver: () => resolver};
    const newInfo = () => {
        const info = {};
        for (const field of ['GuideState', 'Display', 'TbtIconId', 'NextDistance',
            'CurrentRoadName', 'TurnRoadName', 'DestName']) {
            info['set' + field] = value => { info[field] = value; };
        }
        return info;
    };
    const classes = {
        'android.app.ActivityThread': {
            currentApplication: () => application,
            currentActivityThread: () => ({}),
            class: {getDeclaredField: name => {
                assert.equal(name, 'mServices');
                return {setAccessible: () => {}, get: () => services};
            }}
        },
        'android.provider.Settings$Global': {
            getString: (resolver, key) => {
                assert.equal(key, 'voyahtune_cluster_navigation_enabled');
                return setting;
            },
            getUriFor: key => key
        },
        'android.os.Handler': {$new: () => ({})},
        'android.os.Looper': {getMainLooper: () => ({})},
        'android.database.ContentObserver': {},
        'android.util.ArrayMap': {},
        'com.qinggan.cluster.GuidanceFormat': {
            toDistanceUnit: method(['android.content.Context', 'int'], distanceUnit)
        },
        [descriptor]: {class: {isInstance: candidate => candidate === binder}},
        [descriptor + '$Stub']: {
            onTransact: method(['int', 'android.os.Parcel', 'android.os.Parcel', 'int'], transact)
        },
        [implementationName]: {
            updateNaviInfo: method(['com.qinggan.cluster.info.NaviInfo'], update)
        },
        'com.qinggan.cluster.info.NaviInfo': {$new: newInfo},
        'com.qinggan.cluster.info.GuideState': {MAN_STOP: {value: 'MAN_STOP'}}
    };
    const sandbox = {
        console: {log: message => messages.push(message)},
        setTimeout: (callback, delay) => scheduled.push({callback, delay}),
        Java: {
            use: name => {
                if ((!formatter && name.endsWith('.GuidanceFormat'))
                    || (!writer && name === descriptor)) throw new Error('Missing OEM API');
                assert(name in classes, name);
                return classes[name];
            },
            cast: value => value,
            retain: value => { retained++; return value; },
            perform: callback => callback(),
            registerClass: spec => ({$new: handler => {
                assert.deepEqual(Array.from(spec.methods.$init[0].argumentTypes), ['android.os.Handler']);
                const instance = {
                    $super: {$init: value => assert.equal(value, handler)},
                    onChange: spec.methods.onChange[0].implementation
                };
                spec.methods.$init[0].implementation.call(instance, handler);
                return instance;
            }})
        }
    };
    vm.runInNewContext(source + '\ninstallClusterNavigation();', sandbox);
    function flush() {
        const immediate = scheduled.filter(task => task.delay === 0);
        for (const task of immediate) {
            scheduled.splice(scheduled.indexOf(task), 1);
            task.callback();
        }
    }
    return {
        calls, messages, distanceUnit,
        retained: () => retained,
        flush,
        change: (value, immediate = true) => {
            setting = value;
            observer.onChange(false);
            if (immediate) flush();
        },
        send: info => update.implementation.call(binder, info),
        transact: info => transact.implementation.call(binder, 105, info, null, 0),
        unrelatedTransaction: () => transact.implementation.call(binder, 999, null, null, 0)
    };
}

function assertStopped(info) {
    assert.equal(info.GuideState, 'MAN_STOP');
    assert.equal(info.Display, false);
    assert.equal(info.TbtIconId, 0);
    assert.equal(info.NextDistance, 0);
    assert.equal(info.TurnRoadName, '');
    assert.equal(info.CurrentRoadName, '');
    assert.equal(info.DestName, '');
}

function runClusterNavigationTests(units) {
    const scripts = profiles.map(file => fs.readFileSync(path.join(__dirname, '..', file), 'utf8'));
    const hooks = scripts.map(source => source.slice(source.indexOf(entry)));

    for (const source of hooks) {
        const active = {GuideState: 'GUIDING', TbtIconId: 7, NextDistance: 250, TurnRoadName: 'Road'};
        const runtime = loadHook(source);
        runtime.send(active);
        assert.equal(runtime.calls[0], active, 'default-enabled must preserve the complete OEM record');
        runtime.change('0');
        assertStopped(runtime.calls[1]);
        runtime.send(active);
        assertStopped(runtime.calls[2]);
        assert.equal(active.GuideState, 'GUIDING', 'the source record must not be mutated');
        runtime.change('1');
        assert.equal(runtime.calls.length, 3, 'enabling must not replay an old turn');
        runtime.send(active);
        assert.equal(runtime.calls[3], active);
        runtime.unrelatedTransaction();
        runtime.unrelatedTransaction();
        assert.equal(runtime.retained(), 1, 'Binder calls must reuse the captured service');
        runtime.change('0', false);
        runtime.change('1', false);
        runtime.flush();
        assert.equal(runtime.calls.length, 4, 'a queued clear must not erase newly enabled guidance');

        const disabled = loadHook(source, {setting: '0'});
        assertStopped(disabled.calls[0]);
        disabled.send(active);
        assertStopped(disabled.calls[1]);

        const late = loadHook(source, {setting: '0', late: true});
        late.transact(active);
        assert.equal(late.calls.length, 2);
        late.calls.forEach(assertStopped);
        late.change('1');
        late.transact(active);
        assert.equal(late.calls[2], active);

        for (const [meters, unit] of [[0, units.meters], [999, units.meters], [1000, units.kilometers], [1500, units.kilometers]]) {
            assert.equal(runtime.distanceUnit.implementation(null, meters), unit);
        }
        const noFormatter = loadHook(source, {formatter: false, setting: '0'});
        noFormatter.send(active);
        noFormatter.calls.forEach(assertStopped);
        assert(noFormatter.messages.includes('[cluster-navigation] hook ready v1'));
        const noWriter = loadHook(source, {writer: false});
        assert.equal(noWriter.distanceUnit.implementation(null, 1000), units.kilometers);
        assert(noWriter.messages.some(message => message.includes('[cluster-navigation] hook unavailable')));
    }

    console.log('Cluster maneuvers: defaults, disable/clear, fresh enable, late Binder, units and missing APIs passed');
}

module.exports = {runClusterNavigationTests};

if (require.main === module) {
    runClusterNavigationTests({meters: 'м', kilometers: 'км'});
}
