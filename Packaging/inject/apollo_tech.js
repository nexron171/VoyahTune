// Boot-scoped Apollo subscription/exam reveal for com.qinggan.app.vehiclesetting.
//
// Native/load.bin injects this script only after the user explicitly enables
// “Активировать Apollo в настройках” for the current Linux boot. The hook is intentionally
// confined to Apollo/drive-assistance subscription classes: it never lies about the global 97X
// platform, never reveals the OEM function switches, never checks the current gear, and never
// sends CAN.
// Individual Apollo functions are configured and restored by VoyahTune itself.
Java.perform(function () {
    "use strict";

    var TAG = "VoyahApollo";
    var READY_MARKER = "[apollo] hook ready";
    var SENTINEL_KEY = "open_voyah.apollo.settings_reveal.v1";
    var ACTIVE_SUBSCRIPTION =
        '{"expireStatus":"0","isMqtt":false,"remainDays":"30","subscriptionStatus":"1"}';
    var NOA_LEARNED = "1";
    var Log = Java.use("android.util.Log");
    var JavaSystem = Java.use("java.lang.System");
    var installedMethods = [];

    function install(method, implementation) {
        method.implementation = implementation;
        installedMethods.push(method);
    }

    function forceSubscriptionUiVisible(fragment) {
        try {
            var datas = fragment.datas.value;
            if (datas !== null) {
                datas.setShowAdas(true);
                datas.setStatusType(1002);
                datas.setShowAIIntelligence(true);
            }
        } catch (ignoredData) {}
        try {
            var binding = fragment.binding.value;
            if (binding !== null) {
                binding.fragmentAdasSubStatusBg.value.setVisibility(0);
            }
        } catch (ignoredBinding) {}
    }

    function ready(details) {
        try { Log.i(TAG, READY_MARKER + " " + details); } catch (ignoredLog) {}
        try { console.log(READY_MARKER + " " + details); } catch (ignoredConsole) {}
    }

    function refreshExistingApolloFragments() {
        Java.choose(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.DriveAssistanceFragment", {
                onMatch: function (fragment) {
                    Java.scheduleOnMainThread(function () {
                        try {
                            fragment.updateAdasData();
                            fragment.getSDBState();
                        } catch (ignoredRefresh) {}
                    });
                },
                onComplete: function () {}
            });
    }

    try {
        if (("" + JavaSystem.getProperty(SENTINEL_KEY, "")) === "installed") {
            ready("state=already_installed");
            return;
        }

        var BaiduProviderUtil = Java.use(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil");
        install(BaiduProviderUtil.doQuerySubscribeInfo.overload("android.content.Context"),
            function () { return ACTIVE_SUBSCRIPTION; });
        install(BaiduProviderUtil.doQueryNOALearnInfo.overload(
            "android.content.Context", "java.lang.String"),
            function () { return NOA_LEARNED; });

        // Only the Apollo/SDB capability bit is overridden. Both global AppCommonUtils.is97X() and
        // the fragment's local 97X/97XY layout selectors remain stock, so unrelated 97X controls do
        // not change their variants or visibility.
        var DriveAssistantConfig = Java.use(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.DriveAssistantConfig");
        install(DriveAssistantConfig.isSupportSDB.overload(), function () { return true; });

        var DriveAssistantData = Java.use(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.DriveAssistantData");
        install(DriveAssistantData.isShowAdas.overload(), function () { return true; });
        install(DriveAssistantData.getStatusType.overload(), function () { return 1002; });
        install(DriveAssistantData.isShowAIIntelligence.overload(), function () { return true; });

        // 97X constructs this manager in a deliberately inert state. Its pure status getters are
        // enough for the stock fragment; we do not start its TSP threads/observers or call its CAN
        // entitlement writer.
        var AdasStatusManager = Java.use(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.adas." +
            "DriveAssistanceAdasStatusManager");
        install(AdasStatusManager.getSubscriptionStatus.overload(), function () { return true; });
        install(AdasStatusManager.getExpireStatus.overload(), function () { return true; });
        install(AdasStatusManager.getRemainDay.overload(), function () { return 30; });
        install(AdasStatusManager.getLearnStatus.overload(), function () { return 1; });

        // The OEM fragment has two explicit 97X early-outs. Run the stock method first, then repair
        // only its subscription/exam view model. Function rows keep the stock H97X visibility.
        var DriveAssistanceFragment = Java.use(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.DriveAssistanceFragment");
        var updateAdasData = DriveAssistanceFragment.updateAdasData.overload();
        install(updateAdasData, function () {
            updateAdasData.call(this);
            forceSubscriptionUiVisible(this);
        });
        var getSDBState = DriveAssistanceFragment.getSDBState.overload();
        install(getSDBState, function () {
            getSDBState.call(this);
            forceSubscriptionUiVisible(this);
        });

        // Injection can race the first fragment render. Refresh already-created instances once;
        // future instances are covered by the method hooks above. There is no timer or polling.
        refreshExistingApolloFragments();
        JavaSystem.setProperty(SENTINEL_KEY, "installed");
        ready("profile=persisted-target ui=subscription_exam subscription=active noa_learned=1 can=none");
    } catch (e) {
        for (var i = installedMethods.length - 1; i >= 0; i--) {
            try { installedMethods[i].implementation = null; } catch (ignoredRollback) {}
        }
        try { Log.e(TAG, "[apollo] hook failed stage=install"); } catch (ignoredErrorLog) {}
        try { console.log("[apollo] hook failed stage=install"); } catch (ignoredErrorConsole) {}
    }
});
