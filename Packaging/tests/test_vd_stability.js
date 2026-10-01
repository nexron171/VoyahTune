const assert = require('node:assert/strict');
const { fixture } = require('./vd_fixture');
function test(name, fn) { fn(); console.log('OK ' + name); }
test('preparation waits without mutating methods or registering receivers', () => {
    const f = fixture({ readyAt: 1500 });
    assert.match(f.status(), /\|preparing\|services$/);
    assert.equal(f.events.filter(e => e[0] === 'replacement').length, 0);
    assert.equal(f.registered.size, 0);
    f.advance(1500);
    assert.match(f.status(), /\|preparing\|geometry.pending$/);
    f.advance(1000);
    assert.match(f.status(), /^v2:test:104:1\|3.22.0-v1\|active\|geometry$/);
});
test('missing server class or UID ends bounded preparation', () => {
    for (const options of [{ missingClass: 'com.android.server.wm.Task' }, { uid: -1 }]) {
        const f = fixture(options); f.advance(21000);
        assert.match(f.status(), /\|failed\|clean.prepare/);
        assert.equal(f.registered.size, 0);
        assert.equal(f.events.filter(e => e[0] === 'replacement').length, 0);
        assert.equal(f.timers.size, 0);
    }
});
test('ABI mismatch fails before changing any method', () => {
    const f = fixture({ missingMethod: 'com.android.server.wm.ActivityRecord.ensureActivityConfiguration' });
    assert.match(f.status(), /\|failed\|clean.prepare/);
    assert.equal(f.events.filter(e => e[0] === 'replacement').length, 0);
});
test('each replacement installation failure rolls back all prior methods', () => {
    const baseline = fixture(); baseline.advance(1000);
    const names = [...new Set(baseline.events.filter(e => e[0] === 'replacement' && e[2]).map(e => e[1]))];
    assert.equal(names.length, 11);
    for (const name of names) {
        const f = fixture({ failInstall: name }); f.advance(1500);
        assert.match(f.status(), /\|failed\|clean\./, name);
        assert.equal(f.registered.size, 0, name);
        for (const m of f.methods.values()) assert.equal(m.implementation, null, name);
    }
});
test('receiver registration failure rolls back even a partially registered receiver', () => {
    for (const name of ['WinReloadReceiver', 'ScreenLiftReceiver', 'ScreenStateReceiver']) {
        const f = fixture({ failReceiver: 'ru.big.town.vd.' + name }); f.advance(2000);
        assert.match(f.status(), /\|failed\|clean.install/);
        assert.equal(f.registered.size, 0);
        for (const m of f.methods.values()) assert.equal(m.implementation, null);
    }
});
test('rollback failure cannot report ready', () => {
    const f = fixture({ failReceiver: 'ru.big.town.vd.WinReloadReceiver',
        failRollback: 'com.android.server.wm.ActivityTaskManagerService.removeTask' });
    assert.match(f.status(), /\|failed\|partial.install/);
});
test('screen off and disabled geometry retain ready core permissions', () => {
    for (const options of [{ screenOn: false }, { settings: { voyahtune_freeform: '0' } }]) {
        const f = fixture(options);
        assert.match(f.status(), /\|active\|geometry\.(sleeping|disabled)$/);
        assert.ok(f.methods.get('com.android.server.wm.ActivityTaskManagerService.removeTask').implementation);
    }
});
test('complete DPI snapshot serves first launch with zero hot-path Settings reads', () => {
    const f = fixture({ settings: { voyahtune_dpi_packages: 'org.first,org.second',
        'voyahtune_dpi_org.first': '180', 'voyahtune_dpi_org.second': '240' } });
    f.advance(1000); f.events.length = 0;
    const task = { getRequestedOverrideConfiguration: () => ({ densityDpi: { value: 0 } }),
        onRequestedOverrideConfigurationChanged: cfg => assert.equal(cfg.densityDpi.value, 240) };
    const record = { getDisplayId: () => 1, packageName: { value: 'org.second' }, task };
    f.methods.get('com.android.server.wm.ActivityRecord.ensureActivityConfiguration').implementation.call(record, 0, false, false);
    assert.equal(f.api.ffDpiFor('org.first'), 180);
    assert.equal(f.api.ffDpiFor('org.unconfigured'), 0);
    assert.equal(f.events.filter(e => e[0] === 'setting').length, 0);
});
test('failed refresh retains the entire previous snapshot', () => {
    const f = fixture({ settings: { voyahtune_win_left: '150', voyahtune_dpi_packages: 'org.app',
        'voyahtune_dpi_org.app': '240' } });
    const old = f.api.cfg;
    f.options.settings.voyahtune_win_left = '170';
    f.options.failSetting = 'voyahtune_dpi_org.app';
    assert.equal(f.api.refreshFreeformCfg(), false);
    assert.equal(f.api.cfg, old);
    assert.equal(f.api.cfg.left, 150);
    assert.equal(f.api.ffDpiFor('org.app'), 240);
    delete f.options.failSetting;
    assert.equal(f.api.refreshFreeformCfg(), true);
    assert.notEqual(f.api.cfg, old);
    assert.equal(f.api.cfg.left, 170);
});
test('DPI recursion is per task and guard clears after failure', () => {
    const f = fixture({ settings: { voyahtune_dpi_packages: 'org.app', 'voyahtune_dpi_org.app': '240' } });
    let count = 0, otherCount = 0;
    const other = { getRequestedOverrideConfiguration: () => ({ densityDpi: { value: 0 } }),
        onRequestedOverrideConfigurationChanged: () => ++otherCount };
    const task = { getRequestedOverrideConfiguration: () => ({ densityDpi: { value: 0 } }),
        onRequestedOverrideConfigurationChanged: () => {
            ++count;
            assert.equal(f.api.ffApplyTaskDpi(task, 'org.app'), false);
            assert.equal(f.api.ffApplyTaskDpi(other, 'org.app'), true);
            throw new Error('config rejected');
        } };
    assert.throws(() => f.api.ffApplyTaskDpi(task, 'org.app'), /config rejected/);
    assert.throws(() => f.api.ffApplyTaskDpi(task, 'org.app'), /config rejected/);
    assert.equal(count, 2); assert.equal(otherCount, 2);
    other.getRequestedOverrideConfiguration = () => ({ densityDpi: { value: 240 } });
    assert.equal(f.api.ffApplyTaskDpi(other, 'org.app'), false);
});
function screen(f, on) { f.receive('ScreenStateReceiver', 'android.intent.action.SCREEN_' + (on ? 'ON' : 'OFF')); }
test('duplicate ON and OFF do not repeat replacements or pending work', () => {
    const f = fixture(); f.advance(1000); f.events.length = 0;
    screen(f, true); screen(f, true); f.advance(1000);
    assert.equal(f.events.filter(e => e[0] === 'replacement').length, 0);
    assert.equal(f.api.state, 'active');
    screen(f, false); const count = f.events.filter(e => e[0] === 'replacement').length;
    screen(f, false); f.advance(6000);
    assert.equal(count, 2);
    assert.equal(f.events.filter(e => e[0] === 'replacement').length, 2);
    assert.equal(f.api.state, 'sleeping'); assert.equal(f.timers.size, 0);
});
test('OFF ON OFF cancels wake timer and stale callbacks', () => {
    const f = fixture(); f.advance(1000); screen(f, false); screen(f, true);
    const stale = [...f.timers.values()].map(t => t.fn);
    screen(f, false);
    assert.equal(f.timers.size, 0);
    for (const callback of stale) callback();
    f.advance(7000);
    assert.equal(f.api.state, 'sleeping');
    assert.equal(f.methods.get('com.android.server.wm.DisplayPolicy.layoutWindowLw').implementation, null);
});
test('reload burst loads once and replays once without changing active hooks', () => {
    const f = fixture(); f.advance(1000); f.events.length = 0;
    for (let n = 0; n < 10; ++n) f.receive('WinReloadReceiver', 'ru.big.town.anative.WIN_RELOAD');
    f.advance(200);
    assert.equal(f.events.filter(e => e[0] === 'setting' && e[1] === 'voyahtune_freeform').length, 1);
    assert.equal(f.events.filter(e => e[0] === 'traversal').length, 1);
    assert.equal(f.events.filter(e => e[0] === 'replacement').length, 0);
});
test('disable during initial wait cancels every pending timer', () => {
    const f = fixture();
    f.options.settings = { voyahtune_freeform: '0' };
    f.receive('WinReloadReceiver', 'ru.big.town.anative.WIN_RELOAD'); f.advance(50);
    assert.equal(f.api.state, 'disabled'); assert.equal(f.timers.size, 0);
    f.advance(6000);
    assert.equal(f.methods.get('com.android.server.wm.DisplayPolicy.layoutWindowLw').implementation, null);
});
test('partial detach is failed and cannot schedule a later attach', () => {
    const f = fixture(); f.advance(1000);
    f.options.failRollback = 'com.android.server.wm.DisplayPolicy.layoutWindowLw';
    screen(f, false);
    assert.match(f.status(), /\|failed\|partial.hot.detach$/);
    assert.equal(f.api.state, 'error'); assert.equal(f.timers.size, 0);
    f.advance(10000);
    assert.equal(f.registered.size, 0);
});
function fullscreenFixture() {
    const f = fixture({ settings: { voyahtune_fullscreen_apps: 'org.app' } }); f.advance(1000); return f;
}
function bounds(r) { return ['left','top','right','bottom'].map(k => r[k].value); }
test('invalid viewport, lift and DPI retain the last complete policy', () => {
    const f = fixture(); const old = f.api.cfg;
    for (const delta of [{ voyahtune_win_left: '-1' }, { voyahtune_win_right: '100' },
        { voyahtune_win_bottom: 'NaN' }, { voyahtune_win_top: '1.5' },
        { voyahtune_win_compact_bottom: '800' }, { voyahtune_win_right: '2147483648' },
        { voyahtune_freeform: '3' }, { voyahtune_dpi_packages: 'org.app', 'voyahtune_dpi_org.app': '99' }]) {
        f.options.settings = delta;
        assert.equal(f.api.refreshFreeformCfg(), false, JSON.stringify(delta)); assert.equal(f.api.cfg, old);
    }
    f.options.settings = { voyahtune_screen_lift_type: '0' }; f.options.liftType = 0;
    assert.equal(f.api.refreshFreeformCfg(), false); assert.equal(f.api.cfg, old);
});
test('incomplete frame leaves stock layout and requested sizes unchanged', () => {
    for (const missingFrame of ['mStableFrame','mParentFrame','mDisplayFrame','mContentFrame','mVisibleFrame','mDecorFrame']) {
        const f = fullscreenFixture(), w = f.window({ missingFrame }); f.events.length = 0;
        w.layout();
        assert.deepEqual(bounds(w.stable), [0,0,1920,720]);
        assert.equal(w.win.mRequestedWidth.value, 1780); assert.equal(w.attrs.width.value, 1780);
        assert.equal(f.api.windowCount, 0); assert.equal(f.events.filter(e => e[0] === 'compute').length, 0);
    }
});
test('each mutation failure and compute failure restores shared fields and stock frames', () => {
    // requested W/H, attrs W/H, stable, six WindowFrames, then compute.
    for (const options of [...Array.from({length:11}, (_,n) => ({failMutation:n+1})), {failCompute:true}]) {
        const f = fullscreenFixture(), w = f.window(options);
        w.layout();
        assert.deepEqual(bounds(w.stable), [0,0,1920,720], JSON.stringify(options));
        for (const frame of Object.values(w.frames)) assert.deepEqual(bounds(frame.value), [0,0,1920,720]);
        assert.equal(w.attrs.width.value, 1780); assert.equal(w.attrs.height.value, 675);
        assert.equal(w.win.mRequestedWidth.value, 1780); assert.equal(w.win.mRequestedHeight.value, 675);
        assert.equal(f.api.windowCount, 0);
    }
});
test('fullscreen Surface persists while shared frames and attributes recover', () => {
    const f = fullscreenFixture(), w = f.window(); f.events.length = 0;
    w.layout();
    assert.equal(w.win.mRequestedWidth.value, 1920); assert.equal(w.win.mRequestedHeight.value, 675);
    assert.equal(w.attrs.width.value, 1780); assert.deepEqual(bounds(w.stable), [0,0,1920,720]);
    assert.deepEqual(bounds(w.frames.mContentFrame.value), [0,45,1920,720]);
    assert.equal(f.events.filter(e => e[0] === 'compute').length, 1);
    assert.equal(f.events.filter(e => e[0] === 'setting').length, 0);
    assert.equal(f.api.windowCount, 1);
});
test('identical hashCodes cannot exchange originals; removal releases the record', () => {
    const f = fullscreenFixture(), a = f.window({width:1780}), b = f.window({width:1600});
    a.layout(); b.layout(); assert.equal(f.api.windowCount, 2);
    a.remove(); assert.equal(f.api.windowCount, 1); assert.equal(a.win.mRequestedWidth.value, 1780);
    f.options.settings.voyahtune_fullscreen_apps = '';
    f.receive('WinReloadReceiver', 'ru.big.town.anative.WIN_RELOAD'); f.advance(200);
    assert.equal(b.win.mRequestedWidth.value, 1600); assert.equal(f.api.windowCount, 0);
});
test('bounded cache preserves stock geometry for an untracked window', () => {
    const f = fullscreenFixture();
    for (let n=0; n<128; ++n) f.window().layout();
    assert.equal(f.api.windowCount, 128);
    const w = f.window(); w.layout();
    assert.equal(f.api.windowCount, 128); assert.equal(w.win.mRequestedWidth.value, 1780);
    assert.deepEqual(bounds(w.frames.mContentFrame.value), [0,0,1920,720]);
});
test('disable and agent failure restore original size before detaching', () => {
    for (const failure of [false, true]) {
        const f = fullscreenFixture(), w = f.window(); w.layout();
        if (failure) {
            f.options.failRollback = 'com.android.server.wm.DisplayPolicy.layoutWindowLw'; screen(f, false);
            assert.match(f.status(), /\|failed\|partial/);
        } else {
            f.options.settings.voyahtune_freeform = '0';
            f.receive('WinReloadReceiver', 'ru.big.town.anative.WIN_RELOAD'); f.advance(100);
            assert.equal(f.api.state, 'disabled');
        }
        assert.equal(w.win.mRequestedWidth.value, 1780); assert.equal(f.api.windowCount, 0);
    }
});
test('missing WM lock context cannot claim successful cleanup', () => {
    const f = fullscreenFixture(), w = f.window(); w.layout(); f.options.noWmCleanup = true;
    f.options.settings.voyahtune_freeform = '0';
    f.receive('WinReloadReceiver', 'ru.big.town.anative.WIN_RELOAD'); f.advance(100);
    assert.match(f.status(), /\|failed\|partial.window.restore$/);
});
test('app relayout replaces its original request; physical guards and compact viewport persist', () => {
    const f = fullscreenFixture(), w = f.window(); w.layout();
    w.win.mRequestedWidth.value = 1500; w.layout();
    f.options.settings.voyahtune_fullscreen_apps = ''; f.api.refreshFreeformCfg(); w.layout();
    assert.equal(w.win.mRequestedWidth.value, 1500); assert.equal(f.api.windowCount, 0);
    f.options.liftType = 1; f.api.refreshFreeformCfg(); w.layout();
    assert.deepEqual(bounds(w.frames.mContentFrame.value), [145,45,1920,560]);
    for (const options of [{mode:5}, {display:2}, {pkg:'com.qinggan.launcher'}, {type:2038}]) {
        const skip = f.window(options); skip.layout();
        assert.deepEqual(bounds(skip.frames.mContentFrame.value), [0,0,1920,720]);
    }
});
test('restoration failure attempts every other shared field and publishes partial failure', () => {
    const f = fullscreenFixture(), w = f.window({ failMutation:12 }); w.layout();
    assert.match(f.status(), /\|failed\|partial.window.fields.restore$/);
    assert.equal(w.attrs.height.value, 675); assert.deepEqual(bounds(w.stable), [0,0,1920,720]);
    assert.equal(w.win.mRequestedWidth.value, 1780); assert.equal(f.api.windowCount, 0);
    assert.equal(f.timers.size, 0);
});
