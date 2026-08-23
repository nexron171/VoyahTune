#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/BatteryHeatService.java"
POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/BatteryHeatAutoPolicy.java"
GATE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/BatteryHeatRefreshGate.java"
SET_MODES="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
ADVANCE="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"
}

first_line() {
    LINE=$(grep -Fn -- "$2" "$1" | sed -n '1s/:.*//p')
    [ -n "$LINE" ] || fail "$1 does not contain: $2"
    echo "$LINE"
}

assert_before() {
    BEFORE=$(first_line "$1" "$2")
    AFTER=$(first_line "$1" "$3")
    [ "$BEFORE" -lt "$AFTER" ] || fail "$2 must appear before $3 in $1"
}

# No permanent cadence or delayed Settings retry remains. The other delayed operations are finite:
# one connection snapshot, one UI broadcast coalescer and ApplyEngine's existing command settle.
for FORBIDDEN in POLL_MS FULL_QUERY_MIN_INTERVAL_MS lastFullQueryAttemptElapsed \
        pollRunnable SETTINGS_RETRY_MS settingsRetryRunnable scheduleSettingsRetry \
        'refreshGate.retry()' '"poll"'; do
    if grep -Fq "$FORBIDDEN" "$SERVICE"; then
        fail "BatteryHeatService must remain event-driven: $FORBIDDEN"
    fi
done
if grep -Fq 'synchronized Request retry()' "$GATE"; then
    fail "rejected Settings work must wait for the next real event, not a retry timer"
fi

FORCE_QUERY_POSTS=$(grep -F -c \
    'handler.postDelayed(forceQueryRunnable, FORCE_QUERY_MS);' "$SERVICE")
[ "$FORCE_QUERY_POSTS" -eq 1 ] \
    || fail "BatteryHeatService must schedule one named +6s snapshot per connection path"
require_fixed "$SERVICE" 'case CONNECTION:'
require_fixed "$SERVICE" 'handler.removeCallbacks(forceQueryRunnable);'
require_fixed "$SERVICE" \
    'if (destroyed || canBusEventHub == null || !isVehicleSnapshotIncomplete()) return;'

