#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HOOK="$REPO_ROOT/Packaging/inject/apollo_tech.js"
HOOK_MANIFEST="$REPO_ROOT/Packaging/system/voyahtune-hook-manifest.json"
LOAD_BIN="$REPO_ROOT/Packaging/system/load.bin"
README="$REPO_ROOT/Packaging/README.md"
ADVANCE="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
APOLLO_SETTINGS="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/ApolloSettings.java"
PROVIDER="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/RestoreModeContentProvider.java"
LAYOUT="$REPO_ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
SET_MODES="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
MAIN="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/MainActivity.java"
RESTORE_POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApolloRestorePolicy.java"
APPLY_ENGINE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApplyEngine.java"
NATIVE_MANIFEST="$REPO_ROOT/Native/app/src/main/AndroidManifest.xml"
RUNTIME_FLAG="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApolloSettingsRuntimeFlag.java"
RUNTIME_STATE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApolloSettingsRuntimeState.java"

fail() { echo "FAIL: $*" >&2; exit 1; }
require_fixed() { grep -Fq -- "$2" "$1" || fail "missing '$2' in $1"; }
forbid_fixed() {
    if grep -Fq -- "$2" "$1"; then
        fail "forbidden '$2' remains in $1"
    fi
}

# The Android 11 VehicleSettings hook only reveals subscription/exam status. It never exposes the
# hidden H97X function rows, changes Parking, subscribes to CAN, or writes VehicleState itself.
require_fixed "$HOOK" 'BaiduProviderUtil.doQuerySubscribeInfo.overload("android.content.Context")'
require_fixed "$HOOK" 'BaiduProviderUtil.doQueryNOALearnInfo.overload('
require_fixed "$HOOK" 'DriveAssistantConfig.isSupportSDB.overload()'
require_fixed "$HOOK" 'DriveAssistantData.isShowAdas.overload()'
require_fixed "$HOOK" 'DriveAssistanceAdasStatusManager'
require_fixed "$HOOK" 'forceSubscriptionUiVisible(this);'
require_fixed "$HOOK" 'binding.fragmentAdasSubStatusBg.value.setVisibility(0);'
require_fixed "$HOOK" 'ui=subscription_exam'
for SYMBOL in forceApolloBindingVisible FragmentDriveAssistanceBindingImpl \
        'executeBindings.overload()' onHintSwitchAdasClick getGearStatus GearState Parking \
        CanBusManager CanBusTool asyncQueryAllAdasStatus asyncQueryAdasSubData \
        onVehicleStateChanged sendAdasSubStatusToADCU setVehicleState \
        setVehicleAndAirConditionBundleState TX58 TX77 setInterval setTimeout; do
    forbid_fixed "$HOOK" "$SYMBOL"
done
node --check "$HOOK"

# The stock-menu target is a normal persisted setting. The boot-bound file is only a fail-closed
# loader transport republished by the same delayed restore plan.
require_fixed "$LAYOUT" 'android:text="Показывать подписку и экзамен Apollo в штатных настройках"'
require_fixed "$LAYOUT" 'android:id="@+id/switchApolloSettingsActivation"'
require_fixed "$LAYOUT" 'android:checked="false"'
require_fixed "$APOLLO_SETTINGS" 'static final String STOCK_UI = "apolloStockUiEnabled";'
require_fixed "$ADVANCE" 'ApolloSettings.STOCK_UI, ApolloSettings.DEFAULT_ENABLED'
require_fixed "$PROVIDER" 'ApolloSettings.STOCK_UI,      // 28'
require_fixed "$MAIN" 'apolloStockUiEnabled = cursor.getColumnCount() > 28'
require_fixed "$MAIN" 'plan.addOnce("Apollo stock subscription/exam UI"'
require_fixed "$MAIN" 'ApolloSettingsRuntimeState.applyTarget(context, stockUiTarget)'
require_fixed "$RUNTIME_STATE" 'static TargetApplyResult applyTarget(Context context, boolean enabled)'
require_fixed "$RUNTIME_STATE" 'forceStop.invoke(am, "com.qinggan.app.vehiclesetting")'
require_fixed "$RUNTIME_FLAG" 'boot='
require_fixed "$RUNTIME_FLAG" 'isEnabledForBoot'
require_fixed "$RUNTIME_STATE" 'createDeviceProtectedStorageContext()'
require_fixed "$RUNTIME_STATE" 'new File("/proc/sys/kernel/random/boot_id")'
require_fixed "$RUNTIME_STATE" 'StandardCopyOption.ATOMIC_MOVE'
for FILE in "$RUNTIME_FLAG" "$RUNTIME_STATE"; do
    forbid_fixed "$FILE" 'Settings.Global'
done
require_fixed "$LOAD_BIN" 'apollo_runtime_flag_enabled() {'
require_fixed "$LOAD_BIN" '[ "$APOLLO_FLAG_BOOT" = "$APOLLO_CURRENT_BOOT" ]'
require_fixed "$LOAD_BIN" 'if apollo_runtime_flag_enabled; then'
require_fixed "$HOOK" 'profile=persisted-target'
forbid_fixed "$ADVANCE" 'MSG_APOLLO_SETTINGS_SET'
forbid_fixed "$ADVANCE" 'MSG_APOLLO_SETTINGS_STATE'
forbid_fixed "$SET_MODES" 'MSG_APOLLO_SETTINGS_SET'
forbid_fixed "$SET_MODES" 'MSG_APOLLO_SETTINGS_STATE'

# VoyahTune owns five persisted targets. There is no current-state query or parking gate.
for KEY in STOCK_UI TLC TRAFFIC_LIGHTS GREEN_SOUND TRAFFIC_SIGNS; do
    require_fixed "$APOLLO_SETTINGS" "static final String $KEY"
