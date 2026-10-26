const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(
    path.join(__dirname, '../adapters/launcher/split-navigation.js'),
    'utf8',
);
const nativePackage = 'ru.big.town.anative';
const carPlayActivity = nativePackage + '.CarPlayFullscreenActivity';

function production(name) {
    const match = new RegExp('^([ ]*)function ' + name + '\\(', 'm').exec(source);
    assert(match, name);
    const closing = '\n' + match[1] + '}';
    const end = source.indexOf(closing, match.index);
    assert(end > match.index, name + ' closing brace');
    return source.slice(match.index, end + closing.length);
}

function harness(savedClaims = [false, false]) {
    const top = ['com.qinggan.launcher|HomeActivity', 'com.qinggan.launcher|HomeActivity'];
    const dockVisible = [true, true];
    const statusVisible = [true, true];
    const saved = [...savedClaims];
    const requests = [];
    const timers = [];
    const errors = [];
    const failStatus = [false, false];
    const failDock = [false, false];
    const controllers = [];
    const overloads = {};
    const application = {};
    application.getContentResolver = () => ({});
    let now = 1000;
    const revealUntil = [0, 0];
    const dockHidden = [-1, -1];
    const context = {
        OUR_PKG: nativePackage,
        CARPLAY_FULLSCREEN_HOST: carPlayActivity,
        CARPLAY_CHROME_REVEAL_SETTING: 'voyahtune_carplay_chrome_reveal_until_',
        SettingsGlobal: {
            getLong: (_, key) => revealUntil[Number(key.slice(-1))],
            getInt: (_, key) => dockHidden[Number(key.slice(-1))],
            putInt(_, key, value) {
                assert(key.startsWith('voyahtune_carplay_dock_hidden_'));
                const displayId = Number(key.slice(-1));
                assert.equal(dockVisible[displayId], value !== 1,
                    'Report visibility only after the OEM dock has changed');
                dockHidden[displayId] = value;
                return true;
            },
        },
        SystemClock: {elapsedRealtime: () => now},
        TAG: 'test',
        cache: {fullscreen: {}},
        restoringCarPlayDock: [false, false],
        fgByScreen: {0: {pkg: '', act: ''}, 1: {pkg: '', act: ''}},
        AccountConstantUtil: null,
        LauncherAppUtils: {
            getTopAppInfo(_, displayId) {
                if (top[displayId] instanceof Error) throw top[displayId];
                return top[displayId];
            },
        },
        retainedLauncherModel: null,
        carPlayChromeHidden: [...savedClaims],
        carPlayChromeCheckPending: false,
        carPlayStatusBus: null,
        carPlayStatusBarRequest: [null, null],
        carPlayChromePreferences: {
            edit: () => ({
                putBoolean(key, value) {
                    saved[Number(key.slice(-1))] = value;
                    return this;
                },
                apply() {},
            }),
        },
        ctx: () => application,
        Log: {
            i() {},
            w: (_, message) => errors.push(message),
            e: (_, message) => errors.push(message),
        },
        dockField: (model, field) => model[field],
        runtimeObject: controller => controller ?? null,
        forceHideDockController(controller) {
            if (controller == null) return false;
            controller.dismiss();
            return true;
        },
        dockKept: () => false,
        setTimeout(callback, delay) {
            assert(delay > 0 && delay <= 1000);
            timers.push({callback, delay});
        },
        Java: {
            retain: model => model,
            scheduleOnMainThread: callback => callback(),
            use(name) {
                switch (name) {
                    case 'com.qinggan.bus.QGBus':
                        return {
                            $new: () => ({
                                publish(event) {
                                    assert.equal(event.destination, 'com.qinggan.systemui');
                                    assert.equal(event.type, 'VOYAHTUNE_CARPLAY_STATUS_BAR_SYNC');
                                    const {displayId, isVisible} = event.data;
                                    assert(displayId === 0 || displayId === 1);
                                    assert.equal(typeof isVisible, 'boolean');
                                    if (failStatus[displayId]) throw new Error('SystemUI unavailable');
                                    requests.push({displayId, visible: isVisible});
                                    statusVisible[displayId] = isVisible;
                                },
                            }),
                        };
                    case 'com.qinggan.bus.QGBusEvent':
                        return {
                            $new: () => ({
                                setDestination(value) { this.destination = value; },
                                setEventType(value) { this.type = value; },
                                setData(value) { this.data = value; },
                            }),
                        };
                    case 'android.os.Bundle':
                        return {
                            $new: () => ({
                                putBoolean(key, value) { this[key] = value; },
                                putInt(key, value) { this[key] = value; },
                            }),
                        };
                    default:
                        throw new Error('Unexpected Java class: ' + name);
                }
            },
        },
        TopLM: {},
    };
    const model = {};
    for (const displayId of [0, 1]) {
        const controller = {
            show() {
                if (failDock[displayId]) throw new Error('Dock unavailable');
                assert.equal(
                    context.hidesPhysicalDock(context.topActivityForScreen(displayId), displayId),
                    false,
                    'A restore must bypass the fullscreen gate',
                );
                dockVisible[displayId] = true;
            },
            dismiss() { dockVisible[displayId] = false; },
        };
        controllers.push(controller);
        const field = displayId === 0 ? 'mMainScreenNavigationBar' : 'mSecondScreenNavigationBar';
        const method = displayId === 0
            ? 'handleUpdateMainNavigationBar' : 'handleUpdateSecondNavigationBar';
        model[field] = controller;
        overloads[method] = {
            call(_, packageName, activity, visible) {
                if (visible) controller.show();
                else controller.dismiss();
            },
        };
        context.TopLM[method] = {overload: () => overloads[method]};
        model[method] = (...args) => overloads[method].implementation.apply(model, args);
    }
    vm.createContext(context);
    vm.runInContext([
        'cleanJavaString', 'isUserFullscreen', 'isCarPlayFullscreen', 'hidesPhysicalDock',
        'carPlayChromeRevealRemaining', 'reportCarPlayDockHidden', 'carPlayChromeTransition',
        'parseTopActivity', 'topActivityForScreen',
        'rememberCarPlayChrome', 'setCarPlayStatusBarVisible', 'reconcileCarPlayStatusBar',
        'modelDockController', 'installFullscreenVisibilityGate', 'reconcilePhysicalDock',
        'reconcilePhysicalDisplays', 'scheduleCarPlayChromeCheck',
    ].map(production).join('\n'), context);
    context.installFullscreenVisibilityGate('handleUpdateMainNavigationBar', 0);
    context.installFullscreenVisibilityGate('handleUpdateSecondNavigationBar', 1);

    return {
        context, model, top, dockVisible, dockHidden, statusVisible, saved, requests,
        timers, errors, failStatus, failDock, revealUntil,
        reveal(displayId) {
            revealUntil[displayId] = now + 10000;
            this.reconcile();
        },
        advance(duration) { now += duration; },
        carPlay(displayId, activity = carPlayActivity) {
            top[displayId] = nativePackage + '|' + activity;
        },
        reconcile() {
            context.reconcilePhysicalDisplays(model, application, 'test');
        },
        tick() {
            assert.equal(timers.length, 1, 'Only one watchdog may be queued');
            const timer = timers.shift();
            now += timer.delay;
            timer.callback();
        },
    };
}

