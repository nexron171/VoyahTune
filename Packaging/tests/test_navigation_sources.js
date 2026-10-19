'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const profiles = ['od/inject/app_client.js', 'pi/inject/clusternavi.js'];
const blocks = profiles.map(file => {
    const script = fs.readFileSync(path.join(__dirname, '..', file), 'utf8');
    return script.slice(script.indexOf('function createNavigationSourcePolicy() {'),
        script.indexOf('function installClusterNavigation() {'));
});
assert.equal(blocks[0], blocks[1], 'OD and PI navigation adapters must stay identical');
const policyScope = {};
vm.runInNewContext(blocks[0], policyScope);
const policy = policyScope.createNavigationSourcePolicy();
for (const [input, meters] of [['150 м', 150], ['1,2 км', 1200], ['2.35 km', 2350],
    ['1\u202f200 m', 1200], ['1\u00a0200 м', 1200], ['0 m', 0]]) {
    assert.equal(policy.meters(input), meters, input);
}
for (const invalid of ['', '100', '-2 m', 'NaN km', '1,200 km', '1 mile', '9999999999 km',
    '2 h 30 m', '12 34 m', 'about 100 m']) {
    assert.equal(policy.meters(invalid), -1, invalid);
}
assert.equal(policy.yandexIcon('LEFT'), 7);
assert.equal(policy.yandexIcon('RIGHT'), 3);
assert.equal(policy.yandexIcon('UTURN_RIGHT'), 0);
assert.equal(policy.yandexIcon('UNKNOWN_FUTURE_TYPE'), 0);
assert.equal(policy.dgisIcon('crossroad_left'), 7);
assert.equal(policy.dgisIcon('/icons/crossroad_right.svg'), 3);
assert.equal(policy.dgisIcon('turn_over_right_hand'), 5);
assert.equal(policy.dgisIcon('ringroad_leftside_backward'), 0);
for (const mode of ['', 'pedestrian', 'freeroam', 'carstepbystep', 'bicycle', 'unknown']) {
    assert.equal(policy.driving(mode), false, mode);
}
assert.equal(policy.driving('car'), true);

function harness(packageName, missingClass) {
    const messages = [];
    const logs = [];
    const mainQueue = [];
    const workers = [];
    const intervals = [];
    let onMain = false;
    let time = 1000;
    let enabled = null;
    const method = original => {
        const overload = {call: original};
        const invoke = function (...args) {
            return overload.implementation ? overload.implementation.apply(this, args)
                : original(this, ...args);
        };
        invoke.overload = () => overload;
        return invoke;
    };
    const reference = value => ({get: () => value, $dispose: () => {}});
    const provider = method((resolver, uri, operation, argument, bundle) => {
        assert.equal(onMain, false, 'provider IPC must not run on the navigator main thread');
        assert.equal(uri, 'content://ru.big.town.anative.cluster.navigation');
        assert.equal(operation, 'maneuver');
        messages.push(bundle.values);
    });
    const resolver = {call: provider};
    const application = {getPackageName: () => packageName, getContentResolver: () => resolver};
    const guidanceMethods = {
        getNextManeuver: method(self => self.next),
        route: method(self => self.active ? {} : null),
        navigation: method(self => ({getGuidance: () => self.automotive})),
        onStart: method(() => {}),
        $init: method(() => {})
    };
    const dispatcher = {
        mode: 'car', badLocation: false,
        state: {mManeuverDistance: {value: '1,2 км'}, mManeuverIcon: {value: 'crossroad_left'},
            mManeuverDescription: {value: 'Road'}},
        getActiveNavigationMode() { return this.mode; },
        getNavigationState() { return this.state; },
        getSpeedInfo() { return {mBadLocation: {value: this.badLocation}}; }
    };
    const Dispatcher = {
        sInstance: {value: dispatcher},
        setNavigationState: method((self, arrival, remaining, total, distance, icon, description) => {
            self.state.mManeuverDistance.value = distance;
            self.state.mManeuverIcon.value = icon;
            self.state.mManeuverDescription.value = description;
        }),
        setActiveNavigationMode: method((self, mode) => { self.mode = mode; })
    };
    const classes = {
        'android.os.SystemClock': {elapsedRealtime: () => time},
        'android.provider.Settings$Global': {getString: () => enabled},
        'android.net.Uri': {parse: value => value},
        'android.os.Bundle': {$new: () => {
            const bundle = {values: {}};
            for (const kind of ['Boolean', 'Int', 'Long', 'String']) {
                bundle['put' + kind] = (name, value) => { bundle.values[name] = value; };
            }
            return bundle;
        }},
        'com.yandex.navikit.guidance.internal.GuidanceBinding': guidanceMethods,
        'com.yandex.mapkit.navigation.automotive.UpcomingManoeuvre': {},
        'com.yandex.navikit.guidance.GuidanceListener': {},
        'java.lang.ref.WeakReference': {$new: reference},
        'ru.dublgis.qsdk.IntegrationApplicationStateDispatcher': Dispatcher
    };
    const context = {
        console: {log: message => logs.push(message)},
        setTimeout: callback => workers.push(callback),
        setInterval: callback => intervals.push(callback),
        Java: {
            use: name => {
                if (!classes[name] || name === missingClass) throw new Error('Missing ' + name);
                return classes[name];
            },
            retain: value => value,
            cast: value => value,
            perform: callback => callback(),
            scheduleOnMainThread: callback => mainQueue.push(callback),
            registerClass: spec => ({$new: () => ({...spec.methods, $dispose: () => {}})})
        }
    };
    vm.runInNewContext(blocks[0], context);
    const installed = context.installNavigationSource(application);
    function flush() {
        for (let attempts = 0; mainQueue.length || workers.length; attempts++) {
            assert.ok(attempts < 100, 'callbacks must settle');
            onMain = true;
            while (mainQueue.length) mainQueue.shift()();
            onMain = false;
            while (workers.length) workers.shift()();
        }
    }
    function advance(milliseconds = 1000) {
        time += milliseconds;
        intervals.forEach(callback => callback());
        flush();
    }
    function createGuidance() {
        const point = latitude => ({getLatitude: () => latitude, getLongitude: () => 37});
        const state = {
            active: true, valid: true, status: 'ON_ROUTE', action: 'LEFT', road: 'Yandex road',
            position: point(55),
            next: {getPoint: () => point(55), getDistance: () => 250},
            isValid() { return this.valid; },
            equals(other) { return this === other; },
            $dispose() {},
            addGuidanceListener(listener) { this.listener = listener.get(); },
            removeGuidanceListener() {},
            ...guidanceMethods
        };
        const maneuver = {
            getPosition: () => ({getPoint: () => state.position}),
            getAnnotation: () => ({getAction: () => ({name: () => state.action}),
                getToponym: () => state.road})
        };
        state.automotive = {
            getRouteStatus: () => ({name: () => state.status}),
            getCurrentRoute: () => state.active ? {} : null,
            getWindshield: () => ({getManoeuvres: () => ({size: () => 1, get: () => maneuver})})
        };
        return state;
    }
    flush();
    return {
        installed, messages, logs, advance, flush, createGuidance,
        setEnabled: value => { enabled = value; },
        current: () => messages.at(-1),
        dispatcher,
        mode: value => Dispatcher.setActiveNavigationMode.call(dispatcher, value),
        state: (distance, icon = 'crossroad_right') => Dispatcher.setNavigationState.call(
            dispatcher, '', '', '', distance, icon, 'New road', '', '', 20)
    };
}

