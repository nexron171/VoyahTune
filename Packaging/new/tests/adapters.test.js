"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

function method(signatures) {
    return {
        overload(...signature) {
            assert.ok(
                signatures.some(
                    (expected) =>
                        JSON.stringify(expected) === JSON.stringify(signature),
                ),
            );
            return { signature };
        },
    };
}

function registry(classes) {
    const context = vm.createContext({
        Java: {
            use(name) {
                if (!(name in classes)) throw new Error("missing " + name);
                return classes[name];
            },
            retain: (value) => value,
            cast: (value) => value,
        },
    });
    vm.runInContext(
        fs.readFileSync(path.join(__dirname, "../runtime/adapters.js"), "utf8"),
        context,
    );
    return context.VoyahAdapters;
}

const navigation = {
    onClick: method([["android.view.View"]]),
    updateTheme: method([[]]),
    updateSelectedApp: method([["java.lang.String", "java.lang.String"]]),
    initScreenUpViews: method([[]]),
    doScreenLift: method([["int"]]),
    dismiss: method([[]]),
};
const sharedClasses = {
    "com.qinggan.mainlauncher.navigation.NavigationBar": navigation,
    "com.qinggan.secondlauncher.activity.SecondMainActivity": {
        onResume: method([[]]),
    },
    "com.qinggan.app.launcher.LauncherModel": {
        handleUpdateMainNavigationBar: method([
            ["java.lang.String", "java.lang.String", "boolean"],
        ]),
        handleUpdateSecondNavigationBar: method([
            ["java.lang.String", "java.lang.String", "boolean"],
        ]),
        onReceive: method([
            ["android.content.Context", "android.content.Intent"],
        ]),
        onMoveStart: method([
            [
                "java.lang.String",
                "java.lang.String",
                "int",
                "int",
                "int",
                "int",
            ],
        ]),
        onMoveStop: method([
            [
                "java.lang.String",
                "java.lang.String",
                "int",
                "int",
                "int",
                "int",
            ],
        ]),
    },
};
assert.equal(registry(sharedClasses).launcher().id, "shared-navigation");
assert.throws(
    () =>
        registry({
            "com.qinggan.mainlauncher.navigation.NavigationBar": navigation,
        }).launcher(),
    /unsupported/,
);
assert.equal(
    registry({
        "com.qinggan.launcher.navigation.NavigationBarMain": navigation,
    }).launcher().id,
    "split-navigation",
);
assert.throws(() => registry({}).launcher(), /unsupported/);
assert.throws(
    () =>
        registry({
            "com.qinggan.launcher.navigation.NavigationBarMain": {
                onClick: method([["int"]]),
            },
        }).launcher(),
    /unsupported/,
);
assert.equal(registry({}).clusterCard(), null);
assert.throws(() => registry({}).acc(), /missing class/);

const selected = {};
const windows = registry({
    "com.android.server.LocalServices": { getService: () => selected },
    "android.view.WindowManagerInternal": {
        class: {},
        requestTraversalFromDisplayManager: method([[]]),
    },
}).windowTraversal();
assert.equal(windows.id, "android.view.WindowManagerInternal");
assert.equal(windows.service, selected);

const memory =
    "com.qinggan.app.vehiclesetting.accountdata.VehicleMemoryManager";
const fragment =
    "com.qinggan.app.vehiclesetting.fragments.drivepreference.DrivePreferenceFragment";
const drive = registry({
    [memory]: {
        resetSettings: method([[]]),
        resetOverseaDriveMode: method([[]]),
    },
    [fragment]: {
        setDriveMode: method([["int"]]),
        setPowerMode: method([["int"]]),
        onHintSwitchClick: method([["com.qinggan.canbus.VehicleState", "int"]]),
    },
    "com.qinggan.app.vehiclesetting.utils.Utils": { is97Y: () => false },
}).driveReset();
assert.equal(drive.supportsReset({ is97X: () => true }), true);
assert.equal(drive.supportsReset({ is97C: () => true }), false);
assert.equal(drive.supportsUserSelection({ is97C: () => true }), true);
assert.equal(
    drive.supportsUserSelection({ is97X: () => false, is97C: () => false }),
    false,
);
assert.equal(
    drive.supportsReset({ is97X: () => false, is97C: () => false }),
    false,
);
console.log(
    "PASS: component-local firmware selection, ABI rejection and vendor class fallback",
);