test('dock visibility reports follow hiding, reveal and display transfer', () => {
    const app = harness();
    app.carPlay(0);
    app.reconcile();
    assert.deepEqual(app.dockHidden, [1, -1]);
    app.reveal(0);
    assert.deepEqual(app.dockHidden, [0, -1]);
    app.advance(10001);
    app.reconcile();
    assert.deepEqual(app.dockHidden, [1, -1]);
    app.top[0] = 'com.qinggan.app.launcher|HomeActivity';
    app.carPlay(1);
    app.reconcile();
    assert.deepEqual(app.dockHidden, [0, 1]);
});

test('a failed dock restoration retains the hidden report until recovery', () => {
    const app = harness();
    app.carPlay(0);
    app.reconcile();
    app.top[0] = 'com.qinggan.app.launcher|HomeActivity';
    app.failDock[0] = true;
    app.reconcile();
    assert.equal(app.dockHidden[0], 1);
    app.failDock[0] = false;
    app.reconcile();
    assert.equal(app.dockHidden[0], 0);
});

test('only the live physical fullscreen host owns chrome; unrelated screens stay intact', () => {
    const app = harness();
    app.reconcile();
    assert.equal(app.requests.length, 0);
    assert.equal(app.timers.length, 0);

    for (const activity of [
        'ru.big.town.anative.SplitHostActivity',
        'ru.big.town.anative.CarPlayWindowedActivity',
        'ru.big.town.anative.AdvanceActivity',
        'com.shilapi.xcertplay.CarPlayHostActivity',
        'com.shilapi.xcertplay.DiPlayActivity',
    ]) {
        app.carPlay(0, activity);
        app.reconcile();
        assert.deepEqual(app.dockVisible, [true, true]);
        assert.deepEqual(app.statusVisible, [true, true]);
    }
    app.carPlay(0, '.CarPlayFullscreenActivity');
    app.reconcile();
    assert.deepEqual(app.dockVisible, [false, true]);
    assert.deepEqual(app.statusVisible, [false, true]);
    assert.deepEqual(app.saved, [true, false]);

    app.context.reconcilePhysicalDock(app.model, null, 42, 'virtual display');
    assert.equal(app.requests.length, 1);
    app.model.handleUpdateMainNavigationBar('stale.app', 'StaleActivity', true);
    assert.equal(app.dockVisible[0], false, 'Late OEM show cannot cover the fullscreen host');
});