# onStart owns startup/physical-wake provider reconciliation. Monitoring itself only subscribes
# and publishes cached state; an unknown setting may get one more read on a real temperature event.
require_fixed "$SERVICE" 'ACTION_STARTUP_SETTINGS_REFRESH'
require_fixed "$SERVICE" 'ACTION_PHYSICAL_WAKE_SETTINGS_REFRESH'
require_fixed "$SERVICE" 'postSettingsRefresh("startup");'
require_fixed "$SERVICE" 'postSettingsRefresh("physical-wake");'
require_fixed "$SERVICE" 'startupRefreshRequested = true;'
require_fixed "$SERVICE" 'requestBroadcastUpdate();'
require_fixed "$SET_MODES" 'BatteryHeatService.requestStartup(this);'
require_fixed "$SET_MODES" 'BatteryHeatService.requestPhysicalWake(this);'
WAKE_FUNCTION=$(awk '
    /private void runWakeSideEffects\(String source\)/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SET_MODES")
for REQUIRED in 'if (beginWakeSession()) {' \
        'BatteryHeatService.requestPhysicalWake(this);'; do
    printf '%s\n' "$WAKE_FUNCTION" | grep -Fq "$REQUIRED" \
        || fail "physical wake wiring lacks: $REQUIRED"
done
WAKE_REQUESTS=$(grep -F -c 'BatteryHeatService.requestPhysicalWake(this);' "$SET_MODES")
[ "$WAKE_REQUESTS" -eq 1 ] || fail "physical wake request must have one call site"

# The only preference writer sends an exact package-targeted bool after SharedPreferences.apply().
require_fixed "$ADVANCE" 'new Intent(ACTION_BATTERY_HEAT_AUTO_CHANGED)'
require_fixed "$ADVANCE" '.setPackage(NATIVE_PACKAGE)'
require_fixed "$ADVANCE" '.putExtra(EXTRA_BATTERY_HEAT_AUTO_ENABLED, checked);'
assert_before "$ADVANCE" \
    'prefs.edit().putBoolean("batteryHeatAuto", checked).apply();' \
    'Intent changed = new Intent(ACTION_BATTERY_HEAT_AUTO_CHANGED)'

# Every inbound request/toggle is protected by the existing signature permission. UI snapshot
# requests stay cached-only, while AUTO_CHANGED directly invalidates provider/decision revisions.
require_fixed "$SERVICE" \
    'registerReceiver(uiReceiver, filter, BIND_PERMISSION, handler, RECEIVER_EXPORTED);'
require_fixed "$SERVICE" 'filter.addAction(ACTION_BATTERY_HEAT_AUTO_CHANGED);'
UI_RECEIVER=$(awk '
    /private final BroadcastReceiver uiReceiver = new BroadcastReceiver\(\)/ { capture = 1 }
    capture { print }
    capture && /^    };$/ { exit }
' "$SERVICE")
if printf '%s\n' "$UI_RECEIVER" | grep -Fq 'requestSettingsRefresh'; then
    fail "Battery heat UI receiver must never synchronously/implicitly query Settings"
fi
for REQUIRED in 'ACTION_REQUEST_BATTERY_HEAT.equals(a)' \
        'requestBroadcastUpdate();' 'ACTION_BATTERY_HEAT_AUTO_CHANGED.equals(a)' \
        'applyAutoSettingChange'; do
    printf '%s\n' "$UI_RECEIVER" | grep -Fq "$REQUIRED" \
        || fail "Battery heat receiver lacks cached/event path: $REQUIRED"
done
require_fixed "$SERVICE" \
    'BatteryHeatAutoPolicy.settingRefreshNeededForTemperature(autoSettingKnown)'
require_fixed "$POLICY" 'return !settingKnown;'
require_fixed "$SERVICE" 'if (!autoSettingKnown || cachedAutoEnabled != enabled) {'
require_fixed "$SERVICE" 'autoSettingKnown = true;'

# Provider failures are nullable and cache-preserving. Revision is captured at submit, checked
# before Binder query and again after it; every stale completion still advances the one+one gate.
require_fixed "$SERVICE" 'private volatile long autoSettingRevision;'
require_fixed "$SERVICE" 'private volatile long autoDecisionGeneration;'
require_fixed "$SERVICE" 'final long submittedSettingRevision = autoSettingRevision;'
require_fixed "$SERVICE" 'Boolean enabled = queryAutoEnabled(resolver);'
require_fixed "$SERVICE" 'private static Boolean queryAutoEnabled(ContentResolver resolver) {'
require_fixed "$SERVICE" 'return null;'
assert_before "$SERVICE" \
    'submittedSettingRevision, beforeQuery.autoSettingRevision' \
    'Boolean enabled = queryAutoEnabled(resolver);'
assert_before "$SERVICE" \
    'BatteryHeatRefreshGate.Completion completion = refreshGate.finish(request);' \
    'submittedSettingRevision, autoSettingRevision'
REJECTS=$(grep -F -c 'refreshGate.reject(request);' "$SERVICE")
[ "$REJECTS" -eq 1 ] || fail "only executor-submit rejection may retain Settings work"

# Automatic work composes a nested decision guard with ApplyEngine's wake guard. Manual activation
# remains an independent direct user command. The pure policy covers every mutable send condition.
require_fixed "$SERVICE" 'private boolean automaticActivationCurrent('
require_fixed "$SERVICE" 'return CanSender.runGuardedSend('
require_fixed "$SERVICE" 'attemptedAt.compareAndSet('
require_fixed "$SERVICE" 'if (attemptedAt > 0L) {'
require_fixed "$SERVICE" 'ApplyEngine.postIndependentUserCommand("battery heat " + reason'
require_fixed "$SERVICE" 'maybeAutoActivate("stale-completion-handoff");'
require_fixed "$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/CanSender.java" \
    'private static final ThreadLocal<Runnable> FRAME_ATTEMPT'
require_fixed "$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/CanSender.java" \
    'notifyFrameAttempt();'
for REQUIRED in activeInstance expectedCanBusEpoch ambientTemperatureEpoch \
        expectedDecisionGeneration autoEnabled temperatureKnown temperatureCold \
        controlAlreadyActive; do
    require_fixed "$POLICY" "$REQUIRED"
done

echo "PASS: BatteryHeatService uses bounded event-driven refresh and guarded automatic CAN"
