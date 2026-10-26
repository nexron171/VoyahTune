const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../agents/app-client.js'), 'utf8');
const context = {Java: {perform() {}}, rpc: {}};
vm.runInNewContext(source, context);
const carPlay = 'ru.big.town.anative.CarPlayFullscreenActivity';

function harness() {
    const top = ['', ''];
    const visible = [true, true];
    const timers = [];
    const errors = [];
    const calls = [];
    const fail = [false, false];
    let now = 1000;
    const revealUntil = [0, 0];
    const hiddenForCarPlay = [-1, -1];
    const controller = context.createCarPlayStatusBarController({
        revealRemaining(displayId) { return Math.max(0, revealUntil[displayId] - now); },
        top(displayId) {
            if (top[displayId] instanceof Error) throw top[displayId];
            return top[displayId];
        },
        stockHidden: activity => activity === 'Camera' || activity === 'Video',
        apply(displayId, show) {
            if (fail[displayId]) throw new Error('WindowManager unavailable');
            visible[displayId] = show;
            calls.push({displayId, show});
        },
        reportHidden(displayId, hidden) {
            assert(!hidden || !visible[displayId], 'Report hidden only after the OEM bar is hidden');
            hiddenForCarPlay[displayId] = hidden ? 1 : 0;
        },
        schedule: (callback, delay) => timers.push({callback, delay}),
        error: error => errors.push(error),
    });
    return {
        top, visible, hiddenForCarPlay, timers, errors, calls, fail, controller, revealUntil,
        reveal(displayId) {
            revealUntil[displayId] = now + 10000;
            controller.reconcile();
        },
        tick() {
            assert.equal(timers.length, 1);
            const timer = timers.shift();
            now += timer.delay;
            timer.callback();
        },
    };
}

test('status reports are independent of the dock and follow successful OEM changes', () => {
    const app = harness();
    app.top[1] = carPlay;
    app.controller.reconcile();
    assert.deepEqual(app.hiddenForCarPlay, [-1, 1]);
    app.reveal(1);
    assert.equal(app.hiddenForCarPlay[1], 0);
    app.revealUntil[1] = 0;
    app.controller.reconcile();
    assert.equal(app.hiddenForCarPlay[1], 1);
    app.fail[1] = true;
    app.top[1] = '';
    app.controller.reconcile();
    assert.equal(app.hiddenForCarPlay[1], 1);
    app.fail[1] = false;
    app.controller.reconcile();
    assert.equal(app.hiddenForCarPlay[1], 0);
});

test('only the exact CarPlay Activity on a physical display owns the status bar', () => {
    for (const activity of [carPlay, 'ru.big.town.anative/.CarPlayFullscreenActivity',
        'ru.big.town.anative/' + carPlay]) {
        assert.equal(context.isCarPlayStatusBarTarget(activity, 0), true);
        assert.equal(context.isCarPlayStatusBarTarget(activity, 1), true);
        assert.equal(context.isCarPlayStatusBarTarget(activity, 7), false);
    }
    const app = harness();
    for (const activity of ['ru.big.town.anative.SplitHostActivity',
        'ru.big.town.anative.CarPlayWindowedActivity',
        'ru.big.town.anative.AdvanceActivity', 'com.shilapi.xcertplay.CarPlayHostActivity', '']) {
        app.top[0] = activity;
        app.controller.reconcile();
    }
    assert.equal(app.calls.length, 0);
    app.top[0] = carPlay;
    app.controller.reconcile();
    assert.deepEqual(app.visible, [false, true]);
    app.controller.request(0, true);
    assert.equal(app.visible[0], false, 'Late OEM show cannot cover CarPlay');
});

test('switching to windowed CarPlay releases status-bar ownership on both displays', () => {
    const app = harness();
    app.top[0] = app.top[1] = carPlay;
    app.controller.reconcile();
    assert.deepEqual(app.visible, [false, false]);
    for (const displayId of [0, 1]) {
        app.top[displayId] = 'ru.big.town.anative.CarPlayWindowedActivity';
        app.controller.reconcile();
        assert.equal(app.visible[displayId], true);
    }
    assert.deepEqual(app.visible, [true, true]);
});

test('temporary reveal defeats late OEM hide, renews and expires independently of Native', () => {
    const app = harness();
    app.top[0] = carPlay;
    app.top[1] = carPlay;
    app.controller.reconcile();
    app.reveal(0);
    app.controller.request(0, false);
    assert.deepEqual(app.visible, [true, false]);
    for (let second = 0; second < 8; second++) app.tick();
    app.reveal(0);
    for (let second = 0; second < 9; second++) app.tick();
    assert.equal(app.visible[0], true);
    app.tick();
    assert.deepEqual(app.visible, [false, false]);
});

test('a reveal on the old screen does not reveal the destination or override the camera', () => {
    const app = harness();
    app.top[0] = carPlay;
    app.reveal(0);
    app.top[0] = 'Camera';
    app.top[1] = carPlay;
    app.tick();
    assert.deepEqual(app.visible, [false, false]);
    app.top[1] = '';
    app.tick();
    assert.deepEqual(app.visible, [false, true]);
});

test('clearing the lease on pause hides a resumed CarPlay without waiting for expiry', () => {
    const app = harness();
    app.top[0] = carPlay;
    app.reveal(0);
    app.revealUntil[0] = 0;
    app.controller.reconcile();
    assert.equal(app.visible[0], false);
    app.top[0] = 'Home';
    app.controller.reconcile();
    assert.equal(app.visible[0], true);
});

test('a transfer restores the source and hides the destination independently', () => {
    for (const [from, to] of [[0, 1], [1, 0]]) {
        const app = harness();
        app.top[from] = carPlay;
        app.controller.reconcile();
        app.top[from] = 'Home';
        app.top[to] = carPlay;
        app.tick();
        assert.equal(app.visible[from], true);
        assert.equal(app.visible[to], false);
    }
});

test('death or an unavailable foreground service restores the bar without a transition event', () => {
    for (const next of ['', 'ru.big.town.anative.AdvanceActivity', new Error('Binder gone')]) {
        const app = harness();
        app.top[1] = carPlay;
        app.controller.reconcile();
        app.top[1] = next;
        app.tick();
        assert.deepEqual(app.visible, [true, true]);
        assert.equal(app.timers.length, 0);
    }
});

test('leaving CarPlay preserves OEM camera and video hiding', () => {
    for (const activity of ['Camera', 'Video']) {
        const app = harness();
        app.top[0] = carPlay;
        app.controller.reconcile();
        app.top[0] = activity;
        app.tick();
        assert.equal(app.visible[0], false);
        assert.equal(app.timers.length, 0);
        app.top[0] = 'Home';
        app.controller.request(0, true);
        assert.equal(app.visible[0], true);
    }
});

test('a failed restoration is retried and cannot block the other display', () => {
    const app = harness();
    app.top[0] = carPlay;
    app.controller.reconcile();
    app.top[0] = 'Home';
    app.top[1] = carPlay;
    app.fail[0] = true;
    app.tick();
    assert.deepEqual(app.visible, [false, false]);
    app.fail[0] = false;
    app.tick();
    assert.deepEqual(app.visible, [true, false]);
});

test('late attachment discovers CarPlay without changing the unrelated passenger window', () => {
    const app = harness();
    app.top[0] = carPlay;
    app.top[1] = 'Camera';
    app.visible[1] = false;
    app.controller.reconcile();
    assert.deepEqual(app.calls, [{displayId: 0, show: false}]);
    app.visible[0] = true;
    app.tick();
    assert.deepEqual(app.visible, [false, false]);
});
