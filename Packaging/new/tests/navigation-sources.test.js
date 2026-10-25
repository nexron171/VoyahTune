"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../agents/app-client.js"), "utf8");
const navigationSource = source.slice(source.indexOf("function createNavigationSourcePolicy() {"));
const baseCallbacks = [
    "onBackgroundGuidanceTaskRemoved", "onFasterAlternativeAnnotated",
    "onFasterAlternativeUpdated", "onFreeDriveRouteChanged", "onManeuverAnnotated",
    "onReachedWayPoint", "onStandingStatusUpdated", "onBackgroundGuidanceWillBeSuspended",
    "onParkingRouteBuilt", "onLocationUpdated", "onRoutePositionUpdated", "onRouteChanged",
    "onFinishedRoute"
];
const additionalCallbacks = ["onJamForecastUpdated", "onGuidanceResumedChanged"];

function navigator(optionalCallbacks, incompatibleSignature = false) {
    const messages = [];
    const logs = [];
    const mainQueue = [];
    const workers = [];
    const intervals = [];
    let time = 1000;
    let registeredMethods;

    function method(original) {
        const overload = { call: original };
        const invoke = function (...args) {
            return overload.implementation
                ? overload.implementation.apply(this, args)
                : original(this, ...args);
        };
        invoke.overload = () => overload;
        return invoke;
    }

    const guidanceMethods = {
        getNextManeuver: method(self => self.next),
        route: method(self => self.active ? {} : null),
        navigation: method(self => ({ getGuidance: () => self.automotive })),
        onStart: method(() => {}),
        $init: method(() => {})
    };
    const listenerInterface = {};
    for (const name of [...baseCallbacks, ...optionalCallbacks]) {
        listenerInterface[name] = {
            overload(...parameters) {
                assert.equal(parameters.length, 0);
                if (incompatibleSignature && optionalCallbacks.includes(name)) {
                    throw new Error("No zero-argument overload: " + name);
                }
                return {};
            }
        };
    }

    const classes = {
        "android.os.SystemClock": { elapsedRealtime: () => time },
        "android.provider.Settings$Global": { getString: () => "1" },
        "android.net.Uri": { parse: value => value },
        "android.os.Bundle": {
            $new() {
                const bundle = { values: {} };
                for (const kind of ["Boolean", "Int", "Long", "String"]) {
                    bundle["put" + kind] = (name, value) => { bundle.values[name] = value; };
                }
                return bundle;
            }
        },
        "com.yandex.navikit.guidance.internal.GuidanceBinding": guidanceMethods,
        "com.yandex.mapkit.navigation.automotive.UpcomingManoeuvre": {},
        "com.yandex.navikit.guidance.GuidanceListener": listenerInterface,
        "java.lang.ref.WeakReference": {
            $new: value => ({ get: () => value, $dispose() {} })
        }
    };
    const resolver = {
        call: method((receiver, uri, operation, argument, bundle) => {
            assert.equal(uri, "content://ru.big.town.anative.cluster.navigation");
            assert.equal(operation, "maneuver");
            messages.push(bundle.values);
        })
    };
    const context = {
        console: { log: message => logs.push(message) },
        setTimeout: callback => workers.push(callback),
        setInterval: callback => intervals.push(callback),
        Java: {
            use(name) {
                if (!(name in classes)) throw new Error("Missing class: " + name);
                return classes[name];
            },
            retain: value => value,
            cast: value => value,
            perform: callback => callback(),
            scheduleOnMainThread: callback => mainQueue.push(callback),
            registerClass(specification) {
                assert.equal(specification.implements[0], listenerInterface);
                assert.deepEqual(Object.keys(specification.methods).sort(),
                    Object.keys(listenerInterface).sort(), "implement exactly the installed interface");
                registeredMethods = specification.methods;
                return { $new: () => ({ ...registeredMethods, $dispose() {} }) };
            }
        }
    };
    vm.runInNewContext(navigationSource, context);
    const installed = context.installNavigationSource({
        getPackageName: () => "ru.yandex.yandexnavi",
        getContentResolver: () => resolver
    });

    function flush() {
        for (let passes = 0; mainQueue.length || workers.length; passes++) {
            assert.ok(passes < 100, "callbacks must settle");
            while (mainQueue.length) mainQueue.shift()();
            while (workers.length) workers.shift()();
        }
    }

    const point = { getLatitude: () => 55, getLongitude: () => 37 };
    const maneuver = {
        getPosition: () => ({ getPoint: () => point }),
        getAnnotation: () => ({
            getAction: () => ({ name: () => "LEFT" }),
            getToponym: () => "Route road"
        })
    };
    const route = {
        active: true,
        next: { getPoint: () => point, getDistance: () => 250 },
        isValid: () => true,
        equals(other) { return this === other; },
        $dispose() {},
        addGuidanceListener(reference) { this.listener = reference.get(); },
        removeGuidanceListener() {},
        automotive: {
            getRouteStatus: () => ({ name: () => "ON_ROUTE" }),
            getCurrentRoute: () => ({}),
            getWindshield: () => ({ getManoeuvres: () => ({ size: () => 1, get: () => maneuver }) })
        },
        ...guidanceMethods
    };
    flush();
    return {
        installed, logs, route, flush,
        current: () => messages.at(-1),
        advance(milliseconds = 1000) {
            time += milliseconds;
            intervals.forEach(callback => callback());
            flush();
        }
    };
}

for (const [name, optionalCallbacks] of [
    ["legacy GuidanceListener", []],
    ["GuidanceListener with both new notifications", additionalCallbacks],
    ["GuidanceListener with jam notifications only", [additionalCallbacks[0]]],
    ["GuidanceListener with resume notifications only", [additionalCallbacks[1]]]
]) {
    test(name + " preserves maneuver delivery and route lifecycle", () => {
        const navigation = navigator(optionalCallbacks);
        assert.equal(navigation.installed, true, navigation.logs.join("\n"));
        navigation.route.getNextManeuver();
        navigation.flush();
        navigation.advance();
        assert.equal(navigation.current().navigationActive, true);
        assert.equal(navigation.current().navigationIcon, 7);
        assert.equal(navigation.current().navigationDistance, 250);
        assert.equal(navigation.current().navigationRoad, "Route road");

        navigation.advance(30000);
        optionalCallbacks.forEach(callback => navigation.route.listener[callback]());
        navigation.advance();
        assert.equal(navigation.current().navigationActive, false,
            "optional notifications must not revive a stale maneuver");

        navigation.route.listener.onRoutePositionUpdated();
        navigation.advance();
        assert.equal(navigation.current().navigationActive, true);
        navigation.route.listener.onFinishedRoute();
        navigation.flush();
        optionalCallbacks.forEach(callback => navigation.route.listener[callback]());
        navigation.advance();
        assert.equal(navigation.current().navigationActive, false);
        navigation.route.listener.onRouteChanged();
        navigation.advance();
        assert.equal(navigation.current().navigationActive, true);
    });
}

test("an unsupported notification signature does not report readiness", () => {
    const navigation = navigator(additionalCallbacks, true);
    assert.equal(navigation.installed, false);
    assert.ok(navigation.logs.some(message => message.includes("No zero-argument overload")));
    assert.ok(!navigation.logs.some(message => message.includes("[navigation-source] hook ready")));
});