done
require_fixed "$ADVANCE" 'bindApolloSwitch(switchApolloTlc, ApolloSettings.TLC);'
require_fixed "$ADVANCE" 'bindApolloSwitch(switchApolloTrafficLights, ApolloSettings.TRAFFIC_LIGHTS);'
require_fixed "$ADVANCE" 'bindApolloSwitch(switchApolloTrafficSigns, ApolloSettings.TRAFFIC_SIGNS);'
require_fixed "$ADVANCE" 'prefs.edit().putBoolean(ApolloSettings.GREEN_SOUND'
require_fixed "$LAYOUT" 'android:id="@+id/switchApolloTlc"'
require_fixed "$LAYOUT" 'android:id="@+id/switchApolloTrafficLights"'
require_fixed "$LAYOUT" 'android:id="@+id/switchApolloTrafficSigns"'
require_fixed "$PROVIDER" 'ApolloSettings.TLC,          // 24'
require_fixed "$PROVIDER" 'ApolloSettings.TRAFFIC_LIGHTS, // 25'
require_fixed "$PROVIDER" 'ApolloSettings.GREEN_SOUND, // 26'
require_fixed "$PROVIDER" 'ApolloSettings.TRAFFIC_SIGNS,// 27'
for SYMBOL in MSG_APOLLO_TLC_QUERY ACTION_APOLLO_TLC_UPDATE APOLLO_DEMAND_OWNER \
        requestQuery releaseApolloDemand ApolloTlcService ApolloCanBusDemandGate \
        ApolloTlcPolicy TX_GET_GEAR_STATUS TX_GET_VEHICLE_STATE; do
    if grep -R -Fq --exclude-dir=build --exclude-dir=.gradle \
            --exclude=test_apollo_direct_only.sh \
            "$SYMBOL" "$REPO_ROOT/Native" "$REPO_ROOT/RestoreMode"; then
        fail "obsolete read-only Apollo symbol remains: $SYMBOL"
    fi
done
forbid_fixed "$NATIVE_MANIFEST" 'android:process=":apollo"'

# The existing wake restore sends capability values first and switches second through ordered OEM
# TX77 bundles. Disabled values are explicit too, so a target can actually be turned back off.
for ENTRY in PLC_SWITCH GLA_SWITCH GLA_LIGHT_CHANGE_SWITCH TSR_SWITCH \
        RPA_FUNC_ENABLE HPP_FUNC_ENABLE GLC_FUNC_ENABLE ISLC_FUNC_ENABLE TLC_FUNC_ENABLE \
        NOA_FUNC_ENABLE ELK_FUNC_ENABLE ESA_FUNC_ENABLE APA_FUNC_ENABLE_SA \
        RPA_FUNC_ENABLE_SA HAVP_FUNC_ENABLE_SA ACC_FUNC_ENABLE_SA ICA_FUNC_ENABLE_SA \
        PLC_FUNC_ENABLE_SA HANP_FUNC_ENABLE_SA ISA_FUNC_ENABLE_SA ISLC_FUNC_ENABLE_SA \
        TLA_FUNC_ENABLE_SA; do
    require_fixed "$RESTORE_POLICY" "static final String $ENTRY"
done
require_fixed "$RESTORE_POLICY" 'if (tlc || trafficLights || trafficSigns) {'
require_fixed "$RESTORE_POLICY" 'putAllEntitlements(entitlements, ENABLED);'
require_fixed "$RESTORE_POLICY" 'target.put(RPA_FUNC_ENABLE, value);'
require_fixed "$RESTORE_POLICY" 'target.put(TLA_FUNC_ENABLE_SA, value);'
require_fixed "$RESTORE_POLICY" 'switches.put(PLC_SWITCH, state(tlc));'
require_fixed "$RESTORE_POLICY" 'switches.put(GLA_LIGHT_CHANGE_SWITCH, state(trafficLights && greenSound));'
require_fixed "$RESTORE_POLICY" 'switches.put(TSR_SWITCH, trafficSigns ? 1 : 2);'
require_fixed "$MAIN" 'ApolloRestorePolicy.appendTo(primaryValues, trailingValues,'
require_fixed "$MAIN" 'OemVehicleStateTransport.sendRestoreSequence('
require_fixed "$MAIN" 'Apollo entitlements then switches/recuperation'
forbid_fixed "$RESTORE_POLICY" 'getVehicleState'
forbid_fixed "$RESTORE_POLICY" 'Parking'

# Automatic startup/wake restore waits ten seconds; explicit Apply remains on applyNow().
require_fixed "$APPLY_ENGINE" 'private static final long DEBOUNCE_MS = 10_000L;'
require_fixed "$APPLY_ENGINE" 'scheduledAt + DEBOUNCE_MS'
require_fixed "$APPLY_ENGINE" 'public static void applyNow('
require_fixed "$README" 'Скрытые на 97X строки отдельных функций не раскрываются'
require_fixed "$README" 'Автоматическое'
require_fixed "$README" 'через 10 секунд после wake-события'

# The manifest must commit the exact current hook bytes.
if command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA=$(shasum -a 256 "$HOOK" | awk '{print $1}')
else
    ACTUAL_SHA=$(sha256sum "$HOOK" | awk '{print $1}')
fi
require_fixed "$HOOK_MANIFEST" \
    "{\"id\":\"apollo-tech\",\"process\":\"com.qinggan.app.vehiclesetting\",\"script\":\"apollo_tech.js\",\"sha256\":\"$ACTUAL_SHA\"}"

echo "PASS: Apollo UI and functions use persisted event-driven restore targets"
