// Preserve international numbers and repair lost +7/+375 prefixes at the HFP dial boundary.
var phoneNumberBootstrapResolve;
var phoneNumberBootstrap = new Promise(function (resolve) {
    phoneNumberBootstrapResolve = resolve;
});

// The injector waits for Java replacements before eternalizing the agent.
if (typeof rpc !== "undefined") {
    rpc.exports = {
        init: function () {
            return phoneNumberBootstrap;
        }
    };
}

Java.perform(function () {
    "use strict";

    const installedHooks = [];
    const lostInternationalPrefix = /^(7\d{10}|375\d{9})([,;pPwW].*)?$/;

    function normalizeNumber(number) {
        if (number === null) return "";

        // Keep service codes and post-dial controls; remove only visual separators.
        const compact = String(number).replace(/[\s\-()]/g, "");
        return lostInternationalPrefix.test(compact) ? "+" + compact : compact;
    }

    function installHook(method, implementation) {
        const previous = method.implementation;
        method.implementation = implementation;
        installedHooks.push({ method: method, previous: previous });
    }

    function findMakeCall(method, argumentTypes) {
        try {
            return method.overload.apply(method, argumentTypes);
        } catch (error) {
            return null;
        }
    }

    try {
        const Util = Java.use("com.qinggan.bluetoothphone.util.Util");
        const amendNumber = Util.getAmendNumber.overload("java.lang.String");
        const HeadSet = Java.use("com.qinggan.bluetoothphone.logic.manager.HeadSetProfileManager");
        const makeCall = findMakeCall(HeadSet.makeCall, ["java.lang.String"]);
        const makeCallOnDevice = findMakeCall(HeadSet.makeCall, ["java.lang.String", "java.lang.String"]);

        // Resolve the complete supported ABI before changing any OEM method.
        if (makeCall === null && makeCallOnDevice === null) {
            throw new Error("No supported makeCall overload");
        }

        installHook(amendNumber, normalizeNumber);

        if (makeCall !== null) {
            installHook(makeCall, function (number) {
                return makeCall.call(this, normalizeNumber(number));
            });
        }

        if (makeCallOnDevice !== null) {
            installHook(makeCallOnDevice, function (number, deviceAddress) {
                return makeCallOnDevice.call(this, normalizeNumber(number), deviceAddress);
            });
        }

        console.log("[phone-numbers] hook ready v1");
    } catch (error) {
        for (let index = installedHooks.length - 1; index >= 0; index--) {
            try {
                const hook = installedHooks[index];
                hook.method.implementation = hook.previous;
            } catch (rollbackError) {
                console.log("[phone-numbers] rollback failed v1: " + rollbackError);
            }
        }

        console.log("[phone-numbers] hook failed v1: " + error);
    } finally {
        phoneNumberBootstrapResolve();
    }
});
