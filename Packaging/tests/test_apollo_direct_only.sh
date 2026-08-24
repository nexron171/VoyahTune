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
NATIVE_BUILD="$REPO_ROOT/Native/app/build.gradle.kts"
RESTORE_BUILD="$REPO_ROOT/RestoreMode/app/build.gradle.kts"
LOAD_BIN="$REPO_ROOT/Packaging/system/load.bin"
MAKE_RELEASE="$REPO_ROOT/make_release.sh"
README="$REPO_ROOT/Packaging/README.md"
FULL_INSTALL_SH="$REPO_ROOT/Packaging/installer/full/install.sh"
FULL_INSTALL_BAT="$REPO_ROOT/Packaging/installer/full/install.bat"
LIGHT_INSTALL_SH="$REPO_ROOT/Packaging/installer/light/install.sh"
LIGHT_INSTALL_BAT="$REPO_ROOT/Packaging/installer/light/install.bat"

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

# The obsolete VehicleSetting/GumJS implementation is not a release asset or runtime path.
[ ! -e "$REPO_ROOT/Packaging/inject/apollo_tech.js" ] \
    || fail "legacy apollo_tech.js still exists"
forbid_fixed "$MAKE_RELEASE" "apollo_tech.js"
for LEGACY in APOLLO_LEGACY_OPT_IN apollo_startup_once VehicleSetting voyah_apollo \
        open_voyah_apollo "settings get global" "settings put global"; do
    forbid_fixed "$LOAD_BIN" "$LEGACY"
done
for FILE in "$SERVICE" "$POLICY" "$SET_MODES" "$ADVANCE" "$LAYOUT"; do
    for LEGACY in open_voyah_apollo legacy_hook GLOBAL_MASTER_KEY APOLLO_MASTER_SET \
            MSG_APOLLO_SET_MASTER masterKnown masterEnabled switchApolloMaster \
            buttonApolloForceOff profile_vehicle_setting profile_heartbeat \
            profile_callback; do
        forbid_fixed "$FILE" "$LEGACY"
    done
done

# Installers may mention old names only to remove a previous installation; they must not package,
# back up, restore, install, or enable the old agent.
for FILE in "$FULL_INSTALL_SH" "$FULL_INSTALL_BAT" "$LIGHT_INSTALL_SH" "$LIGHT_INSTALL_BAT"; do
    require_fixed "$FILE" "rm -f /data/local/bin/apollo_tech.js"
    require_fixed "$FILE" "am force-stop com.qinggan.app.vehiclesetting"
done
for FILE in "$FULL_INSTALL_SH" "$FULL_INSTALL_BAT"; do
    forbid_fixed "$FILE" "install_required_data_file apollo_tech.js"
    forbid_fixed "$FILE" "backup_pull_with_absent /data/local/bin/apollo_tech.js"
done
require_fixed "$README" "Legacy VehicleSetting/Frida hook удалён"

# Both Android 11 flavors expose only the direct, permission/schema-pinned implementation.
for FILE in "$NATIVE_BUILD" "$RESTORE_BUILD"; do
    COUNT=$(grep -Fc 'buildConfigField("boolean", "HAS_DIRECT_APOLLO", "true")' "$FILE")
    [ "$COUNT" -eq 2 ] || fail "both flavors must enable direct Apollo in $FILE"
done
require_fixed "$MANIFEST" 'android:name=".ApolloTlcService"'
require_fixed "$MANIFEST" 'android:process=":apollo"'
require_fixed "$SERVICE" 'private static final String WRITE_CANBUS_PERMISSION ='
require_fixed "$SERVICE" 'return binder.transact(transactionCode, data, reply, 0);'
require_fixed "$SERVICE" 'return service.getInterfaceDescriptor();'

# A hung Android 11 vendor Binder call is contained by a one-shot watchdog in the private process.
require_fixed "$SERVICE" 'VENDOR_BINDER_CALL_TIMEOUT_MS = 15_000L'
require_fixed "$SERVICE" 'armVendorBinderWatchdog("transact " + transactionCode)'
require_fixed "$SERVICE" 'armVendorBinderWatchdog("getInterfaceDescriptor")'
require_fixed "$SERVICE" 'Process.killProcess(Process.myPid());'
forbid_fixed "$SERVICE" 'setInterval('
forbid_fixed "$SERVICE" 'metricsRunnable'
forbid_fixed "$SERVICE" 'METRICS_INTERVAL_MS'

# Schema work is lazy, bounded and latest-only rather than an unbounded executor queue.
require_fixed "$SERVICE" 'new ArrayBlockingQueue<>(1)'
require_fixed "$SERVICE" 'executor.allowCoreThreadTimeOut(true);'
require_fixed "$SERVICE" 'SCHEMA_THREAD_IDLE_TIMEOUT_SECONDS = 30L'
require_fixed "$SERVICE" 'if (!schemaCheckComplete) {'
require_fixed "$SERVICE" 'startSchemaCheck();'
forbid_fixed "$SERVICE" 'Executors.newSingleThreadExecutor'

# UI demand is tied to a real Binder owner; no lease heartbeat/TTL poll is introduced.
require_fixed "$ADVANCE" 'private static final IBinder APOLLO_DEMAND_OWNER = new Binder();'
require_fixed "$ADVANCE" 'putBinder(EXTRA_APOLLO_DEMAND_OWNER, APOLLO_DEMAND_OWNER)'
require_fixed "$SET_MODES" 'getBinder(ApolloTlcService.EXTRA_DEMAND_OWNER)'
require_fixed "$SERVICE" 'implements IBinder.DeathRecipient'
require_fixed "$SERVICE" 'owner.linkToDeath(candidate, 0);'
require_fixed "$SERVICE" 'link.owner.unlinkToDeath(link, 0);'
for SYMBOL in LEASE_TTL renewDemand demandHeartbeat periodicDemand; do
    forbid_fixed "$SERVICE" "$SYMBOL"
done

# Apollo never subscribes to the global CAN callback stream.
for SYMBOL in onVehicleStateChanged TRANSACTION_addCallback TRANSACTION_removeCallback \
        registerCanBusCallback unregisterCanBusCallback; do
    forbid_fixed "$SERVICE" "$SYMBOL"
done

# Pure safety policy and demand gate remain present and Android-independent.
require_fixed "$POLICY" 'static String directTlcBlockReason('
require_fixed "$GATE" 'final class ApolloCanBusDemandGate'

echo "PASS: Apollo is Native-only; legacy hook absent; Android 11 guards intact"
