#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HOOK="$REPO_ROOT/Packaging/od/inject/apollo_tech.js"
LOAD_BIN="$REPO_ROOT/Packaging/od/system/load.bin"
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

# The Android 11 VehicleSettings hook overrides subscription/exam status without changing stock
# view visibility. It never exposes hidden H97X rows, subscribes to CAN, or writes VehicleState.
require_fixed "$HOOK" 'BaiduProviderUtil.doQuerySubscribeInfo.overload("android.content.Context")'
require_fixed "$HOOK" 'BaiduProviderUtil.doQueryNOALearnInfo.overload('
require_fixed "$HOOK" 'DriveAssistantConfig.isSupportSDB.overload()'
require_fixed "$HOOK" 'DriveAssistanceAdasStatusManager'
require_fixed "$HOOK" 'ui=stock_visibility'
for SYMBOL in forceApolloBindingVisible FragmentDriveAssistanceBindingImpl \
        'executeBindings.overload()' onHintSwitchAdasClick getGearStatus GearState Parking \
        CanBusManager CanBusTool asyncQueryAllAdasStatus asyncQueryAdasSubData \
        onVehicleStateChanged sendAdasSubStatusToADCU setVehicleState \
        setVehicleAndAirConditionBundleState TX58 TX77 setInterval setTimeout \
        forceSubscriptionUiVisible refreshExistingApolloFragments \
        'DriveAssistantData.isShowAdas.overload()' 'setShowAdas(' \
        'fragmentAdasSubStatusBg.value.setVisibility(' \
        'DriveAssistanceFragment.updateAdasData.overload()' \
        'DriveAssistanceFragment.getSDBState.overload()'; do
    forbid_fixed "$HOOK" "$SYMBOL"
done
node --check "$HOOK"

# The retired stock-menu target is never exposed or republished. The old hook transport remains
# fail-closed while the installer and Updater migrate prior installations.
forbid_fixed "$LAYOUT" 'android:id="@+id/switchApolloSettingsActivation"'
forbid_fixed "$ADVANCE" 'switchApolloSettingsActivation'
require_fixed "$APOLLO_SETTINGS" 'static final String STOCK_UI = "apolloStockUiEnabled";'
require_fixed "$PROVIDER" 'remove(ApolloSettings.STOCK_UI).commit()'
require_fixed "$PROVIDER" 'ApolloSettings.STOCK_UI,      // 28'
require_fixed "$PROVIDER" '0, // 28: retired stock UI target'
forbid_fixed "$MAIN" 'apolloStockUiEnabled = cursor.getColumnCount() > 28'
forbid_fixed "$MAIN" 'cacheApolloStockUiEnabled", apolloStockUiEnabled'
require_fixed "$MAIN" 'plan.addOnce("Apollo stock subscription/exam UI"'
require_fixed "$MAIN" 'ApolloSettingsRuntimeState.applyTarget(context, false)'
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

# Only the individual feature targets remain selectable; STOCK_UI is migration-only.
for KEY in STOCK_UI TLC TRAFFIC_LIGHTS GREEN_SOUND TRAFFIC_SIGNS SPEED_SIGNS CRUISE_SPEED_ADJUSTMENT SPEED_MODE SPEED_WARNING; do
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
        ApolloTlcPolicy; do
    if grep -R -Fq --exclude-dir=build --exclude-dir=.gradle \
            --include='*.java' --include='*.xml' \
            --exclude=test_apollo_direct_only.sh \
            "$SYMBOL" "$REPO_ROOT/Native" "$REPO_ROOT/RestoreMode"; then
        fail "obsolete read-only Apollo symbol remains: $SYMBOL"
    fi
done
# TX6 is owned by the shared CAN hub to recover the current trip's gear after a restart.
# It must not return as a separate Apollo getter or a second transport in a consumer.
if grep -R -Fq --include='*.java' --include='*.xml' --exclude=CanBusEventHub.java \
        'TX_GET_GEAR_STATUS' "$REPO_ROOT/Native/app/src" "$REPO_ROOT/RestoreMode/app/src"; then
    fail "gear snapshot transport must remain in the shared CanBusEventHub"
fi
# TX57 is now a shared transport capability for the unrelated Power Hold one-shot SOC/status
# checks. Apollo itself must remain write-only and must not read current VehicleState.
forbid_fixed "$RESTORE_POLICY" 'readVehicleState'
forbid_fixed "$RESTORE_POLICY" 'TX_GET_VEHICLE_STATE'
forbid_fixed "$NATIVE_MANIFEST" 'android:process=":apollo"'

# The existing wake restore sends capabilities, optional ISA mode, then switches through ordered OEM
# TX77 bundles. Disabled values are explicit too, so a target can actually be turned back off.
for ENTRY in PLC_SWITCH GLA_SWITCH GLA_LIGHT_CHANGE_SWITCH TSR_SWITCH \
        RPA_FUNC_ENABLE HPP_FUNC_ENABLE GLC_FUNC_ENABLE ISLC_FUNC_ENABLE TLC_FUNC_ENABLE \
        NOA_FUNC_ENABLE ELK_FUNC_ENABLE ESA_FUNC_ENABLE APA_FUNC_ENABLE_SA \
        RPA_FUNC_ENABLE_SA HAVP_FUNC_ENABLE_SA ACC_FUNC_ENABLE_SA ICA_FUNC_ENABLE_SA \
        PLC_FUNC_ENABLE_SA HANP_FUNC_ENABLE_SA ISA_FUNC_ENABLE_SA ISLC_FUNC_ENABLE_SA \
        TLA_FUNC_ENABLE_SA; do
    require_fixed "$RESTORE_POLICY" "static final String $ENTRY"
