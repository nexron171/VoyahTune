const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const context = {Java: {perform() {}}, rpc: {}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../od/inject/app_client.js'), 'utf8'), context);

function gesture() {
    const track = context.createCarPlayAnchorTapTracker();
    return (action, x, y, time, eligible = true, pointers = 1, pointer = 0, key = 'driver') =>
        track(key, {action, x, y, time, pointers, pointer}, eligible, 8, 500);
}

test('a short marker tap tolerates small finger movement and fires once on release', () => {
    const event = gesture();
    assert.equal(event(0, 12, 350, 1000), false);
    assert.equal(event(2, 15, 352, 1060), false);
    assert.equal(event(1, 15, 352, 1150), true);
    assert.equal(event(1, 15, 352, 1150), false);
});

test('horizontal and vertical drags never become taps even after returning to the origin', () => {
    for (const [x, y] of [[24, 350], [12, 370], [20, 358]]) {
        const event = gesture();
        event(0, 12, 350, 1000);
        assert.equal(event(2, x, y, 1050), false);
        assert.equal(event(1, 12, 350, 1150), false);
    }
});

test('long press, cancellation, additional fingers and pointer changes do not trigger chrome', () => {
    for (const [action, time, pointers, pointer] of [
        [2, 1500, 1, 0], [3, 1100, 1, 0], [5, 1100, 2, 0], [2, 1100, 1, 1],
    ]) {
        const event = gesture();
        event(0, 12, 350, 1000);
        assert.equal(event(action, 12, 350, time, true, pointers, pointer), false);
        assert.equal(event(1, 12, 350, 1501), false);
    }
});

test('touches outside the marker, an expanded shade or another foreground stay OEM-only', () => {
    const event = gesture();
    event(0, 12, 350, 1000, false);
    assert.equal(event(1, 12, 350, 1100), false);
    event(0, 12, 350, 1200);
    assert.equal(event(1, 12, 350, 1300, false), false);
    event(0, 12, 350, 1400);
    event(2, 12, 350, 1450, false);
    assert.equal(event(1, 12, 350, 1500), false);
});

test('driver and passenger gestures remain independent', () => {
    const event = gesture();
    event(0, 12, 350, 1000);
    event(0, 12, 350, 1010, true, 1, 0, 'passenger');
    event(2, 90, 350, 1100);
    assert.equal(event(1, 12, 350, 1150, true, 1, 0, 'passenger'), true);
    assert.equal(event(1, 90, 350, 1160), false);
});

function anchorHook(displayId = 0, tag = 'VehicleHiBoardBarNewOTA') {
    const carPlay = 'ru.big.town.anative.CarPlayFullscreenActivity';
    const state = {activity: carPlay, elapsed: 50000, lookupError: false};
    const calls = [];
    const writes = [];
    const order = [];
    const original = {
        call(receiver, motion) {
            calls.push({receiver, motion});
            order.push('oem:' + motion.getActionMasked());
            if (state.onOriginal) state.onOriginal(motion);
            return motion.result;
        },
    };
    const field = value => ({value});
    const delegate = {
        getDisplay: () => ({getDisplayId: () => displayId}),
        getResources: () => ({getDisplayMetrics: () => ({density: field(1)})}),
        getWidth: () => 30,
        getHeight: () => 720,
    };
    const bar = {
        mDelegateView: field(delegate),
        mLineView: field({getGlobalVisibleRect(bounds) {
            bounds.top = field(290);
            bounds.bottom = field(430);
            return true;
        }}),
    };
    const window = {
        mTag: field(tag),
        mWindowStatusListener: field(bar),
        mLp: field({x: field(-1770), minOffset: field(-1770)}),
        isCollapsing: field({get: () => false}),
        isExpanding: field({get: () => false}),
        mScaledTouchSlop: field(8),
    };
    const classes = {
        'com.qinggan.app.hiboard.BlindsWindow': {handleTouch: {overload: () => original}},
        'com.qinggan.os.ServiceManager': {getDpyTopAppInfo() {
            if (state.lookupError) throw Error('top activity unavailable');
            return state.activity;
        }},
        'android.provider.Settings$Global': {putLong(resolver, key, deadline) {
            order.push('write');
            writes.push({key, deadline});
            return true;
        }},
        'android.os.SystemClock': {elapsedRealtime: () => state.elapsed},
        'java.lang.System': {identityHashCode: () => 1},
        'android.view.ViewConfiguration': {getLongPressTimeout: () => 500},
        'android.graphics.Rect': {$new: () => ({})},
        'android.util.Log': {i() {}, w() {}, e() {}},
    };
    for (const name of ['VehicleHiBoardBarNew', 'VehicleHiBoardBarNewOTA']) {
        classes['com.qinggan.app.hiboard.vehiclehiboard.main.' + name] = {};
    }
    classes['com.qinggan.app.hiboard.vehiclehiboard.second.SecondVehicleHiboardBar'] = {};
    const runtime = {
        Java: {perform() {}, cast: value => value, use(name) {
            assert.ok(classes[name], 'unexpected Java class: ' + name);
            return classes[name];
        }},
        rpc: {},
        console: {log() {}},
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../od/inject/app_client.js'), 'utf8'), runtime);
    assert.equal(runtime.installCarPlayAnchorTap({getContentResolver: () => 'resolver'}), true);
    return {
        state, calls, writes, order, window,
        dispatch(motion) { return original.implementation.call(window, motion); },
    };
}

function motion(action, time, x = 12, y = 350, history = []) {
    return {
        result: action !== 2,
        getActionMasked: () => action,
        getX: () => x,
        getY: () => y,
        getEventTime: () => time,
        getPointerCount: () => 1,
        getPointerId: () => 0,
        getHistorySize: () => history.length,
        getHistoricalX: index => history[index].x,
        getHistoricalY: index => history[index].y,
        getHistoricalEventTime: index => history[index].time,
    };
}

test('OEM adapter forwards each event and return value, then reveals the tapped physical display', () => {
    for (const [displayId, tag] of [
        [0, 'VehicleHiBoardBarNew'], [0, 'VehicleHiBoardBarNewOTA'], [1, 'SecondVehicleHiboardBar'],
    ]) {
        for (const y of [350, 695]) {
            const hook = anchorHook(displayId, tag);
            const events = [motion(0, 1000, 12, y), motion(2, 1050, 13, y), motion(1, 1100, 13, y)];
            for (const event of events) assert.equal(hook.dispatch(event), event.result);
            assert.deepEqual(hook.calls.map(call => call.motion), events);
            assert.ok(hook.calls.every(call => call.receiver === hook.window));
            assert.deepEqual(hook.order, ['oem:0', 'oem:2', 'oem:1', 'write']);
            assert.deepEqual(hook.writes, [{
                key: 'voyahtune_carplay_chrome_reveal_until_' + displayId,
                deadline: 60000,
            }]);
        }
    }
});

test('a coalesced drag and ACTION_CANCEL retain the complete OEM stream without a reveal', () => {
    for (const intermediate of [
        motion(2, 1100, 12, 350, [{x: 80, y: 350, time: 1050}]),
        motion(3, 1100),
    ]) {
        const hook = anchorHook();
        const events = [motion(0, 1000), intermediate, motion(1, 1150)];
        for (const event of events) assert.equal(hook.dispatch(event), event.result);
        assert.deepEqual(hook.calls.map(call => call.motion), events);
        assert.deepEqual(hook.writes, []);
    }
});

test('a failed foreground lookup cancels the tap without interrupting OEM handling', () => {
    const hook = anchorHook();
    hook.dispatch(motion(0, 1000));
    hook.state.lookupError = true;
    assert.equal(hook.dispatch(motion(2, 1050)), false);
    hook.state.lookupError = false;
    assert.equal(hook.dispatch(motion(1, 1100)), true);
    assert.equal(hook.calls.length, 3);
    assert.deepEqual(hook.writes, []);
});

test('foreign windows, expanded shade and an OEM foreground change cannot reveal panels', () => {
    for (const configure of [
        hook => { hook.state.activity = 'com.qinggan.launcher.HomeActivity'; },
        hook => { hook.window.mTag.value = 'HiboardBar'; },
        hook => { hook.window.mLp.value.x.value = 0; },
        hook => { hook.state.onOriginal = event => {
            if (event.getActionMasked() === 1) hook.state.activity = 'CameraActivity';
        }; },
    ]) {
        const hook = anchorHook();
        configure(hook);
        assert.equal(hook.dispatch(motion(0, 1000)), true);
        assert.equal(hook.dispatch(motion(1, 1100)), true);
        assert.equal(hook.calls.length, 2);
        assert.deepEqual(hook.writes, []);
    }
});
