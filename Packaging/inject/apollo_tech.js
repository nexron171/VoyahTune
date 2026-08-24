// Apollo/ADAS entitlement hook for com.qinggan.app.vehiclesetting.
//
// This intentionally mirrors voboost-script/agents/adas-activation-mod.js:
//   * doQuerySubscribeInfo() reports an active, non-expired subscription;
//   * doQueryNOALearnInfo() reports completed NOA learning.
//
// It does not subscribe to CAN callbacks, send CAN transactions, poll Settings, or invoke the
// old manager-driven activation path. The stock VehicleSetting application keeps
// ownership of the actual ADAS switches and their normal CAN behaviour.
Java.perform(function () {
    "use strict";

    var TAG = "VoyahApollo";
    var READY_MARKER = "[apollo] hook ready";
    var SENTINEL_KEY = "open_voyah.apollo.voboost_entitlement.v1";
    var ACTIVE_SUBSCRIPTION =
        '{"expireStatus":"0","isMqtt":false,"remainDays":"30","subscriptionStatus":"1"}';
    var NOA_LEARNED = "1";
    var Log = Java.use("android.util.Log");
    var JavaSystem = Java.use("java.lang.System");
    var subscribeQuery = null;
    var noaLearnQuery = null;
    var subscribeInstalled = false;
    var noaLearnInstalled = false;

    function ready(details) {
        // Logging cannot be allowed to roll back two successfully installed method hooks.
        try { Log.i(TAG, READY_MARKER + " " + details); } catch (ignoredLog) {}
        try { console.log(READY_MARKER + " " + details); } catch (ignoredConsole) {}
    }

    try {
        if (("" + JavaSystem.getProperty(SENTINEL_KEY, "")) === "installed") {
            ready("state=already_installed");
            return;
        }

        var BaiduProviderUtil = Java.use(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil");
        subscribeQuery = BaiduProviderUtil.doQuerySubscribeInfo;
        noaLearnQuery = BaiduProviderUtil.doQueryNOALearnInfo;

        // Keep the exact method-level replacement used by voboost. Both methods have one OEM
        // overload on the Android 11 VehicleSetting build; arguments are deliberately unused.
        subscribeQuery.implementation = function () {
            return ACTIVE_SUBSCRIPTION;
        };
        subscribeInstalled = true;
        noaLearnQuery.implementation = function () {
            return NOA_LEARNED;
        };
        noaLearnInstalled = true;

        JavaSystem.setProperty(SENTINEL_KEY, "installed");
        ready("profile=voboost subscription=active noa_learned=1");
    } catch (e) {
        // Never leave a half-installed entitlement profile, including the unlikely sentinel-write
        // failure after both method assignments.
        if (noaLearnInstalled) {
            try { noaLearnQuery.implementation = null; } catch (ignoredNoa) {}
        }
        if (subscribeInstalled) {
            try { subscribeQuery.implementation = null; } catch (ignoredSubscribe) {}
        }
        // Do not expose OEM paths or provider payloads in logs. A failed resolution is passive:
        // VehicleSetting retains its stock implementations for any hook that was not installed.
        try { Log.e(TAG, "[apollo] hook failed stage=install"); } catch (ignoredErrorLog) {}
        try { console.log("[apollo] hook failed stage=install"); } catch (ignoredErrorConsole) {}
    }
});
