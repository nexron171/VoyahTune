#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SERVICE="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
RECEIVER="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/SavedConfigSyncReceiver.java"
SYNC="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/SplitConfigSync.java"
ADVANCE="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
NATIVE_CONFIG="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesConfigReceiver.java"
NATIVE_BRIDGE="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesReceiverDynamic.java"
MANIFEST="$ROOT/RestoreMode/app/src/main/AndroidManifest.xml"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

grep -q 'ru.big.town.restoremode.SYNC_SAVED_CONFIG' "$SERVICE" || fail "Native sync action missing"
grep -q 'ru.big.town.restoremode.SavedConfigSyncReceiver' "$SERVICE" || fail "explicit receiver missing"
grep -q 'Intent.FLAG_INCLUDE_STOPPED_PACKAGES' "$SERVICE" || fail "stopped RestoreMode is not included"
grep -q 'requestSavedConfigSync("service start")' "$SERVICE" || fail "startup sync missing"

awk '
    /private void runWakeSideEffects\(String source\)/ { in_wake = 1 }
    in_wake && /if \(beginWakeSession\(\)\)/ { coalesced = 1 }
    in_wake && coalesced && /requestSavedConfigSync\("physical wake"\)/ { found = 1 }
    in_wake && /^    }/ { exit(found ? 0 : 1) }
    END { if (!found) exit 1 }
' "$SERVICE" || fail "wake sync is not inside beginWakeSession"

grep -q 'DrivePreferences' "$RECEIVER" || fail "receiver does not read persisted preferences"
grep -q 'SplitConfigSync.pushAll(context, prefs)' "$RECEIVER" || fail "receiver does not publish Dock and steering"
grep -q 'pushAppDpi(context, prefs, null, 0)' "$SYNC" || fail "startup/wake sync omits complete app DPI snapshot"
grep -q 'ru.big.town.anative.APP_DPI_CONFIG' "$SYNC" || fail "app DPI config action missing"
grep -q 'appDpiJson' "$SYNC" || fail "authoritative app DPI JSON is not published"
grep -q 'SplitConfigSync.pushAppDpi(AdvanceActivity.this, prefs, fpkg, dpi)' "$ADVANCE" \
    || fail "DPI changes are not published immediately"
grep -q 'mirrorAppDpi(context, intent)' "$NATIVE_CONFIG" || fail "Native does not receive app DPI config"
grep -q 'voyahtune_dpi_packages' "$NATIVE_BRIDGE" || fail "removed/Auto DPI values cannot be cleared"
grep -q 'sendWinReload(ctx)' "$NATIVE_BRIDGE" || fail "launch-time DPI fallback does not reload WM cache"
grep -q 'intent.getComponent() == null' "$RECEIVER" || fail "receiver does not require an explicit intent"

awk '
    /android:name="\.SavedConfigSyncReceiver"/ { found = 1; block = 1 }
    block && /android:exported="true"/ { exported = 1 }
    block && /android:permission="ru.big.town.anative.permission.BIND_SET_MODES_SERVICE"/ { protected_receiver = 1 }
    block && /\/>/ { exit(found && exported && protected_receiver ? 0 : 1) }
    END { if (!(found && exported && protected_receiver)) exit 1 }
' "$MANIFEST" || fail "RestoreMode receiver is not signature-protected"

if grep -Eq 'setInterval|scheduleAtFixedRate|postDelayed\([^,]+,[[:space:]]*[0-9]+\).*SYNC_SAVED_CONFIG' "$SERVICE" "$RECEIVER"; then
    fail "saved configuration sync must not poll"
fi

echo "PASS: saved Dock/steering/app-DPI configuration is applied at startup and coalesced wake"
