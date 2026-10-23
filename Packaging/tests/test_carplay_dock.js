const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const restorePackage = 'ru.big.town.restoremode';
const carPlayTarget = restorePackage + '/com.shilapi.xcertplay.DiPlayActivity';
const carPlayLauncher = restorePackage + '.carplay.CarPlayLauncherActivity';

function production(source, name) {
    const match = new RegExp('^([ ]*)function ' + name + '\\(', 'm').exec(source);
    assert(match, name);
    const closing = '\n' + match[1] + '}';
    return source.slice(match.index, source.indexOf(closing, match.index) + closing.length);
}

function drawable(name) {
    return {
        name,
        getIntrinsicWidth: () => 80,
        getIntrinsicHeight: () => 80,
        setBounds() {},
        draw(canvas) {
            canvas.bitmap.name = name;
        },
    };
}

for (const profile of ['pi', 'od']) {
    const source = fs.readFileSync(path.join(__dirname, '..', profile, 'inject/launcherdock.js'), 'utf8');
    let activityEnabled = true;
    let activityExported = true;
    let activityPresent = true;
    let dockResourcePresent = true;
    const broadcasts = [];
    const packageManager = {
        getLaunchIntentForPackage: packageName => packageName === restorePackage ? {} : null,
        getActivityInfo(component) {
            assert.equal(component.packageName, restorePackage);
            assert.equal(component.activityName, carPlayLauncher);
            if (!activityPresent)
                throw new Error('activity missing');
            return {enabled: {value: activityEnabled}, exported: {value: activityExported}};
        },
        getResourcesForApplication(packageName) {
            assert.equal(packageName, restorePackage);
            return {
                getIdentifier: name => name === 'carplay_dock_logo' && dockResourcePresent ? 1 : 0,
                getDrawable: () => ({name: 'carplay_dock_logo', width: 116, height: 90}),
            };
        },
        getActivityIcon(component) {
            assert.equal(component.packageName, restorePackage);
            assert.equal(component.activityName, carPlayLauncher);
            return drawable('carplay_launcher_icon');
        },
        getApplicationInfo: packageName => ({packageName}),
        getApplicationIcon(packageOrInfo) {
            const packageName = typeof packageOrInfo === 'string' ? packageOrInfo : packageOrInfo.packageName;
            assert.equal(packageName, restorePackage);
            return drawable('dashboard_icon');
        },
    };
    const context = {
        CARPLAY_TARGET: carPlayTarget,
        CARPLAY_LAUNCHER: carPlayLauncher,
        RESTORE_PKG: restorePackage,
        OUR_PKG: 'ru.big.town.anative',
        TAG: 'test',
        Log: {
            i() {},
            e: (_, error) => {
                throw new Error(error);
            }
        },
        cleanJavaString: value => value || '',
        retainDrawable: value => value,
        ctx: () => ({
            getPackageManager: () => packageManager,
            getResources: () => ({}),
            sendBroadcast: intent => broadcasts.push(intent),
        }),
        Java: {
            use(name) {
                assert.equal(name, 'android.content.ComponentName');
                return {$new: (packageName, activityName) => ({packageName, activityName})};
            },
        },
        BitmapConfig: {ARGB_8888: {value: 1}},
        Bitmap: {
            createBitmap: (width, height) => ({width, height}),
            createScaledBitmap: (bitmap, width, height) => ({...bitmap, width, height}),
        },
        Canvas: {
            $new: bitmap => ({bitmap, getWidth: () => bitmap.width, getHeight: () => bitmap.height}),
        },
        BitmapDrawable: {
            $new: (_, bitmap) => ({...bitmap, setGravity() {}, setBounds() {}}),
        },
        Intent: {
            $new: action => ({
                action,
                extras: {},
                setClassName(packageName, activityName) {
                    this.component = {packageName, activityName};
                },
                putExtra: {
                    overload: () => function(key, value) {
                        this.extras[key] = value;
                    }
                },
                addFlags() {},
            }),
        },
    };
    vm.createContext(context);
    const functions = ['isInstalled', 'getAppDrawable', 'ourInsetActivity', 'launchFreeform'];
    if (profile === 'pi')
        functions.push('sendNative');
    vm.runInContext(functions.map(name => production(source, name)).join('\n'), context);

    assert.equal(context.isInstalled(carPlayTarget), true);
    assert.equal(context.isInstalled(restorePackage), true);
    assert.equal(context.isInstalled('missing.app'), false);
    assert.equal(context.isInstalled('none'), false);
    activityEnabled = false;
    assert.equal(context.isInstalled(carPlayTarget), false);
    activityEnabled = true;
    activityExported = false;
    assert.equal(context.isInstalled(carPlayTarget), false);
    activityExported = true;
    activityPresent = false;
    assert.equal(context.isInstalled(carPlayTarget), false);
    activityPresent = true;

    const dockIcon = context.getAppDrawable(carPlayTarget);
    assert.equal(dockIcon.name, 'carplay_dock_logo');
    assert.equal(dockIcon.width, 116);
    assert.equal(dockIcon.height, 90);
    dockResourcePresent = false;
    const fallback = context.getAppDrawable(carPlayTarget);
    assert.equal(fallback.name, 'carplay_launcher_icon');
    assert.equal(fallback.width, 50);
    assert.equal(fallback.height, 50);
    assert.equal(context.getAppDrawable(restorePackage).name, 'dashboard_icon');

    for (const activity
             of ['com.shilapi.xcertplay.DiPlayActivity',
                 'com.shilapi.xcertplay.CarPlayHostActivity',
                 carPlayLauncher,
                 'ru.big.town.anative.SplitHostActivity',
    ]) {
        assert.equal(context.ourInsetActivity(activity), true);
    }
    assert.equal(context.ourInsetActivity('com.shilapi.xcertplay.OtherActivity'), false);

    context.launchFreeform(carPlayTarget, 0);
    assert.equal(broadcasts.length, 1);
    assert.equal(broadcasts[0].action, 'ru.big.town.anative.OPEN_FREEFORM');
    assert.equal(broadcasts[0].component.packageName, 'ru.big.town.anative');
    assert.equal(broadcasts[0].component.activityName, 'ru.big.town.anative.SetModesReceiverDynamic');
    assert.equal(broadcasts[0].extras.pkg, carPlayTarget);
    assert.equal(broadcasts[0].extras.display, 0);
    console.log('PASS: ' + profile + ' CarPlay target, icon fallback, dock visibility and Native launch routing');
}