test('windowed CarPlay keeps the OEM dock under ordinary inset-window policy', () => {
    const app = harness();
    app.context.cfg = () => '1';
    vm.runInContext(['ourInsetActivity', 'dockKept'].map(production).join('\n'), app.context);
    assert.equal(app.context.dockKept(nativePackage, nativePackage + '.CarPlayWindowedActivity'), true);
    assert.equal(app.context.dockKept(nativePackage, carPlayActivity), false);
    assert.equal(app.context.dockKept(nativePackage, nativePackage + '.OtherActivity'), false);
    app.context.cfg = () => '0';
    assert.equal(app.context.dockKept(nativePackage, nativePackage + '.CarPlayWindowedActivity'), false);
});

test('windowed CarPlay restores both OEM panels on either physical screen', () => {
    for (const displayId of [0, 1]) {
        const app = harness();
        app.carPlay(displayId);
        app.reconcile();
        assert.equal(app.dockVisible[displayId], false);
        assert.equal(app.statusVisible[displayId], false);
        app.carPlay(displayId, nativePackage + '.CarPlayWindowedActivity');
        app.reconcile();
        assert.deepEqual(app.dockVisible, [true, true]);
        assert.deepEqual(app.statusVisible, [true, true]);
        assert.deepEqual(app.saved, [false, false]);
        app.carPlay(displayId);
        app.reconcile();
        assert.equal(app.dockVisible[displayId], false);
        assert.equal(app.statusVisible[displayId], false);
    }
});

test('the access button reveals only its screen and expires without an Activity callback', () => {
    const app = harness();
    app.carPlay(0);
    app.carPlay(1);
    app.reconcile();
    app.reveal(0);
    assert.deepEqual(app.dockVisible, [true, false]);
    assert.deepEqual(app.statusVisible, [true, false]);
    app.model.handleUpdateMainNavigationBar(nativePackage, carPlayActivity, false);
    assert.equal(app.dockVisible[0], true, 'Late OEM hide cannot cancel the timed reveal');
    for (let second = 0; second < 9; second++) app.tick();
    assert.equal(app.dockVisible[0], true);
    app.tick();
    assert.deepEqual(app.dockVisible, [false, false]);
    assert.deepEqual(app.statusVisible, [false, false]);
});

test('another press renews the deadline and cancellation immediately hides CarPlay chrome', () => {
    const app = harness();
    app.carPlay(0);
    app.reveal(0);
    for (let second = 0; second < 8; second++) app.tick();
    app.reveal(0);
    for (let second = 0; second < 8; second++) app.tick();
    assert.equal(app.dockVisible[0], true);
    app.revealUntil[0] = 0;
    app.reconcile();
    assert.equal(app.dockVisible[0], false);
});

test('a reveal cannot override unrelated fullscreen policy or migrate to another display', () => {
    const app = harness();
    app.carPlay(0);
    app.reveal(0);
    app.top[0] = 'video.player|VideoActivity';
    app.context.cache.fullscreen['video.player'] = true;
    app.carPlay(1);
    app.reconcile();
    assert.deepEqual(app.dockVisible, [false, false]);
    assert.equal(app.statusVisible[1], false);
});

