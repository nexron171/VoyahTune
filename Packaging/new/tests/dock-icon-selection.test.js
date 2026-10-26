const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function production(source, name) {
    const match = new RegExp('^([ ]*)function ' + name + '\\(', 'm').exec(source);
    assert(match, name);
    const closing = '\n' + match[1] + '}';
    return source.slice(match.index, source.indexOf(closing, match.index) + closing.length);
}

function view(id) {
    return {
        background: 'OEM',
        getId() {
            return id;
        },
        getBackground() {
            return this.background;
        },
        setBackground(drawable) {
            this.background = drawable;
        },
        invalidate() {},
        setLongClickable() {},
        setOnLongClickListener() {},
    };
}

for (const profile of ['shared-navigation', 'split-navigation']) {
    const source = fs.readFileSync(path.join(__dirname, '../adapters/launcher', profile + '.js'), 'utf8');
    const settings = {dock1Icon: 'navigation', dock2Icon: 'music'};
    const resources = {
        getIdentifier: name => name,
        getDrawable: name => ({name, width: 116, height: 90}),
    };
    const first = view(1);
    const second = view(2);
    const packages = {1: 'same.app', 2: 'same.app'};
    const context = {
        RESTORE_PKG: 'ru.big.town.restoremode',
        TAG: 'test',
        cfg: key => settings[key],
        ctx: () => ({getPackageManager: () => ({getResourcesForApplication: () => resources})}),
        Java: {retain: drawable => drawable},
        retainDrawable: drawable => drawable,
        getAppDrawable: packageName => ({name: 'default:' + packageName}),
        Log: {
            w() {},
            e: (_, error) => {
                throw new Error(error);
            }
        },
        managedScreenId: instance => instance.screen,
        screenOf: instance => instance.screen,
        dockViews: () => ({slot1Name: 'first', slot1: first, slot2Name: 'second', slot2: second}),
        dockPackage: profile === 'shared-navigation' ? slot => packages[slot] : (_, slot) => packages[slot],
        originalBg: {},
        slotByViewId: {},
        slotLC: {},
        getNavigationLongClick: () => null,
        getSlotLongClick: () => null,
    };
    vm.createContext(context);
    vm.runInContext(['getDockDrawable', 'updateIcons'].map(name => production(source, name)).join('\n'), context);

    function repaint(screen) {
        if (profile === 'shared-navigation') {
            context.updateIcons({screen}, true);
        } else {
            context.updateIcons({screen}, screen, true);
        }
    }

    repaint(0);
    assert.equal(first.background.name, 'dock_navigation_icon');
    assert.equal(second.background.name, 'dock_music_icon');
    assert.equal(first.background.width, 116);
    assert.equal(first.background.height, 90);

    settings.dock1Icon = 'default';
    repaint(0);
    assert.equal(first.background.name, 'default:same.app');
    assert.equal(second.background.name, 'dock_music_icon');

    settings.dock1Icon = 'navigation';
    repaint(1);
    assert.equal(first.background.name, 'default:same.app');
    assert.equal(second.background.name, 'dock_music_icon');

    // OEM theme updates replace backgrounds before the hook reapplies the slot styles.
    first.background = 'OEM new theme';
    second.background = 'OEM new theme';
    repaint(0);
    assert.equal(first.background.name, 'dock_navigation_icon');
    assert.equal(second.background.name, 'dock_music_icon');

    for (const style of [undefined, 'invalid', 'default']) {
        settings.dock1Icon = style;
        assert.equal(context.getDockDrawable('app', 1).name, 'default:app');
    }
    settings.dock1Icon = 'music';
    resources.getIdentifier = () => 0;
    assert.equal(context.getDockDrawable('app', 1).name, 'default:app');
    resources.getIdentifier = () => {
        throw new Error('old APK');
    };
    assert.equal(context.getDockDrawable('app', 1).name, 'default:app');

    packages[1] = 'none';
    packages[2] = 'none';
    repaint(0);
    assert.equal(first.background, 'OEM');
    assert.equal(second.background, 'OEM');
    console.log(
        'PASS: ' + profile + ' independent dock icons, reset, theme, geometry, passenger isolation and fallback');
}