done
require_fixed "$RESTORE_POLICY" 'putAllEntitlements(entitlements, state(tlc || trafficLights || trafficSigns || speedSigns));'
require_fixed "$RESTORE_POLICY" 'target.put(RPA_FUNC_ENABLE, value);'
require_fixed "$RESTORE_POLICY" 'target.put(TLA_FUNC_ENABLE_SA, value);'
require_fixed "$RESTORE_POLICY" 'switches.put(PLC_SWITCH, state(tlc));'
require_fixed "$RESTORE_POLICY" 'switches.put(GLA_LIGHT_CHANGE_SWITCH, state(trafficLights && greenSound));'
require_fixed "$RESTORE_POLICY" 'switches.put(TSR_SWITCH, trafficSigns ? 1 : 2);'
require_fixed "$MAIN" 'ApolloRestorePolicy.appendPlan(plan,'
require_fixed "$MAIN" 'OemVehicleStateTransport.sendRestoreSequence('
require_fixed "$MAIN" 'Apollo capabilities then ISA mode then PLC/GLA/TSR/ISA'
require_fixed "$ADVANCE" 'applyApolloTargets();'
require_fixed "$SET_MODES" 'ApplyEngine.applyApolloTargets(this);'
forbid_fixed "$RESTORE_POLICY" 'getVehicleState'
forbid_fixed "$RESTORE_POLICY" 'Parking'

# Restoration has no debounce; explicit Apply remains immediate.
forbid_fixed "$APPLY_ENGINE" 'DEBOUNCE_MS'
require_fixed "$APPLY_ENGINE" 'public static void applyNow('
require_fixed "$README" 'включить, так и выключить функцию, не раскрывая штатные'
require_fixed "$README" '97X-строки и не вводя отдельные CAN subscriptions'
require_fixed "$README" 'Автоматическое'
require_fixed "$README" 'восстановление OD выполняется один раз в ACC-цикле'


# ISA is a separate opt-in target; provider columns 0..36 stay compatible.
require_fixed "$ADVANCE" 'bindApolloSwitch(switchApolloSpeedSigns, ApolloSettings.SPEED_SIGNS);'
require_fixed "$LAYOUT" 'android:id="@+id/switchApolloSpeedSigns"'
require_fixed "$PROVIDER" 'ApolloSettings.SPEED_SIGNS, // 37'
require_fixed "$PROVIDER" 'apolloSpeedSignsEnabled ? 1 : 0,'
require_fixed "$MAIN" 'apolloSpeedSignsEnabled = cursor.getColumnCount() > 37 && cursor.getInt(37) == 1;'
require_fixed "$MAIN" '.putBoolean("cacheApolloSpeedSignsEnabled", apolloSpeedSignsEnabled)'
require_fixed "$MAIN" 'apolloSpeedSignsEnabled = p.getBoolean("cacheApolloSpeedSignsEnabled", false);'
require_fixed "$RESTORE_POLICY" 'modes.put(ISA_ISLC_MODE, speedSigns ? normalizeSpeedMode(speedMode) : 4);'
require_fixed "$RESTORE_POLICY" 'switches.put(ISA_ISLC_SWITCH, speedSigns ? 1 : 2);'

# The OD master switch gates correction and warning; PI retains its legacy appendTo call.
require_fixed "$PROVIDER" 'ApolloSettings.CRUISE_SPEED_ADJUSTMENT, // 38'
require_fixed "$PROVIDER" 'ApolloSettings.SPEED_MODE, // 39'
require_fixed "$PROVIDER" 'ApolloSettings.SPEED_WARNING, // 40'
require_fixed "$PROVIDER" 'apolloSpeedSignsEnabled && apolloSpeedMode == ApolloSettings.AUTO_CORRECTION ? 1 : 0,'
require_fixed "$MAIN" 'cursor.getColumnCount() > 39 ? cursor.getInt(39)'
require_fixed "$MAIN" 'apolloSpeedWarningEnabled = cursor.getColumnCount() > 40 && cursor.getInt(40) == 1;'
require_fixed "$MAIN" 'if (!InfrastructureProfile.read(context).usesAccHooks()) return;'
require_fixed "$MAIN" 'apolloSpeedMode, apolloSpeedWarningEnabled,'
require_fixed "$MAIN" '.putInt("cacheApolloSpeedMode", apolloSpeedMode)'
require_fixed "$MAIN" '.putBoolean("cacheApolloSpeedWarningEnabled", apolloSpeedWarningEnabled)'
require_fixed "$ADVANCE" 'apolloSpeedModeGroup.setOnCheckedChangeListener('
require_fixed "$ADVANCE" 'bindApolloSwitch(switchApolloSpeedWarning, ApolloSettings.SPEED_WARNING);'
require_fixed "$RESTORE_POLICY" 'switches.put(ISA_ISLC_OVER_SPEED_WARNING_SWITCH, state(speedSigns && speedWarning));'
forbid_fixed "$LAYOUT" 'android:id="@+id/switchApolloCruiseSpeedAdjustment"'
for ENTRY in apolloSpeedRecognition apolloSpeedConfirm apolloSpeedAutomatic switchApolloSpeedWarning; do
    require_fixed "$LAYOUT" "android:id=\"@+id/$ENTRY\""
done

echo "PASS: Apollo UI and functions use persisted event-driven restore targets"
