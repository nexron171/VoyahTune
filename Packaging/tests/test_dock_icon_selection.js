const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../od/inject/launcherdock.js'), 'utf8');
function production(name) {
    const match = new RegExp('^([ ]*)function ' + name + '\\(', 'm').exec(source);
    assert(match, name);
    const closing = '\n' + match[1] + '}';
    return source.slice(match.index, source.indexOf(closing, match.index) + closing.length);
}

const settings = { dock1Icon: 'navigation', dock2Icon: 'music' };
const resources = {
    getIdentifier: name => name,
    getDrawable: name => ({ name, width: 116, height: 90 }),
};
function view() {
    return {
        background: 'OEM',
        getBackground() { return this.background; },
        setBackground(drawable) { this.background = drawable; },
        invalidate() {},
    };
}
const first = view();
const second = view();
const packages = { 1: 'same.app', 2: 'same.app' };
const context = {
    RESTORE_PKG: 'ru.big.town.restoremode', TAG: 'test',
    cfg: key => settings[key],
    ctx: () => ({ getPackageManager: () => ({ getResourcesForApplication: () => resources }) }),
    retainDrawable: drawable => drawable,
    getAppDrawable: packageName => ({ name: 'default:' + packageName }),
    Log: { w() {}, e: (_, error) => { throw new Error(error); } },
    managedScreenId: instance => instance.screen,
    dockViews: () => ({ slot1Name: 'first', slot1: first, slot2Name: 'second', slot2: second }),
    dockPackage: (_, slot) => packages[slot], originalBg: {},
    getNavigationLongClick: () => null, getSlotLongClick: () => null,
};
vm.createContext(context);
vm.runInContext(['getDockDrawable', 'updateIcons'].map(production).join('\n'), context);

context.updateIcons({ screen: 0 }, 0, true);
assert.equal(first.background.name, 'dock_navigation_icon');
assert.equal(second.background.name, 'dock_music_icon');
assert.equal(first.background.width, 116);
assert.equal(first.background.height, 90);

settings.dock1Icon = 'default';
context.updateIcons({ screen: 0 }, 0, true);
assert.equal(first.background.name, 'default:same.app');
assert.equal(second.background.name, 'dock_music_icon');

settings.dock1Icon = 'navigation';
context.updateIcons({ screen: 1 }, 1, true);
assert.equal(first.background.name, 'default:same.app');

for (const style of [undefined, 'invalid', 'default']) {
    settings.dock1Icon = style;
    assert.equal(context.getDockDrawable('app', 1).name, 'default:app');
}
settings.dock1Icon = 'music';
resources.getIdentifier = () => 0;
assert.equal(context.getDockDrawable('app', 1).name, 'default:app');
resources.getIdentifier = () => { throw new Error('old APK'); };
assert.equal(context.getDockDrawable('app', 1).name, 'default:app');

packages[1] = 'none';
context.updateIcons({ screen: 0 }, 0, true);
assert.equal(first.background, 'OEM');
console.log('PASS: independent dock icons, live reset, OEM geometry, passenger isolation and missing-resource fallback');
