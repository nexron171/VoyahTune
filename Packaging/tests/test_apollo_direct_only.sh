#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApolloTlcService.java"
POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApolloTlcPolicy.java"
GATE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApolloCanBusDemandGate.java"
SET_MODES="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
ADVANCE="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
LAYOUT="$REPO_ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
MANIFEST="$REPO_ROOT/Native/app/src/main/AndroidManifest.xml"
LOAD_BIN="$REPO_ROOT/Packaging/system/load.bin"
APOLLO_HOOK="$REPO_ROOT/Packaging/inject/apollo_tech.js"
MAKE_RELEASE="$REPO_ROOT/make_release.sh"
README="$REPO_ROOT/Packaging/README.md"
FULL_INSTALL_SH="$REPO_ROOT/Packaging/installer/full/install.sh"
FULL_INSTALL_BAT="$REPO_ROOT/Packaging/installer/full/install.bat"
FULL_REMOVE_SH="$REPO_ROOT/Packaging/installer/full/remove.sh"
FULL_REMOVE_BAT="$REPO_ROOT/Packaging/installer/full/remove.bat"
LIGHT_INSTALL_SH="$REPO_ROOT/Packaging/installer/light/install.sh"
LIGHT_INSTALL_BAT="$REPO_ROOT/Packaging/installer/light/install.bat"
LIGHT_REMOVE_SH="$REPO_ROOT/Packaging/installer/light/remove.sh"
LIGHT_REMOVE_BAT="$REPO_ROOT/Packaging/installer/light/remove.bat"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "missing '$2' in $1"
}

forbid_fixed() {
    if grep -Fq -- "$2" "$1"; then
        fail "forbidden '$2' remains in $1"
    fi
}

# Full entitlement hook exactly follows the two voboost provider replacements. It must not bring
# back the former master/profile/async activation machinery or any CAN interaction.
[ -s "$APOLLO_HOOK" ] || fail "minimal apollo_tech.js is missing"
require_fixed "$APOLLO_HOOK" \
    'com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil'
require_fixed "$APOLLO_HOOK" 'BaiduProviderUtil.doQuerySubscribeInfo'
require_fixed "$APOLLO_HOOK" 'BaiduProviderUtil.doQueryNOALearnInfo'
require_fixed "$APOLLO_HOOK" \
    '{"expireStatus":"0","isMqtt":false,"remainDays":"30","subscriptionStatus":"1"}'
require_fixed "$APOLLO_HOOK" 'var NOA_LEARNED = "1";'
require_fixed "$APOLLO_HOOK" '[apollo] hook ready'
for FORBIDDEN in DriveAssistanceAdasStatusManager asyncQueryAdasSubData onVehicleStateChanged \
        ICanBusService SettingsGlobal setInterval setTimeout TX58 TX77; do
    forbid_fixed "$APOLLO_HOOK" "$FORBIDDEN"
done
node --check "$APOLLO_HOOK"

# Loader only discovers VehicleSetting and performs one latched attempt per exact process identity.
# No Apollo Settings polling or activation CAN transaction is allowed.
require_fixed "$LOAD_BIN" 'APOLLO_TARGET=com.qinggan.app.vehiclesetting'
require_fixed "$LOAD_BIN" 'inject_apollo() {'
require_fixed "$LOAD_BIN" 'reserve_injection_attempt "$APOLLO_ID" "$APOLLO_ATTEMPT"'
require_fixed "$LOAD_BIN" 'APOLLO_PID=$(pidof "$APOLLO_TARGET" 2>/dev/null)'
require_fixed "$LOAD_BIN" 'APOLLO_READY_MARKER='
for FORBIDDEN in APOLLO_LEGACY_OPT_IN apollo_startup_once open_voyah_apollo \
        'settings get global' 'settings put global' asyncQueryAdasSubData; do
    forbid_fixed "$LOAD_BIN" "$FORBIDDEN"
done
[ "$(grep -Fc 'APOLLO_PID=$(pidof "$APOLLO_TARGET" 2>/dev/null)' "$LOAD_BIN")" -eq 1 ] \
    || fail "Apollo process discovery is not a single watchdog call site"

require_fixed "$MAKE_RELEASE" 'apollo_tech.js'
require_fixed "$FULL_INSTALL_SH" \
    'install_required_data_file apollo_tech.js /data/local/bin/apollo_tech.js 644'
require_fixed "$FULL_INSTALL_BAT" \
    'install_required_data_file apollo_tech.js /data/local/bin/apollo_tech.js 644'
require_fixed "$FULL_INSTALL_SH" 'ensure_apollo_entitlement_ready()'
require_fixed "$FULL_INSTALL_BAT" ':ensure_apollo_entitlement_ready'
require_fixed "$FULL_INSTALL_SH" '/data/local/tmp/voyahtune_apollo.pid'
require_fixed "$FULL_INSTALL_BAT" '/data/local/tmp/voyahtune_apollo.pid'
for FILE in "$FULL_INSTALL_SH" "$FULL_INSTALL_BAT" "$LIGHT_INSTALL_SH" "$LIGHT_INSTALL_BAT"; do
    require_fixed "$FILE" "rm -f /data/local/bin/apollo_tech.js"
