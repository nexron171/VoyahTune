"use strict";

// Resolve capabilities in the target process: firmware families may mix OEM packages.
var VoyahAdapters = (function () {
    function optionalClass(name) {
        try { return Java.use(name); } catch (_) { return null; }
    }

    function method(type, name, signature) {
        if (type === null || !type[name]) return null;
        try { return type[name].overload.apply(type[name], signature); }
        catch (_) { return null; }
    }

    function requireMethods(name, requirements) {
        var resolved = {};
        requirements.forEach(function (entry) {
            var type = optionalClass(entry[1]);
            if (type === null) throw new Error(name + ": missing class " + entry[1]);
            var member = method(type, entry[2], entry[3]);
            if (member === null) throw new Error(name + ": missing method " + entry[2]);
            resolved[entry[0]] = member;
        });
        return resolved;
    }

    function anyModel(receiver, names) {
        return names.some(function (name) {
            if (receiver === null || !receiver[name]) return false;
            try { return !!receiver[name](); } catch (_) { return false; }
        });
    }

    function launcher() {
        var split = "com.qinggan.launcher.navigation.NavigationBarMain";
        var shared = "com.qinggan.mainlauncher.navigation.NavigationBar";
        var selected = null;
        [
            { id: "split-navigation", navigation: split, passenger: "com.qinggan.launcher.navigation.NavigationBarSecond" },
            { id: "shared-navigation", navigation: shared, passenger: shared }
        ].some(function (adapter) {
            var type = optionalClass(adapter.navigation);
            if (!method(type, "onClick", ["android.view.View"]) || !method(type, "updateTheme", [])
                || !method(type, "updateSelectedApp", ["java.lang.String", "java.lang.String"])) return false;
            if (adapter.id === "shared-navigation") {
                var model = optionalClass("com.qinggan.app.launcher.LauncherModel");
                if (!method(type, "initScreenUpViews", []) || !method(type, "doScreenLift", ["int"])
                    || !method(type, "dismiss", [])
                    || !method(optionalClass("com.qinggan.secondlauncher.activity.SecondMainActivity"), "onResume", [])) return false;
                var compatible = [
                    ["handleUpdateMainNavigationBar", ["java.lang.String", "java.lang.String", "boolean"]],
                    ["handleUpdateSecondNavigationBar", ["java.lang.String", "java.lang.String", "boolean"]],
                    ["onReceive", ["android.content.Context", "android.content.Intent"]],
                    ["onMoveStart", ["java.lang.String", "java.lang.String", "int", "int", "int", "int"]],
                    ["onMoveStop", ["java.lang.String", "java.lang.String", "int", "int", "int", "int"]]
                ].every(function (entry) { return method(model, entry[0], entry[1]) !== null; });
                if (!compatible) return false;
            }
            adapter.type = type;
            selected = adapter;
            return true;
        });
        if (selected === null) throw new Error("launcher: unsupported OEM navigation ABI");
        return selected;
    }

    function acc() {
        var componentName = "com.qinggan.canbus.service.protocol.dongfeng_h97c.DongfengH97CCanBusComponentImpl";
        var baseName = "com.qinggan.canbus.service.BaseCanBusComponent";
        var taskName = componentName + "$ModeSettingTask";
        var methods = requireMethods("acc", [
            ["parser", componentName, "onBCM_PEPSChangeData", ["[I", "boolean"]],
            ["setter", componentName, "setVehicleAndAirConditionBundleState", ["android.os.Bundle", "android.os.Bundle"]],
            ["single", componentName, "setVehicleState", ["com.qinggan.canbus.VehicleState", "int"]],
            ["feedback", baseName, "onVehicleStateChanged", ["com.qinggan.canbus.VehicleState", "int"]],
            ["taskInit", taskName, "$init", [componentName, baseName]],
            ["taskSend", taskName, "setVehicleMode", ["java.util.HashMap"]]
        ]);
        return {
            id: "h97c-mode-task", componentName: componentName,
            component: Java.use(componentName), base: Java.use(baseName), task: Java.use(taskName), methods: methods,
            supportsRestore: function (component) { return anyModel(component, ["isH97X", "isH97Y", "isH97C"]); }
        };
    }

    function driveReset() {
        var memoryName = "com.qinggan.app.vehiclesetting.accountdata.VehicleMemoryManager";
        var fragmentName = "com.qinggan.app.vehiclesetting.fragments.drivepreference.DrivePreferenceFragment";
        var methods = requireMethods("drive-reset", [
            ["resetSettings", memoryName, "resetSettings", []],
            ["resetOverseaDriveMode", memoryName, "resetOverseaDriveMode", []],
            ["setDriveMode", fragmentName, "setDriveMode", ["int"]],
            ["setPowerMode", fragmentName, "setPowerMode", ["int"]],
            ["hint", fragmentName, "onHintSwitchClick", ["com.qinggan.canbus.VehicleState", "int"]]
        ]);
        return {
            id: "vehicle-memory-bundle", memory: Java.use(memoryName), fragment: Java.use(fragmentName), methods: methods,
            supportsReset: function (platform) {
                return anyModel(platform, ["is97X", "is97C"]) || anyModel(optionalClass("com.qinggan.app.vehiclesetting.utils.Utils"), ["is97Y"]);
            }
        };
    }

    function multidisplay() {
        var type = optionalClass("com.qinggan.systemservice.multidisplay.MultiDisplayImpl");
        if (type === null || !type.isWhiteListApp) throw new Error("multidisplay: unsupported OEM ABI");
        return {
            id: "qinggan-multidisplay", type: type,
            swap: method(type, "swapActivity", []),
            move: method(type, "moveActivity", ["java.lang.String", "java.lang.String", "boolean"])
        };
    }

    function clusterCard() {
        var service = optionalClass("com.qinggan.cluster.service.InstrumentClusterService");
        var fragment = optionalClass("com.qinggan.cluster.display.fragments.ClusterNaviFragment");
        if (service === null || method(fragment, "createVirtualDisplay", ["android.view.Surface", "int", "int"]) === null) return null;
        return {
            id: "navi-full", service: service, fragment: fragment,
            protocol: optionalClass("com.qinggan.cluster.service.protocol.voyah.VoyahInstrumentCluster")
        };
    }

    function windowTraversal() {
        var names = ["com.android.server.wm.WindowManagerInternal", "android.view.WindowManagerInternal"];
        var services = optionalClass("com.android.server.LocalServices");
        if (services === null) return null;
        for (var index = 0; index < names.length; index++) {
            var type = optionalClass(names[index]);
            var request = method(type, "requestTraversalFromDisplayManager", []);
            if (request === null) continue;
            var service = services.getService(type.class);
            if (service !== null) return { id: names[index], service: Java.retain(Java.cast(service, type)), request: request };
        }
        return null;
    }

    function keyboard(mode) {
        var requirements = [
            ["save", "com.qinggan.app.qgime.InputModeSwitcher", "saveInputMode", ["int"]],
            ["load", "com.qinggan.app.qgime.XmlKeyboardLoader", "loadKeyboard", ["int", "int", "int"]]
        ];
        if (mode === "ru") requirements = requirements.concat([
            ["input", "com.qinggan.app.qgime.QingganIME", "responseSoftKeyEvent", ["com.qinggan.app.qgime.SoftKey"]],
            ["toggle", "com.qinggan.app.qgime.SoftKeyToggle", "getKeyLabel", []],
            ["mode", "com.qinggan.app.qgime.InputModeSwitcher", "switchModeForUserKey", ["int", "boolean"]],
            ["pool", "com.qinggan.app.qgime.SkbPool", "getSoftKeyboard", ["int", "int", "int", "int", "android.content.Context"]],
            ["toast", "com.pateo.material.dialog.QGToast", "makeText", ["android.content.Context", "java.lang.CharSequence", "int"]],
            ["update", "com.qinggan.app.qgime.SkbContainer", "updateInputMode", []],
            ["process", "com.qinggan.app.qgime.EnglishInputProcessor", "processKey", ["android.view.inputmethod.InputConnection", "android.view.KeyEvent", "boolean", "boolean"]],
            ["qwerty", "com.qinggan.app.qgime.SoftKeyboard", "switchQwertyMode", ["int", "boolean"]],
            ["states", "com.qinggan.app.qgime.SoftKeyboard", "enableToggleStates", ["com.qinggan.app.qgime.InputModeSwitcher$ToggleStates"]]
        ]);
        return { id: "qgime-android11-" + mode, methods: requireMethods("keyboard", requirements) };
    }

    return {
        optionalClass: optionalClass, method: method, requireMethods: requireMethods,
        launcher: launcher, acc: acc, driveReset: driveReset, multidisplay: multidisplay,
        clusterCard: clusterCard, windowTraversal: windowTraversal, keyboard: keyboard
    };
})();