test('expired or implausible persisted deadlines cannot reveal CarPlay after reboot', () => {
    const app = harness();
    app.carPlay(0);
    for (const deadline of [0, 1000, 11001, Number.NaN]) {
        app.revealUntil[0] = deadline;
        app.reconcile();
        assert.equal(app.dockVisible[0], false);
    }
    app.revealUntil[0] = 11000;
    app.reconcile();
    app.advance(9999);
    app.reconcile();
    assert.equal(app.dockVisible[0], true);
    app.advance(1);
    app.reconcile();
    assert.equal(app.dockVisible[0], false);
});

test('moving the host in both directions restores source chrome and hides destination chrome', () => {
    for (const [from, to] of [[0, 1], [1, 0]]) {
        const app = harness();
        app.carPlay(from);
        app.reconcile();
        app.top[from] = 'com.qinggan.launcher|HomeActivity';
        app.carPlay(to);
        app.reconcile();
        assert.equal(app.dockVisible[from], true);
        assert.equal(app.statusVisible[from], true);
        assert.equal(app.saved[from], false);
        assert.equal(app.dockVisible[to], false);
        assert.equal(app.statusVisible[to], false);
        assert.equal(app.saved[to], true);
        assert.equal(app.timers.length, 1);
    }
});

test('another Activity in the same package restores chrome even if it is user fullscreen', () => {
    const app = harness();
    app.carPlay(0);
    app.reconcile();
    app.context.cache.fullscreen[nativePackage] = true;
    app.carPlay(0, nativePackage + '.AdvanceActivity');
    app.reconcile();
    assert.deepEqual(app.dockVisible, [true, true]);
    assert.deepEqual(app.statusVisible, [true, true]);
    assert.deepEqual(app.saved, [false, false]);
    app.context.cache.fullscreen[nativePackage] = false;
    app.tick();
    assert.equal(app.timers.length, 0, 'No polling after both claims are released');
});

test('process death without a TOP event is recovered without trusting the cached Activity', () => {
    for (const unavailable of ['', new Error('Task service unavailable')]) {
        const app = harness();
        app.carPlay(1);
        app.reconcile();
        app.top[1] = unavailable;
        app.tick();
        assert.deepEqual(app.dockVisible, [true, true]);
        assert.deepEqual(app.statusVisible, [true, true]);
        assert.deepEqual(app.saved, [false, false]);
        assert.equal(app.timers.length, 0);
    }
});

test('persisted ownership restores both panels after launcher restarts', () => {
    const app = harness([true, true]);
    app.dockVisible.fill(false);
    app.statusVisible.fill(false);
    app.reconcile();
    assert.deepEqual(app.dockVisible, [true, true]);
    assert.deepEqual(app.statusVisible, [true, true]);
    assert.deepEqual(app.saved, [false, false]);
});

test('a missed OEM hide or SystemUI restart is corrected while CarPlay stays on top', () => {
    const app = harness();
    app.carPlay(0);
    app.reconcile();
    app.dockVisible[0] = true;
    app.statusVisible[0] = true;
    app.tick();
    assert.deepEqual(app.dockVisible, [false, true]);
    assert.deepEqual(app.statusVisible, [false, true]);
    assert.equal(app.timers.length, 1);
});

test('failed status restoration keeps ownership until a successful retry', () => {
    const app = harness();
    app.carPlay(0);
    app.reconcile();
    app.top[0] = 'com.qinggan.launcher|HomeActivity';
    app.failStatus[0] = true;
    app.tick();
    assert.equal(app.dockVisible[0], true);
    assert.equal(app.statusVisible[0], false);
    assert.equal(app.saved[0], true);
    app.failStatus[0] = false;
    app.tick();
    assert.equal(app.statusVisible[0], true);
    assert.equal(app.saved[0], false);
    assert.equal(app.timers.length, 0);
});

test('a source dock failure does not block destination hiding or a later restore', () => {
    const app = harness();
    app.carPlay(0);
    app.reconcile();
    app.top[0] = 'com.qinggan.launcher|HomeActivity';
    app.carPlay(1);
    app.failDock[0] = true;
    app.reconcile();
    assert.equal(app.saved[0], true);
    assert.equal(app.statusVisible[0], true);
    assert.equal(app.dockVisible[1], false);
    assert.equal(app.statusVisible[1], false);
    assert.equal(app.context.restoringCarPlayDock[0], false);
    app.failDock[0] = false;
    app.tick();
    assert.equal(app.dockVisible[0], true);
    assert.equal(app.saved[0], false);
});