done
for FILE in "$FULL_REMOVE_SH" "$FULL_REMOVE_BAT" "$LIGHT_REMOVE_SH" "$LIGHT_REMOVE_BAT"; do
    require_fixed "$FILE" '/data/local/tmp/voyahtune_apollo.pid'
    require_fixed "$FILE" '/data/local/tmp/voyahtune_apollo.attempt'
    require_fixed "$FILE" '/data/local/tmp/voyahtune_apollo.txt'
done
require_fixed "$README" "entitlement как в voboost"

# The section remains visible. Entitlement is automatic in full; all displayed CAN controls remain
# read-only and therefore cannot duplicate stock VehicleSetting writes.
require_fixed "$LAYOUT" 'android:id="@+id/pageApolloTech"'
require_fixed "$LAYOUT" 'Full-версия автоматически открывает штатную подписку ADAS'
require_fixed "$ADVANCE" 'private void requestApolloState()'
require_fixed "$ADVANCE" 'switchApolloTlc.setEnabled(false);'
require_fixed "$ADVANCE" 'switchApolloTrafficLights.setEnabled(false);'
require_fixed "$ADVANCE" 'switchApolloTrafficSigns.setEnabled(false);'
for SYMBOL in MSG_APOLLO_SET sendApolloCommand canChangeApollo showApolloTlcEnableDialog \
        showApolloAnpDependencyDialog; do
    forbid_fixed "$ADVANCE" "$SYMBOL"
done

# Native accepts only a query and reads TX57/TX6. No activation message, TX58/TX77, entitlement
# vector, write state machine or delayed write confirmation may remain.
require_fixed "$SET_MODES" 'MSG_APOLLO_TLC_QUERY'
for SYMBOL in MSG_APOLLO_TLC_SET MSG_APOLLO_GLA_SET MSG_APOLLO_GLA_SOUND_SET \
        MSG_APOLLO_TSR_SET requestTlcSet requestGlaSet requestGlaSoundSet requestTsrSet; do
    forbid_fixed "$SET_MODES" "$SYMBOL"
done
require_fixed "$SERVICE" 'TX_GET_GEAR_STATUS = 6'
require_fixed "$SERVICE" 'TX_GET_VEHICLE_STATE = 57'
require_fixed "$SERVICE" 'getVehicleState(ApolloTlcPolicy.Signal signal)'
require_fixed "$SERVICE" 'return binder.transact(transactionCode, data, reply, 0);'
for SYMBOL in TX_SET_VEHICLE_STATE TX_SET_VEHICLE_AND_AIR_BUNDLE_STATE \
        ACTION_INTERNAL_SET ACTION_INTERNAL_GLA_SET ACTION_INTERNAL_GLA_SOUND_SET \
        ACTION_INTERNAL_TSR_SET setVehicleState setCompositeEntitlements Entitlement \
        beginPendingWrite transmitPendingSignal writeGeneration pendingDesiredState \
        requestTlcSet requestGlaSet requestGlaSoundSet requestTsrSet; do
    forbid_fixed "$SERVICE" "$SYMBOL"
done
for SYMBOL in Entitlement requestedPlcState requestedTsrState directTlcBlockReason \
        directSwitchBlockReason writeSessionCurrent; do
    forbid_fixed "$POLICY" "$SYMBOL"
done

# Android 11 containment and demand ownership stay intact for the read-only vendor Binder calls.
require_fixed "$MANIFEST" 'android:name=".ApolloTlcService"'
require_fixed "$MANIFEST" 'android:process=":apollo"'
require_fixed "$SERVICE" 'VENDOR_BINDER_CALL_TIMEOUT_MS = 15_000L'
require_fixed "$SERVICE" 'Process.killProcess(Process.myPid());'
require_fixed "$SERVICE" 'new ArrayBlockingQueue<>(1)'
require_fixed "$SERVICE" 'executor.allowCoreThreadTimeOut(true);'
require_fixed "$ADVANCE" 'private static final IBinder APOLLO_DEMAND_OWNER = new Binder();'
require_fixed "$ADVANCE" 'putBinder(EXTRA_APOLLO_DEMAND_OWNER, APOLLO_DEMAND_OWNER)'
require_fixed "$SET_MODES" 'getBinder(ApolloTlcService.EXTRA_DEMAND_OWNER)'
require_fixed "$SERVICE" 'implements IBinder.DeathRecipient'
require_fixed "$SERVICE" 'owner.linkToDeath(candidate, 0);'
require_fixed "$SERVICE" 'link.owner.unlinkToDeath(link, 0);'
require_fixed "$GATE" 'final class ApolloCanBusDemandGate'
for SYMBOL in onVehicleStateChanged TRANSACTION_addCallback registerCanBusCallback \
        LEASE_TTL renewDemand demandHeartbeat periodicDemand; do
    forbid_fixed "$SERVICE" "$SYMBOL"
done

echo "PASS: Apollo uses the minimal voboost entitlement hook; Native CAN UI remains read-only"