const yandex = harness('ru.yandex.yandexnavi');
assert.equal(yandex.installed, true);
assert.equal(yandex.current().navigationActive, false);
const route = yandex.createGuidance();
route.getNextManeuver(); // Already running before attach: no constructor interception needed.
yandex.flush();
yandex.advance();
assert.equal(yandex.current().navigationIcon, 7);
assert.equal(yandex.current().navigationDistance, 250);
assert.equal(yandex.current().navigationRoad, 'Yandex road');
route.status = 'NOT_ON_ROUTE';
yandex.advance();
assert.equal(yandex.current().navigationActive, false);
route.status = 'ON_ROUTE';
route.listener.onRoutePositionUpdated();
yandex.advance();
assert.equal(yandex.current().navigationActive, true);
route.listener.onFinishedRoute();
yandex.flush();
assert.equal(yandex.current().navigationActive, false);
yandex.advance();
assert.equal(yandex.current().navigationActive, false);
route.listener.onRouteChanged();
yandex.advance();
assert.equal(yandex.current().navigationActive, true);
yandex.advance(30000);
assert.equal(yandex.current().navigationActive, false, 'a stale engine must not be kept alive by polling');
route.listener.onLocationUpdated();
yandex.advance();
assert.equal(yandex.current().navigationActive, true);
route.position = {getLatitude: () => 56, getLongitude: () => 37};
yandex.advance();
assert.equal(yandex.current().navigationActive, false, 'distance and annotation must refer to the same turn');
route.active = false;
yandex.advance();
assert.equal(yandex.current().navigationActive, false);

const dgis = harness('ru.dublgis.dgismobile');
assert.equal(dgis.current().navigationIcon, 7);
assert.equal(dgis.current().navigationDistance, 1200);
dgis.state('50 м');
dgis.advance();
assert.equal(dgis.current().navigationIcon, 3);
assert.equal(dgis.current().navigationDistance, 50);
dgis.mode('freeroam');
dgis.flush();
assert.equal(dgis.current().navigationActive, false);
dgis.mode('car');
dgis.advance();
assert.equal(dgis.current().navigationActive, false, 'a mode change must not revive a previous route');
dgis.state('90 m');
dgis.advance();
assert.equal(dgis.current().navigationActive, true);
dgis.dispatcher.badLocation = true;
dgis.advance();
assert.equal(dgis.current().navigationActive, false);
dgis.dispatcher.badLocation = false;
dgis.advance(30000);
assert.equal(dgis.current().navigationActive, false);
dgis.state('80 m');
dgis.setEnabled('0');
dgis.advance();
assert.equal(dgis.current().navigationActive, false);
dgis.setEnabled('1');
dgis.state('70 m');
dgis.advance();
assert.equal(dgis.current().navigationDistance, 70);
dgis.state('bad distance');
dgis.advance();
assert.equal(dgis.current().navigationActive, false);
assert.equal(harness('ru.yandex.yandexnavi',
    'com.yandex.navikit.guidance.internal.GuidanceBinding').installed, false);
assert.equal(harness('unrelated.app').installed, false);
console.log('Navigation sources: OK (OD/PI, Yandex, 2GIS, lifecycle, freshness, units, worker IPC)');
