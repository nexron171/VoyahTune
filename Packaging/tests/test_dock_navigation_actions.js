const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../od/inject/launcherdock.js'), 'utf8');
function production(name) {
    const match = new RegExp('^([ ]*)function ' + name + '\\(', 'm').exec(source);
    assert(match, name);
    const closing = '\n' + match[1] + '}';
    return source.slice(match.index, source.indexOf(closing, match.index) + closing.length);
}
const settings = {};
const events = [];
const home = {getId: () => 1};
const apps = {getId: () => 2};
const context = {
    cfg: key => settings[key], navigationButtonKeys: {'1': 'dockHomeShortVoyahTune', '2': 'dockAllAppsShortVoyahTune'},
    invokingStockNavigation: false, openVoyahTune: view => events.push('voyah:' + view.getId()),
    managedScreenId: owner => owner.screen, mainFallbackScreen: 0,
    dockViews: () => ({home, allApps: apps, slot1: null, slot2: null}),
    mainOnClick: {call: (owner, view) => events.push('stock:' + view.getId())},
    TAG: 'test', Log: {e: (_, error) => {throw new Error(error);}},
};
vm.createContext(context);
vm.runInContext(['navigationOpensVoyahTune', 'handleNavigationLongClick'].map(production).join('\n'), context);
const start = source.indexOf('mainOnClick.implementation = function (view) {');
const end = source.indexOf('\n        };', start);
vm.runInContext(source.slice(start, end + '\n        };'.length), context);
for (const [view, key] of [[home, 'dockHomeShortVoyahTune'], [apps, 'dockAllAppsShortVoyahTune']]) {
    view.performClick = () => context.mainOnClick.implementation.call({screen: 0}, view);
    for (const value of [undefined, '0', '1']) {
        settings[key] = value;
        events.length = 0;
        view.performClick();
        assert.equal(context.handleNavigationLongClick(view), true);
        assert.deepEqual(events, value === '1'
            ? ['voyah:' + view.getId(), 'stock:' + view.getId()]
            : ['stock:' + view.getId(), 'voyah:' + view.getId()]);
        assert.equal(context.invokingStockNavigation, false);
    }
    events.length = 0;
    context.mainOnClick.implementation.call({screen: 1}, view);
    assert.deepEqual(events, ['stock:' + view.getId()]);
}
settings.dockHomeShortVoyahTune = '1';
home.performClick = () => {throw new Error('OEM failure');};
assert.throws(() => context.handleNavigationLongClick(home), /OEM failure/);
assert.equal(context.invokingStockNavigation, false);
console.log('PASS: independent Home/All Apps gestures, stock bypass, passenger isolation and error recovery');
