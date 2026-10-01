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
    assert.equal(names.length, 9);
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
