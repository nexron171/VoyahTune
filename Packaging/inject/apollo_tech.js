// apollo_tech.js — explicit legacy/diagnostic Apollo hook для VehicleSetting.
//
// Direct-only режим по умолчанию не инжектит этот файл. Даже ручной attach fail-closed и не ставит
// хуки без Settings.Global open_voyah_apollo_legacy_hook_enabled == 1.
// Fake-подписка и activation asyncQueryAdasSubData() разрешены
// одновременно только когда:
//   • Settings.Global open_voyah_apollo_master == 1;
//   • SHA-256 установленных VehicleSetting.apk и CanBusService.apk совпадают с pinned-профилем;
//   • автомобиль: штатный 97C (ASC=1/SDB=1).
// Точный H97X-профиль публикуется отдельно как direct-TLC mode: в нём fake-подписка и глобальный
// Apollo master всегда запрещены, а защищённый Native-компонент управляет только PLC_SWITCH.
// Любая ошибка — pass-through без дополнительных ADAS-запросов. Прямых CAN/Bundle-записей,
// приватных direct-send, overseas/model spoof, лицензирования и локализации здесь нет. После OFF или
// gate-loss допускается не более трёх stock async attempts до non-null provider observation; это
// bounded попытки штатного запроса, а не ECU-confirmation. Generic CAN callback не хукается:
// legacy activation поддерживается только observer-переходами и системным SCREEN_ON.
Java.perform(function () {
    "use strict";

    var TAG = "VoyahApollo";
    var LEGACY_OPT_IN_KEY = "open_voyah_apollo_legacy_hook_enabled";
    var MASTER_KEY = "open_voyah_apollo_master";
    var ASC_KEY = "open_voyah_apollo_asc";
    var SDB_KEY = "open_voyah_apollo_sdb";
    var PROFILE_KEY = "open_voyah_apollo_profile_supported";
    var VEHICLE_SETTING_SHA256 = "72f1c549e5cbfe22f65169898710d63c84981adcbbf7490c959f84fdeff621e6";
    var CANBUS_PACKAGE = "com.qinggan.canbus.service";
    var CANBUS_SHA256 = "96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c";
    var H97X_CANBUS_SOFTWARE = "H97XSA8155-DAILY-20251010221730-USR";
    var H97X_TRACKING_BRAND = "H97C";
    var PROCESS_SENTINEL_KEY = "open_voyah.apollo.hook_state.v1";
    var READY_MARKER = "[apollo] hook ready";
    var SCREEN_ON_ACTION = "android.intent.action.SCREEN_ON";
    var FAKE_SUBSCRIPTION = "{\"expireStatus\":\"0\",\"isMqtt\":false,\"remainDays\":\"30\",\"subscriptionStatus\":\"1\"}";
    var ACTIVATION_DEBOUNCE_MS = 5000;
    var STOCK_RESYNC_RETRY_MS = 10000;
    var MAX_STOCK_RESYNC_ATTEMPTS = 3;

    var Log = Java.use("android.util.Log");
    var ActivityThread = Java.use("android.app.ActivityThread");
    var SettingsGlobal = Java.use("android.provider.Settings$Global");
    var Handler = Java.use("android.os.Handler");
    var HandlerThread = Java.use("android.os.HandlerThread");
    var AndroidProcess = Java.use("android.os.Process");
    var SystemClock = Java.use("android.os.SystemClock");
    var SystemProperties = Java.use("android.os.SystemProperties");
    var JavaSystem = Java.use("java.lang.System");
    var BroadcastReceiver = Java.use("android.content.BroadcastReceiver");
    var IntentFilter = Java.use("android.content.IntentFilter");
    var ContentObserver = Java.use("android.database.ContentObserver");

    var application = ActivityThread.currentApplication();
    if (application === null) {
        console.log("[apollo] event=install_failed stage=context error=application_unavailable");
        return;
    }
    var context = application.getApplicationContext();
    var resolver = context.getContentResolver();

    var settingsGetInt = SettingsGlobal.getInt.overload(
        "android.content.ContentResolver", "java.lang.String", "int");
    var settingsPutInt = SettingsGlobal.putInt.overload(
        "android.content.ContentResolver", "java.lang.String", "int");
    var settingsGetUri = SettingsGlobal.getUriFor.overload("java.lang.String");
    var systemPropertyGet = SystemProperties.get.overload(
        "java.lang.String", "java.lang.String");

    function info(event, details) {
        Log.i(TAG, "event=" + event + (details ? " " + details : ""));
    }

    function warn(event, details) {
        Log.w(TAG, "event=" + event + (details ? " " + details : ""));
    }

    function error(event, details) {
        Log.e(TAG, "event=" + event + (details ? " " + details : ""));
    }

    function putIntSetting(key, value) {
        try {
            var written = settingsPutInt.call(SettingsGlobal, resolver, key, value);
            if (!written) warn("setting_write_rejected", "key=" + key);
            return written;
        } catch (e) {
            // Не выводим exception: сообщения файловых API могут содержать локальный путь.
            error("setting_write_failed", "key=" + key);
            return false;
        }
    }

    function clearPublishedGate(reason, suppressInfo) {
        var profileCleared = putIntSetting(PROFILE_KEY, 0);
        if (suppressInfo !== true) {
            info("gate_cleared", "reason=" + reason + " ok=" + (profileCleared ? 1 : 0));
        }
        return profileCleared;
    }

    // null означает ошибку чтения и всегда трактуется как disabled/pass-through.
    function readPersistedMaster() {
        try {
            return settingsGetInt.call(SettingsGlobal, resolver, MASTER_KEY, 0) === 1;
        } catch (e) {
            error("master_read_failed", "fail_closed=1");
            return null;
        }
    }

    function readLegacyOptIn() {
        try {
            return settingsGetInt.call(SettingsGlobal, resolver, LEGACY_OPT_IN_KEY, 0) === 1;
        } catch (e) {
            error("legacy_opt_in_read_failed", "fail_closed=1");
            return null;
        }
    }

    var vehicleHashMatches = false;
    var canBusHashMatches = false;
    var canBusMetadataSnapshot = null;
    var diagnosticH97XProfile = false;
    var legacy97CProfile = false;

    function sha256MatchesSource(sourceDir, expected, target) {
        var input = null;
        try {
            var FileInputStream = Java.use("java.io.FileInputStream");
            var MessageDigest = Java.use("java.security.MessageDigest");
            var readBytes = FileInputStream.read.overload("[B");
            var updateDigest = MessageDigest.update.overload("[B", "int", "int");
            input = FileInputStream.$new(sourceDir);
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = Java.array("byte", new Array(262144).fill(0));
            var count;
            while ((count = readBytes.call(input, buffer)) !== -1) {
                updateDigest.call(digest, buffer, 0, count);
            }
            var bytes = digest.digest();
            var hex = "";
            for (var i = 0; i < bytes.length; i++) {
                var unsigned = bytes[i] & 0xff;
                if (unsigned < 16) hex += "0";
                hex += unsigned.toString(16);
            }
            var matches = hex === expected;
            info("apk_hash", "target=" + target + " match=" + (matches ? 1 : 0));
            return matches;
        } catch (e) {
            // Никогда не логируем sourceDir или текст исключения файлового API.
            error("apk_hash_failed", "target=" + target + " fail_closed=1");
            return false;
        } finally {
            if (input !== null) {
                try { input.close(); } catch (ignored) {}
            }
        }
    }

    function readCanBusMetadata() {
        try {
            var packageManager = context.getPackageManager();
            var getApplicationInfo = packageManager.getApplicationInfo.overload(
                "java.lang.String", "int");
            var getPackageInfo = packageManager.getPackageInfo.overload(
                "java.lang.String", "int");
            var canBusInfo = getApplicationInfo.call(packageManager, CANBUS_PACKAGE, 0);
            var packageInfo = getPackageInfo.call(packageManager, CANBUS_PACKAGE, 0);
            var File = Java.use("java.io.File");
            var source = "" + canBusInfo.sourceDir.value;
            var apkFile = File.$new(source);
            return {
                source: source,
                updateTime: Number(packageInfo.lastUpdateTime.value),
                size: Number(apkFile.length()),
                modified: Number(apkFile.lastModified())
            };
        } catch (e) {
            error("canbus_metadata_failed", "fail_closed=1");
            return null;
        }
    }

    function sameCanBusMetadata(left, right) {
        return left !== null && right !== null
            && left.source === right.source
            && left.updateTime === right.updateTime
            && left.size === right.size
            && left.modified === right.modified;
    }

    function verifyPinnedPackages() {
        try {
            var vehicleSource = context.getApplicationInfo().sourceDir.value;
            vehicleHashMatches = sha256MatchesSource(
                vehicleSource, VEHICLE_SETTING_SHA256, "vehiclesetting");
            canBusMetadataSnapshot = readCanBusMetadata();
            canBusHashMatches = canBusMetadataSnapshot !== null
                && sha256MatchesSource(canBusMetadataSnapshot.source, CANBUS_SHA256, "canbus");
            return vehicleHashMatches && canBusHashMatches;
        } catch (e) {
            error("apk_profile_failed", "fail_closed=1");
            vehicleHashMatches = false;
            canBusHashMatches = false;
            return false;
        }
    }

    // VehicleSetting source не может смениться без смерти текущего процесса. CanBusService может
    // обновиться отдельно: дешёво сравниваем metadata при event/transition/point-of-use и хешируем
    // его APK повторно только после фактического изменения metadata.
    function refreshCanBusPin(reason) {
        var current = readCanBusMetadata();
        if (current === null) {
            canBusHashMatches = false;
            canBusMetadataSnapshot = null;
            return false;
        }
        if (!sameCanBusMetadata(current, canBusMetadataSnapshot)) {
            canBusHashMatches = sha256MatchesSource(current.source, CANBUS_SHA256, "canbus");
            canBusMetadataSnapshot = current;
            info("canbus_pin_rechecked", "reason=" + reason
                + " match=" + (canBusHashMatches ? 1 : 0));
        }
        return vehicleHashMatches && canBusHashMatches;
    }

    // Читает реальную конфигурацию без spoof. published=true возможно только когда allowPublish=true
    // и все диагностические записи успешны. Attach отдельно заранее обнуляет profile;
    // event-driven refresh не создаёт ложное окно profile=0 между двумя успешными проверками.
    function evaluateProfile(reason, allowPublish) {
        try {
            var VehicleConfigHelper = Java.use("com.qinggan.vehicle.VehicleConfigHelper");
            var VehicleOnlineState = Java.use("com.qinggan.vehicle.VehicleOnlineState");
            var Utils = Java.use("com.qinggan.app.vehiclesetting.fragments.driveassistance.utils.Utils");
            var AppCommonUtils = Java.use("com.qinggan.utils.AppCommonUtils");

            var readOnlineConfigItem = VehicleConfigHelper.readOnlineConfigItem.overload(
                "com.qinggan.vehicle.VehicleOnlineState");
            var is97CMethod = Utils.is97C.overload();
            var is97YMethod = AppCommonUtils.is97Y.overload();
            var is97XMethod = AppCommonUtils.is97X.overload();
            var isN1Method = AppCommonUtils.is97D_N1.overload();
            var isHikvisionMethod = AppCommonUtils.is97D_N2Hikvision.overload();

            var asc = readOnlineConfigItem.call(VehicleConfigHelper, VehicleOnlineState.ASC.value);
            var sdb = readOnlineConfigItem.call(VehicleConfigHelper, VehicleOnlineState.SDB.value);
            var is97C = is97CMethod.call(Utils);
            var is97Y = is97YMethod.call(AppCommonUtils);
            var is97X = is97XMethod.call(AppCommonUtils);
            var isN1 = isN1Method.call(AppCommonUtils);
            var isHikvision = isHikvisionMethod.call(AppCommonUtils);
            var stock97C = is97C && asc === 1 && sdb === 1
                && !is97Y && !is97X && !isN1 && !isHikvision;
            var canBusSoftware = "" + systemPropertyGet.call(SystemProperties,
                "persist.qg.canbus.ipk_software", "");
            var trackingBrand = "" + systemPropertyGet.call(SystemProperties,
                "persist.tracking.frombrand", "");
            var diagnosticH97X = is97X && !is97Y && !isN1 && !isHikvision
                && asc === 1 && sdb === 0
                && canBusSoftware === H97X_CANBUS_SOFTWARE
                && trackingBrand === H97X_TRACKING_BRAND;
            var vehicleSupported = stock97C || diagnosticH97X;
            diagnosticH97XProfile = diagnosticH97X;
            legacy97CProfile = stock97C;

            var ascWritten = putIntSetting(ASC_KEY, asc);
            var sdbWritten = putIntSetting(SDB_KEY, sdb);
            var canPublish = allowPublish && vehicleSupported && ascWritten && sdbWritten;
            var profileWritten = putIntSetting(PROFILE_KEY, canPublish ? 1 : 0);
            var published = canPublish && profileWritten;

            info("profile", "reason=" + reason + " asc=" + asc + " sdb=" + sdb
                + " is97c=" + (is97C ? 1 : 0) + " excluded="
                + ((is97Y || is97X || isN1 || isHikvision) ? 1 : 0)
                + " mode=" + (diagnosticH97X ? "diagnostic_h97x" : (stock97C ? "stock_97c" : "unsupported"))
                + " published=" + (published ? 1 : 0));
            return {
                vehicleSupported: vehicleSupported,
                diagnosticH97X: diagnosticH97X,
                published: published
            };
        } catch (e) {
            diagnosticH97XProfile = false;
            legacy97CProfile = false;
            putIntSetting(ASC_KEY, 0);
            putIntSetting(SDB_KEY, 0);
            putIntSetting(PROFILE_KEY, 0);
            error("profile_read_failed", "reason=" + reason + " fail_closed=1");
            return { vehicleSupported: false, published: false };
        }
    }

    // Повторный attach в ту же VM (например, если потерян marker-файл) не ставит второй observer/hook.
    var existingSentinel = JavaSystem.getProperty(PROCESS_SENTINEL_KEY);
    if (existingSentinel !== null) {
        console.log(READY_MARKER);
        info("duplicate_attach_skipped", "state=" + existingSentinel.toString());
        return;
    }

    // Defense in depth for manual/frida attach: direct-only must not hash APK or touch OEM classes.
    if (readLegacyOptIn() !== true) {
        clearPublishedGate("legacy_opt_in_disabled");
        putIntSetting(MASTER_KEY, 0);
        JavaSystem.setProperty(PROCESS_SENTINEL_KEY, "disabled_opt_in");
        console.log(READY_MARKER);
        info("hooks_disabled", "reason=legacy_opt_in");
        return;
    }

    // Сбрасываем stale gate до любых vendor-class/hook операций, затем pin-им оба installed APK.
    clearPublishedGate("attach_start");
    var hashMatches = verifyPinnedPackages();
    evaluateProfile("preflight", false);
    if (!hashMatches) {
        JavaSystem.setProperty(PROCESS_SENTINEL_KEY, "disabled_hash");
        console.log(READY_MARKER);
        info("hooks_disabled", "reason=apk_hash");
        return;
    }

    var BaiduProviderUtil;
    var AdasManager;
    var subscribeQuery;
    var managerInstance;
    var asyncQuery;
    var managerSingletonField;
    var managerSingletonGet;

    try {
        // Сначала разрешаем все exact overload, нужные в подтверждённом профиле;
        // частично установленный hook не допускается.
        BaiduProviderUtil = Java.use(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil");
        AdasManager = Java.use(
            "com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.DriveAssistanceAdasStatusManager");

        subscribeQuery = BaiduProviderUtil.doQuerySubscribeInfo.overload("android.content.Context");
        managerInstance = AdasManager.instance.overload("android.content.Context");
        asyncQuery = AdasManager.asyncQueryAdasSubData.overload();
        managerSingletonField = AdasManager.class.getDeclaredField("instance");
        managerSingletonField.setAccessible(true);
        managerSingletonGet = managerSingletonField.get.overload("java.lang.Object");
    } catch (e) {
        clearPublishedGate("core_resolution_failed");
        console.log("[apollo] event=install_failed stage=resolve");
        return;
    }

    var hooksInstalled = false;
    var hookAllowed = false;
    var selfDisarmed = false;
    var subscribeInstalled = false;
    var masterKnown = false;
    var persistedMaster = false;
    var activationBlockedUntil = 0;
    var eventThread = null;
    var eventHandler = null;
    var observer = null;
    var screenOnReceiver = null;
    var screenOnReceiverRegistered = false;
    var fakeMayBeApplied = false;
    var pendingStockResync = false;
    var stockResyncInFlight = false;
    var stockResyncTimer = null;
    var stockResyncAttempts = 0;
    var stockResyncExhaustedLogged = false;
    var pendingStockAllowsRawOn = false;
    var forceStockPassThrough = false;
    var reactivateAfterStockResync = false;
    var postStockReactivationTimer = null;
    var selfDisarmCleanupTimer = null;
    var selfDisarmCleanupDone = false;
    var selfDisarmReason = "";
    var selfDisarmStockRefreshPending = false;
    var selfDisarmStockRefreshAttempted = false;
    var selfDisarmStockRefreshSource = "not_needed";

    function detachProviderHookNow() {
        if (!subscribeInstalled) return true;
        try {
            subscribeQuery.implementation = null;
            subscribeInstalled = false;
            return true;
        } catch (e) {
            return false;
        }
    }

    function detachEventSources() {
        var cleanupOk = true;
        var registeredReceiver = screenOnReceiver;
        var receiverWasRegistered = screenOnReceiverRegistered;
        screenOnReceiver = null;
        screenOnReceiverRegistered = false;
        if (receiverWasRegistered && registeredReceiver !== null) {
            try {
                context.unregisterReceiver(registeredReceiver);
            } catch (receiverError) {
                cleanupOk = false;
            }
        }

        var registeredObserver = observer;
        observer = null;
        if (registeredObserver !== null) {
            try {
                resolver.unregisterContentObserver(registeredObserver);
            } catch (observerError) {
                cleanupOk = false;
            }
        }

        eventHandler = null;
        var runningThread = eventThread;
        eventThread = null;
        if (runningThread !== null) {
            try {
                runningThread.quitSafely();
            } catch (threadError) {
                cleanupOk = false;
            }
        }
        return cleanupOk;
    }

    // A provider call already in progress is itself the required stock refresh: claim it before
    // invoking the saved original so deferred cleanup cannot dispatch a duplicate OEM query.
    function claimSelfDisarmStockRefresh(source) {
        if (!selfDisarmed || !selfDisarmStockRefreshPending
                || selfDisarmStockRefreshAttempted) {
            return false;
        }
        selfDisarmStockRefreshPending = false;
        selfDisarmStockRefreshAttempted = true;
        selfDisarmStockRefreshSource = source;
        return true;
    }

    // At most one best-effort OEM query removes a fake result that may already be cached. This path
    // deliberately has no retry, debounce, activation, or reactivation semantics.
    function dispatchSelfDisarmStockRefresh() {
        if (!claimSelfDisarmStockRefresh("deferred")) return true;
        try {
            var existingManager = managerSingletonGet.call(managerSingletonField, null);
            var manager = managerInstance.call(AdasManager, context);
            if (manager === null) {
                selfDisarmStockRefreshSource = "manager_null";
                return false;
            }
            var constructorDispatched = existingManager === null && !diagnosticH97XProfile;
            if (!constructorDispatched) asyncQuery.call(manager);
            selfDisarmStockRefreshSource = constructorDispatched
                ? "constructor" : "explicit";
            return true;
        } catch (e) {
            selfDisarmStockRefreshSource = "failed";
            return false;
        }
    }

    /**
     * Runs outside an executing provider implementation. Flags have already made every queued
     * callback passive; cleanup only removes registrations, publishes zeroes, and (if needed)
     * performs one bounded stock query after the provider hook has been detached.
     */
    function finishSelfDisarmCleanup() {
        if (selfDisarmCleanupDone) return;
        selfDisarmCleanupDone = true;
        var cleanupOk = true;
        try {
            JavaSystem.setProperty(PROCESS_SENTINEL_KEY, "disabled_opt_out");
        } catch (sentinelError) {
            cleanupOk = false;
        }
        if (stockResyncTimer !== null) {
            try { clearTimeout(stockResyncTimer); } catch (stockTimerError) { cleanupOk = false; }
            stockResyncTimer = null;
        }
        if (postStockReactivationTimer !== null) {
            try {
                clearTimeout(postStockReactivationTimer);
            } catch (reactivationTimerError) {
                cleanupOk = false;
            }
            postStockReactivationTimer = null;
        }
        if (!detachEventSources()) cleanupOk = false;

        if (!putIntSetting(MASTER_KEY, 0)) cleanupOk = false;
        if (!clearPublishedGate("legacy_opt_out", true)) cleanupOk = false;
        if (!detachProviderHookNow()) cleanupOk = false;
        if (!dispatchSelfDisarmStockRefresh()) cleanupOk = false;
        info("legacy_self_disarmed", "reason=" + selfDisarmReason
            + " cleanup_ok=" + (cleanupOk ? 1 : 0)
            + " stock_refresh=" + selfDisarmStockRefreshSource);
    }

    function scheduleSelfDisarmCleanup(providerCallbackActive) {
        if (selfDisarmCleanupDone || selfDisarmCleanupTimer !== null) return true;
        try {
            selfDisarmCleanupTimer = setTimeout(function () {
                Java.perform(function () {
                    selfDisarmCleanupTimer = null;
                    finishSelfDisarmCleanup();
                });
            }, 0);
            return true;
        } catch (e) {
            selfDisarmCleanupTimer = null;
            // Never unregister/replace an implementation from inside that implementation.
            if (providerCallbackActive === true) return false;
            finishSelfDisarmCleanup();
            return true;
        }
    }

    /**
     * Monotonic fail-passive transition. All guards flip before any deferred cleanup, so queued
     * timers and a still-installed provider hook can only pass through to stock code.
     */
    function selfDisarmLegacy(reason, providerCallbackActive) {
        if (!selfDisarmed) {
            var hadFake = fakeMayBeApplied;
            selfDisarmed = true;
            hooksInstalled = false;
            hookAllowed = false;
            forceStockPassThrough = true;
            fakeMayBeApplied = false;
            pendingStockResync = false;
            stockResyncInFlight = false;
            pendingStockAllowsRawOn = false;
            stockResyncAttempts = 0;
            stockResyncExhaustedLogged = false;
            reactivateAfterStockResync = false;
            masterKnown = true;
            persistedMaster = false;
            activationBlockedUntil = 0;
            selfDisarmReason = reason;
            selfDisarmStockRefreshPending = hadFake;
            selfDisarmStockRefreshSource = hadFake ? "pending" : "not_needed";
        }
        scheduleSelfDisarmCleanup(providerCallbackActive);
        return false;
    }

    function ensureLegacyOptInOrDisarm(reason, providerCallbackActive) {
        if (selfDisarmed) {
            scheduleSelfDisarmCleanup(providerCallbackActive);
            return false;
        }
        if (readLegacyOptIn() === true) return true;
        return selfDisarmLegacy(reason, providerCallbackActive);
    }

    function scheduleStockResync(reason, allowRawOn, resetBudget) {
        if (selfDisarmed) return;
        if (!pendingStockResync || (resetBudget === true && !stockResyncInFlight)) {
            stockResyncAttempts = 0;
            stockResyncExhaustedLogged = false;
        }
        pendingStockResync = true;
        pendingStockAllowsRawOn = pendingStockAllowsRawOn || allowRawOn === true;
        info("stock_resync_scheduled", "reason=" + reason
            + " raw_on_allowed=" + (pendingStockAllowsRawOn ? 1 : 0)
            + " attempt=" + stockResyncAttempts + "/" + MAX_STOCK_RESYNC_ATTEMPTS);
    }

    function retryPendingStockResync() {
        if (selfDisarmed || !pendingStockResync || stockResyncInFlight
                || stockResyncAttempts >= MAX_STOCK_RESYNC_ATTEMPTS) {
            return false;
        }
        if (!ensureLegacyOptInOrDisarm("stock_resync_retry", false)) return false;
        // runAsyncQuery повторно проверяет raw master и APK pin. allowRawOn действует только для
        // уже установленного gate-loss shutdown; обычный user-OFF по-прежнему требует raw 0.
        return runAsyncQuery(
            "bounded_stock_retry", true, pendingStockAllowsRawOn);
    }

    function schedulePendingStockRetry() {
        if (selfDisarmed || !pendingStockResync || stockResyncTimer !== null) return false;
        try {
            stockResyncTimer = setTimeout(function () {
                Java.perform(function () {
                    stockResyncTimer = null;
                    if (selfDisarmed || !pendingStockResync) return;
                    stockResyncInFlight = false;
                    var retryAvailable = stockResyncAttempts < MAX_STOCK_RESYNC_ATTEMPTS;
                    warn("stock_resync_unconfirmed", "attempt=" + stockResyncAttempts
                        + "/" + MAX_STOCK_RESYNC_ATTEMPTS + " retry_available="
                        + (retryAvailable ? 1 : 0));
                    if (retryAvailable) retryPendingStockResync();
                });
            }, STOCK_RESYNC_RETRY_MS);
            return true;
        } catch (e) {
            stockResyncTimer = null;
            error("stock_resync_retry_schedule_failed", "pending=1");
            return false;
        }
    }

    function markFakeMayBeApplied() {
        if (selfDisarmed) return;
        fakeMayBeApplied = true;
        // Новый ON/fake supersede-ит старый OFF-resync и даёт следующему OFF новый bounded budget.
        // Если raw key уже успел стать 0, observer должен сохранить/создать shutdown, а не потерять его.
        if (readPersistedMaster() !== true) return;
        pendingStockResync = false;
        pendingStockAllowsRawOn = false;
        stockResyncInFlight = false;
        stockResyncAttempts = 0;
        stockResyncExhaustedLogged = false;
        forceStockPassThrough = false;
        reactivateAfterStockResync = false;
        if (stockResyncTimer !== null) {
            clearTimeout(stockResyncTimer);
            stockResyncTimer = null;
        }
    }

    // Вызывается уже из OEM provider callback: текущий organic query сам является нужным stock
    // resync, поэтому не стартуем второй Thread. Только помечаем bounded attempt до saved original.
    function beginOrganicStockResync(reason) {
        if (selfDisarmed) return false;
        if (!pendingStockResync || stockResyncInFlight) return false;
        var rawMaster = readPersistedMaster();
        var rawAllowed = rawMaster === false
            || (rawMaster === true && pendingStockAllowsRawOn);
        if (!hashMatches || !rawAllowed) return false;
        if (stockResyncAttempts >= MAX_STOCK_RESYNC_ATTEMPTS) {
            if (!stockResyncExhaustedLogged) {
                warn("stock_resync_exhausted", "source=organic attempts=" + stockResyncAttempts
                    + " pending=1 ecu_confirmed=0");
                stockResyncExhaustedLogged = true;
            }
            return false;
        }
        if (rawMaster === true) {
            forceStockPassThrough = true;
            reactivateAfterStockResync = true;
            hookAllowed = false;
            clearPublishedGate("organic_gate_loss_resync");
        }
        stockResyncAttempts++;
        stockResyncInFlight = true;
        info("stock_resync_observing", "reason=" + reason + " source=organic attempt="
            + stockResyncAttempts + "/" + MAX_STOCK_RESYNC_ATTEMPTS);
        return true;
    }

    function refreshValidatedGate(reason, suppressActivation) {
        if (selfDisarmed) return false;
        var wasAllowed = hookAllowed;
        hashMatches = refreshCanBusPin(reason);
        var result = evaluateProfile(reason, hooksInstalled && hashMatches);
        hookAllowed = hooksInstalled && hashMatches && result.published
            && !forceStockPassThrough;
        if (forceStockPassThrough) {
            putIntSetting(PROFILE_KEY, 0);
        }
        if (hooksInstalled) {
            JavaSystem.setProperty(PROCESS_SENTINEL_KEY,
                hookAllowed ? "active" : "pass_through");
        }
        if (wasAllowed && !hookAllowed && fakeMayBeApplied) {
            // Gate-loss не должен забывать ранее выданный fake. При exact pins stock pass-through
            // допустим и для raw ON; при hash mismatch pending ждёт pin recovery.
            scheduleStockResync("validated_gate_lost", true, false);
        }
        // OFF либо gate-loss не теряются: выполняется bounded серия stock query при pinned APK.
        if (hashMatches && pendingStockResync) {
            var rawMaster = readPersistedMaster();
            if (rawMaster === false) {
                runAsyncQuery("deferred_master_off_resync", true, false);
            } else if (rawMaster === true && pendingStockAllowsRawOn) {
                runAsyncQuery("gate_loss_resync", true, true);
            }
        }
        // Если профиль был временно не готов, persisted ON не теряется: первое verified gate opening
        // делает один activation query. SCREEN_ON может подавить этот внутренний dispatch и выполнить
        // не более одного activation после согласованного gate/master refresh.
        if (suppressActivation !== true
                && !wasAllowed && hookAllowed && !diagnosticH97XProfile
                && masterKnown && !forceStockPassThrough
                && !stockResyncInFlight) {
            var masterAtGateOpen = readPersistedMaster();
            if (masterAtGateOpen === true) runAsyncQuery("gate_open_enabled", false);
        }
        info("validated_gate", "reason=" + reason + " allowed=" + (hookAllowed ? 1 : 0));
        return hookAllowed;
    }

    function runAsyncQuery(reason, stockResync, allowRawOn) {
        if (selfDisarmed) return false;
        if (stockResync === true) {
            // User-OFF требует raw 0. Gate-loss shutdown может выполнить pass-through при raw 1, но
            // только пока in-memory profile gate уже закрыт и оба APK всё ещё pinned.
            var rawMaster = readPersistedMaster();
            hashMatches = refreshCanBusPin("stock_resync:" + reason);
            var gateLossRawOn = allowRawOn === true && rawMaster === true;
            if ((rawMaster !== false && !gateLossRawOn) || !hashMatches) {
                info("stock_resync_deferred", "reason=" + reason + " raw_off="
                    + (rawMaster === false ? 1 : 0) + " gate_loss_raw_on="
                    + (gateLossRawOn ? 1 : 0) + " pinned=" + (hashMatches ? 1 : 0));
                return false;
            }
        } else if (!hookAllowed || diagnosticH97XProfile) {
            info("async_query_skipped", "reason=" + reason + " gate=0");
            return false;
        }
        if (stockResync === true && stockResyncInFlight) {
            info("stock_resync_coalesced", "reason=" + reason);
            return false;
        }
        if (stockResync === true && stockResyncAttempts >= MAX_STOCK_RESYNC_ATTEMPTS) {
            if (!stockResyncExhaustedLogged) {
                warn("stock_resync_exhausted", "attempts=" + stockResyncAttempts
                    + " pending=1 ecu_confirmed=0");
                stockResyncExhaustedLogged = true;
            }
            return false;
        }
        var now = SystemClock.elapsedRealtime();
        if (stockResync !== true && now < activationBlockedUntil) {
            info("activation_query_debounced", "reason=" + reason);
            return false;
        }
        if (stockResync !== true) {
            // Reserve before OEM code: duplicate SCREEN_ON/observer delivery and synchronous
            // failures cannot turn one wake into a burst of activation requests.
            activationBlockedUntil = now + ACTIVATION_DEBOUNCE_MS;
        }
        try {
            if (stockResync === true && allowRawOn === true
                    && readPersistedMaster() === true) {
                // На время shutdown-query даже восстановившийся profile остаётся pass-through.
                forceStockPassThrough = true;
                reactivateAfterStockResync = true;
                hookAllowed = false;
                clearPublishedGate("gate_loss_resync");
                JavaSystem.setProperty(PROCESS_SENTINEL_KEY, "pass_through");
            }
            if (stockResync === true) stockResyncAttempts++;
            var existingManager = managerSingletonGet.call(managerSingletonField, null);
            // На штатном 97C новый singleton сам вызывает asyncQueryAllAdasStatus(). На H97X
            // constructor намеренно early-return, поэтому диагностический профиль обязан вызвать
            // public asyncQueryAdasSubData() явно даже для только что созданного singleton.
            // Помечаем stock in-flight до instance(), чтобы быстрый worker мог подтвердить provider.
            if (stockResync === true) stockResyncInFlight = true;
            var manager = managerInstance.call(AdasManager, context);
            if (manager === null) {
                if (stockResync === true) {
                    stockResyncInFlight = false;
                    schedulePendingStockRetry();
                }
                warn("async_query_skipped", "reason=" + reason + " cause=manager_null");
                return false;
            }
            var constructorDispatched = existingManager === null && !diagnosticH97XProfile;
            if (!constructorDispatched) asyncQuery.call(manager);
            if (stockResync === true) {
                // Provider может отработать очень быстро до возврата call(); helper ставит только
                // один bounded retry и success-path ниже отменит его.
                schedulePendingStockRetry();
            } else {
                markFakeMayBeApplied();
            }
            info("async_query_requested", "reason=" + reason
                + " stock_resync=" + (stockResync === true ? 1 : 0)
                + " dispatch=" + (constructorDispatched ? "constructor"
                    : (diagnosticH97XProfile ? "explicit_h97x" : "explicit")));
            return true;
        } catch (e) {
            if (stockResync === true) {
                stockResyncInFlight = false;
                schedulePendingStockRetry();
            }
            error("async_query_failed", "reason=" + reason);
            return false;
        }
    }

    // Возвращает true только для уже известного explicit 0<->1 transition.
    function refreshMaster(reason) {
        if (selfDisarmed) return null;
        var next = readPersistedMaster();
        if (next === null) return null;
        // H97X использует только прямой PLC_SWITCH. Никогда не сохраняем и не активируем master,
        // потому что штатный manager отправляет через него полный пакет из 18 entitlement-полей.
        if (diagnosticH97XProfile) {
            if (next) {
                putIntSetting(MASTER_KEY, 0);
                info("master_forced_off", "reason=direct_h97x source=" + reason);
            }
            persistedMaster = false;
            masterKnown = true;
            return next;
        }
        if (!masterKnown) {
            persistedMaster = next;
            masterKnown = true;
            info("master_initial", "enabled=" + (next ? 1 : 0)
                + " gate=" + (hookAllowed ? 1 : 0));
            // Missing/0 полностью пассивен. Persisted explicit 1 делает один query только при valid gate.
            if (next && hookAllowed) runAsyncQuery("initial_enabled");
            return false;
        }
        if (next === persistedMaster) {
            // Organic fake query мог увидеть краткий raw ON между observer callbacks. Даже при cached
            // OFF такой same-state callback обязан запросить/отложить stock resync.
            if (!next && fakeMayBeApplied) {
                scheduleStockResync("same_state_off", false, false);
                if (hashMatches) runAsyncQuery("same_state_off_resync", true, false);
            }
            return false;
        }

        var previous = persistedMaster;
        persistedMaster = next;
        info("master_transition", "from=" + (previous ? 1 : 0) + " to=" + (next ? 1 : 0)
            + " source=" + reason + " gate=" + (hookAllowed ? 1 : 0));
        if (next) {
            // ON никогда не активируется при invalid gate.
            if (hookAllowed) runAsyncQuery("master_transition_on", false);
        } else if (fakeMayBeApplied) {
            // OFF не теряем: при valid gate resync сейчас, иначе откладываем до восстановления gate.
            scheduleStockResync("master_transition_off", false, true);
            if (hashMatches) runAsyncQuery("master_transition_off_resync", true, false);
        }
        return true;
    }

    function effectiveMaster() {
        if (selfDisarmed) return false;
        if (forceStockPassThrough) return false;
        if (!hookAllowed) return false;
        // Point-of-use fail-closed: CanBusService и online profile могут измениться независимо от
        // VehicleSetting. Проверяем их прямо перед fake, но не запускаем отсюда activation/resync,
        // чтобы provider callback не породил рекурсивный async query.
        var wasAllowed = hookAllowed;
        hashMatches = refreshCanBusPin("subscription_point_of_use");
        var profileResult = evaluateProfile(
            "subscription_point_of_use", hooksInstalled && hashMatches);
        hookAllowed = hooksInstalled && hashMatches && profileResult.published;
        JavaSystem.setProperty(PROCESS_SENTINEL_KEY,
            hookAllowed ? "active" : "pass_through");
        if (diagnosticH97XProfile) {
            if (readPersistedMaster() === true) putIntSetting(MASTER_KEY, 0);
            info("subscription_gate_closed", "reason=direct_h97x");
            return false;
        }
        if (!hookAllowed) {
            if (wasAllowed && fakeMayBeApplied) {
                scheduleStockResync("point_of_use_gate_lost", true, false);
                beginOrganicStockResync("point_of_use_gate_lost");
            }
            warn("subscription_gate_closed", "reason=point_of_use_validation pinned="
                + (hashMatches ? 1 : 0));
            return false;
        }
        var enabled = readPersistedMaster();
        if (enabled === false && fakeMayBeApplied) {
            scheduleStockResync("point_of_use_master_off", false, false);
            beginOrganicStockResync("point_of_use_master_off");
        }
        return enabled === true;
    }

    // SCREEN_ON — единственный wake-resync legacy-пути. Gate refresh здесь не запускает activation
    // сам: initial/transition master уже делает максимум один query, а same-state ON получает ровно
    // один дополнительный запрос через общий elapsedRealtime debounce.
    function handleScreenOn(intent) {
        if (selfDisarmed || !hooksInstalled || intent === null) return;
        var action;
        try {
            action = intent.getAction();
            if (action === null || action.toString() !== SCREEN_ON_ACTION) return;
            // Системный SCREEN_ON приходит implicit. Явно адресованный spoof не является wake.
            if (intent.getComponent() !== null) return;
        } catch (intentError) {
            error("screen_on_rejected", "cause=intent_read_failed");
            return;
        }
        if (!ensureLegacyOptInOrDisarm("screen_on", false)) return;
        refreshValidatedGate("screen_on", true);
        if (selfDisarmed) return;

        var masterWasKnown = masterKnown;
        var transitioned = refreshMaster("screen_on");
        if (selfDisarmed || transitioned === null || !masterWasKnown
                || transitioned === true) {
            return;
        }
        if (!legacy97CProfile || diagnosticH97XProfile || !hookAllowed
                || !persistedMaster || forceStockPassThrough
                || pendingStockResync || stockResyncInFlight) {
            return;
        }
        runAsyncQuery("screen_on_restore", false);
    }

    function callStockSubscription(queryContext) {
        claimSelfDisarmStockRefresh("provider");
        var stockResult;
        try {
            stockResult = subscribeQuery.call(BaiduProviderUtil, queryContext);
        } catch (stockError) {
            if (pendingStockResync && stockResyncInFlight) {
                stockResyncInFlight = false;
                schedulePendingStockRetry();
                warn("stock_resync_provider_failed", "retry_available="
                    + (stockResyncAttempts < MAX_STOCK_RESYNC_ATTEMPTS ? 1 : 0));
            }
            throw stockError;
        }
        if (pendingStockResync && stockResyncInFlight) {
            stockResyncInFlight = false;
            if (stockResult !== null) {
                if (stockResyncTimer !== null) {
                    clearTimeout(stockResyncTimer);
                    stockResyncTimer = null;
                }
                pendingStockResync = false;
                pendingStockAllowsRawOn = false;
                fakeMayBeApplied = false;
                stockResyncAttempts = 0;
                stockResyncExhaustedLogged = false;
                info("stock_resync_provider_observed", "result=non_null ecu_confirmed=0");
                if (reactivateAfterStockResync && postStockReactivationTimer === null) {
                    // OEM worker поставит stock result в main Handler сразу после возврата hook.
                    // Delayed revalidation enqueues возможный ON позже и сохраняет порядок.
                    postStockReactivationTimer = setTimeout(function () {
                        Java.perform(function () {
                            postStockReactivationTimer = null;
                            if (!ensureLegacyOptInOrDisarm(
                                    "post_stock_reactivation", false)) {
                                return;
                            }
                            forceStockPassThrough = false;
                            reactivateAfterStockResync = false;
                            refreshValidatedGate("post_gate_loss_resync");
                            refreshMaster("post_gate_loss_resync");
                        });
                    }, 500);
                } else if (!reactivateAfterStockResync) {
                    forceStockPassThrough = false;
                }
            } else {
                schedulePendingStockRetry();
                warn("stock_resync_provider_empty", "retry_available="
                    + (stockResyncAttempts < MAX_STOCK_RESYNC_ATTEMPTS ? 1 : 0));
            }
        }
        return stockResult;
    }

    var hookInstallStage = "subscribe_hook";
    try {
        subscribeQuery.implementation = function (queryContext) {
            if (!ensureLegacyOptInOrDisarm("provider_entry", true)) {
                return callStockSubscription(queryContext);
            }
            if (!effectiveMaster()) {
                info("subscription_query", "mode=passthrough");
                return callStockSubscription(queryContext);
            }
            info("subscription_query", "mode=fake_active");
            markFakeMayBeApplied();
            // Exact final check is deliberately immediately before FAKE_SUBSCRIPTION.
            if (!ensureLegacyOptInOrDisarm("provider_before_fake", true)) {
                return callStockSubscription(queryContext);
            }
            return FAKE_SUBSCRIPTION;
        };
        subscribeInstalled = true;

        hookInstallStage = "event_thread";
        eventThread = HandlerThread.$new(
            "VoyahApolloEvents", AndroidProcess.THREAD_PRIORITY_BACKGROUND.value);
        eventThread.start();
        eventHandler = Handler.$new(eventThread.getLooper());

        hookInstallStage = "observer_class";
        var ObserverClass = Java.registerClass({
            name: "com.qinggan.app.vehiclesetting.VoyahApolloObserver_" + Date.now(),
            superClass: ContentObserver,
            methods: {
                $init: [
                    {
                        returnType: "void",
                        argumentTypes: ["android.os.Handler"],
                        implementation: function (handler) {
                            this.$super.$init(handler);
                        }
                    }
                ],
                onChange: [
                    {
                        returnType: "void",
                        argumentTypes: ["boolean"],
                        implementation: function () {
                            if (selfDisarmed || !hooksInstalled) return;
                            if (!ensureLegacyOptInOrDisarm("observer", false)) return;
                            refreshValidatedGate("master_observer");
                            refreshMaster("observer");
                        }
                    },
                    {
                        returnType: "void",
                        argumentTypes: ["boolean", "android.net.Uri"],
                        implementation: function () {
                            if (selfDisarmed || !hooksInstalled) return;
                            if (!ensureLegacyOptInOrDisarm("observer_uri", false)) return;
                            refreshValidatedGate("master_observer_uri");
                            refreshMaster("observer_uri");
                        }
                    }
                ]
            }
        });
        hookInstallStage = "observer_instance";
        observer = ObserverClass.$new(eventHandler);
        var masterUri = settingsGetUri.call(SettingsGlobal, MASTER_KEY);
        var legacyOptInUri = settingsGetUri.call(SettingsGlobal, LEGACY_OPT_IN_KEY);
        hookInstallStage = "master_observer_register";
        resolver.registerContentObserver.overload(
            "android.net.Uri", "boolean", "android.database.ContentObserver")
            .call(resolver, masterUri, false, observer);
        hookInstallStage = "opt_in_observer_register";
        resolver.registerContentObserver.overload(
            "android.net.Uri", "boolean", "android.database.ContentObserver")
            .call(resolver, legacyOptInUri, false, observer);

        hookInstallStage = "screen_receiver_class";
        var ScreenOnReceiverClass = Java.registerClass({
            name: "com.qinggan.app.vehiclesetting.VoyahApolloScreenReceiver_" + Date.now(),
            superClass: BroadcastReceiver,
            methods: {
                onReceive: {
                    returnType: "void",
                    argumentTypes: ["android.content.Context", "android.content.Intent"],
                    implementation: function (receiverContext, intent) {
                        handleScreenOn(intent);
                    }
                }
            }
        });
        hookInstallStage = "screen_receiver_instance";
        screenOnReceiver = ScreenOnReceiverClass.$new();
        var screenOnFilter = IntentFilter.$new(SCREEN_ON_ACTION);
        hookInstallStage = "screen_receiver_register";
        context.registerReceiver.overload(
            "android.content.BroadcastReceiver", "android.content.IntentFilter",
            "java.lang.String", "android.os.Handler")
            .call(context, screenOnReceiver, screenOnFilter, null, eventHandler);
        screenOnReceiverRegistered = true;

        hooksInstalled = true;
        hookInstallStage = "validated_gate";
        // One exact check per attach chain, immediately before gate/profile publication.
        if (!ensureLegacyOptInOrDisarm("attach", false)) {
            hookInstallStage = "ready_self_disarmed";
            console.log(READY_MARKER);
            return;
        }
        refreshValidatedGate("attach");
        if (selfDisarmed) {
            hookInstallStage = "ready_self_disarmed";
            console.log(READY_MARKER);
            return;
        }
        // Gate/hash/profile подтверждены attach-событием; лишь теперь допустим initial query.
        hookInstallStage = "initial_master";
        refreshMaster("initial");

        hookInstallStage = "ready";
        if (hookAllowed) {
            console.log(READY_MARKER);
            info("hooks_installed", "mode=active events=observer,screen_on");
        } else {
            console.log(READY_MARKER);
            info("hooks_installed", "mode=pass_through profile=0 events=observer,screen_on");
        }
    } catch (e) {
        hooksInstalled = false;
        hookAllowed = false;
        if (stockResyncTimer !== null) clearTimeout(stockResyncTimer);
        stockResyncTimer = null;
        if (postStockReactivationTimer !== null) clearTimeout(postStockReactivationTimer);
        postStockReactivationTimer = null;
        if (selfDisarmCleanupTimer !== null) clearTimeout(selfDisarmCleanupTimer);
        selfDisarmCleanupTimer = null;
        detachEventSources();
        detachProviderHookNow();
        try {
            if (selfDisarmed) {
                JavaSystem.setProperty(PROCESS_SENTINEL_KEY, "disabled_opt_out");
            } else {
                JavaSystem.clearProperty(PROCESS_SENTINEL_KEY);
            }
        } catch (ignored2) {}
        clearPublishedGate("hook_failed");
        error("hook_install_failed", "stage=" + hookInstallStage);
        console.log("[apollo] event=install_failed stage=" + hookInstallStage);
    }
});
