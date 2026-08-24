#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/LightSensorService.java"
TRANSPORT="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/HeadlightCanTransport.java"
SET_MODES="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
RETRY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/EventRetryBudget.java"
STATUS_POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/LightStatusEventPolicy.java"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"
}

# Incoming CAN callbacks may not create steady settings/retry cadence. The single internal
# safety watchdog is intentional: its fixed cadence is independent of CAN traffic and restores
# the target if BCM/user returns the headlights to Auto without a usable delta callback.
for FORBIDDEN in poll-retry settingsRetryRunnable scheduleSettingsRetry \
        'settingsRequestGate.retry()' '"poll"'; do
    if grep -Fq -- "$FORBIDDEN" "$SERVICE"; then
        fail "LightSensorService must not amplify CAN events into polling: $FORBIDDEN"
    fi
done
require_fixed "$SERVICE" 'SAFETY_POLL_MS = 30_000L'
require_fixed "$SERVICE" 'SAFETY_POLL_INITIAL_DELAY_MS = 2_000L'
require_fixed "$SERVICE" 'timerHandler.postDelayed(safetyRunnable, SAFETY_POLL_INITIAL_DELAY_MS);'
require_fixed "$SERVICE" 'timerHandler.postDelayed(this, SAFETY_POLL_MS);'
require_fixed "$SERVICE" 'timerHandler.removeCallbacks(safetyRunnable);'
require_fixed "$SERVICE" 'commit(watchdogTarget, "safety-watchdog reassert"'
require_fixed "$SERVICE" 'watchdogTarget = headlightsOn;'
require_fixed "$SERVICE" 'requestSensorLevelOnIo(null);'
[ "$(grep -F -c 'timerHandler.postDelayed(this, SAFETY_POLL_MS);' "$SERVICE")" -eq 1 ] \
    || fail "safety watchdog must have exactly one self-rearm site"
CAN_EVENT_BLOCK=$(awk '
    /private void onCanBusEvent\(CanBusEvent event\)/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SERVICE")
if printf '%s\n' "$CAN_EVENT_BLOCK" | grep -Fq 'safetyRunnable'; then
    fail "incoming CAN events must not schedule the safety watchdog"
fi
SAFETY_BLOCK=$(awk '
    /private void runSafetyWatchdog\(\)/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SERVICE")
for REQUIRED in externalOffOverrideActive hasManualOwnershipFence \
        MANUAL_AUTO_GATE.blocksAntiAuto hasCurrentCommitForTarget \
        'requestCarSignalMaintenance(true);' 'HeadlightCanTransport.requestRecovery(this);'; do
    printf '%s\n' "$SAFETY_BLOCK" | grep -Fq "$REQUIRED" \
        || fail "light safety watchdog lacks guard/recovery: $REQUIRED"
done
require_fixed "$SERVICE" 'MAX_BIND_RETRIES_PER_EVENT = 2'
require_fixed "$SERVICE" 'MAX_REGISTRATION_RETRIES_PER_EPOCH = 1'
require_fixed "$SERVICE" 'MAX_SENSOR_QUERY_RETRIES_PER_REQUEST = 1'
require_fixed "$SERVICE" 'MAX_COMMIT_RETRIES_PER_DECISION = 1'
require_fixed "$RETRY" 'boolean claim(long candidateScope)'
require_fixed "$RETRY" 'claimed >= maximum'
require_fixed "$SERVICE" 'waiting for reconnect/wake event'
require_fixed "$SERVICE" 'waiting for next real light event'
require_fixed "$SERVICE" 'waiting for next real light/wake event'

# The initial snapshot remains event-scoped. Unknown outdoor state reads thresholds first and then
# exactly one fresh TX36; executor rejection is retained until another actual event.
require_fixed "$SERVICE" 'Read thresholds first, then take exactly one fresh TX36 sample.'
require_fixed "$SERVICE" 'settingsRequestGate.reject(request);'
require_fixed "$SERVICE" 'scheduleSensorQueryRetryOnIo(run.apply);'
require_fixed "$SERVICE" 'requestSensorLevelOnIo(null);'
require_fixed "$SERVICE" 'retryPendingCallbackCleanups();'
require_fixed "$SERVICE" 'unregisterCallback deferred until next reconnect/wake event'
require_fixed "$SERVICE" 'callbackCleanupWaitingForEvent = true;'
require_fixed "$SERVICE" 'callbackCleanupRetryRequested = true;'
require_fixed "$SERVICE" 'retryAfterRunningFailure'
require_fixed "$SERVICE" 'timerHandler.post(() -> markCarSignalReadyOnMain(request.epoch));'
require_fixed "$SERVICE" 'TX46 is delta-only.'
if grep -Fq 'forceInitCompleted = applyTargetWithSensorLevel' "$SERVICE"; then
    fail "force-init may complete only after a terminal successful frame"
fi
require_fixed "$SERVICE" 'if (lastGear != GEAR_DRIVE) return;'
require_fixed "$SERVICE" 'cancelAutomaticWorkForExternalOff();'
require_fixed "$SERVICE" 'if (pending != null) completeSensorApplyOnMain(pending);'
GEAR_BLOCK=$(awk '
    /private void onGear\(CanBusEvent event\)/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SERVICE")
if printf '%s\n' "$GEAR_BLOCK" | grep -Fq 'invalidateAutomaticCommits'; then
    fail "gear changes must invalidate only Drive-derived work, not independent RSM decisions"
fi

# Physical wake is explicit, package-local and coalesced by SetModes' existing wake session.
require_fixed "$SERVICE" 'ACTION_PHYSICAL_WAKE'
require_fixed "$SERVICE" 'context.startForegroundService(intent);'
require_fixed "$SERVICE" 'CanBusEventRouter.INTEREST_CONNECTION'
require_fixed "$SERVICE" \
    'ContextCompat.registerReceiver(this, requestReceiver, reqFilter, BIND_PERMISSION, null,'
require_fixed "$SERVICE" 'ContextCompat.RECEIVER_EXPORTED);'
WAKE_BLOCK=$(awk '
    /private void runWakeSideEffects\(String source\)/ { capture = 1 }
    capture { print }
    capture && /^    }$/ { exit }
' "$SET_MODES")
printf '%s\n' "$WAKE_BLOCK" | grep -Fq 'if (beginWakeSession()) {' \
    || fail "Light wake must stay inside beginWakeSession"
printf '%s\n' "$WAKE_BLOCK" | grep -Fq 'prefs().getBoolean("autoLight", false)' \
    || fail "Light wake must be gated by the persisted option"
printf '%s\n' "$WAKE_BLOCK" | grep -Fq 'LightSensorService.requestPhysicalWake(this);' \
    || fail "SetModes wake path must notify LightSensorService"
[ "$(grep -F -c 'LightSensorService.requestPhysicalWake(this);' "$SET_MODES")" -eq 1 ] \
    || fail "physical light wake must have exactly one SetModes call site"

# A queued automatic decision is fenced at the actual TX58 boundary. State/timestamps are not
# optimistically committed before the terminal result, and retry preserves the original manual token.
require_fixed "$SERVICE" 'activeCommitSequence != request.sequence'
require_fixed "$SERVICE" 'lightDecisionRevision != request.decisionRevision'
require_fixed "$SERVICE" 'boolean driveOnly = request.requiresDrive;'
require_fixed "$SERVICE" 'driveDecisionRevision != capturedDriveRevision'
require_fixed "$SERVICE" 'request.requiresDrive && request.driveRevision == capturedDriveRevision'
require_fixed "$SERVICE" 'MANUAL_AUTO_GATE.isAutomaticActionCurrent(request.automaticToken)'
require_fixed "$SERVICE" 'MANUAL_AUTO_GATE.isRevisionCurrent(request.manualRevision)'
require_fixed "$SERVICE" 'reassertKnownTargetPending |= everSent && isAutomaticControlOwned();'
require_fixed "$SERVICE" 'MANUAL_AUTO_GATE.isAutomaticActionCurrent(automaticOwnershipToken)'
require_fixed "$SERVICE" 'event.elapsedRealtime <= lastManualIntentFenceElapsedRealtime'
require_fixed "$SERVICE" 'lastManualIntentFenceElapsedRealtime = SystemClock.elapsedRealtime();'
require_fixed "$SERVICE" 'externalOffOverrideActive'
require_fixed "$SERVICE" 'LightStatusEventPolicy.classifyExternalOff('
require_fixed "$STATUS_POLICY" 'eventElapsedRealtime <= decisionStartedElapsedRealtime'
require_fixed "$STATUS_POLICY" 'if (eventProtectedBeforeFrame) return Decision.IGNORE;'
require_fixed "$STATUS_POLICY" 'return frameAttemptTargetOn ? Decision.DEFER : Decision.IGNORE;'
require_fixed "$STATUS_POLICY" \
    'long sinceAttempt = eventElapsedRealtime - frameAttemptElapsedRealtime;'
require_fixed "$STATUS_POLICY" 'if (sinceAttempt >= commandEchoGuardMs) return Decision.CONFIRM;'
require_fixed "$SERVICE" 'CanSender.runGuardedSend('
require_fixed "$SERVICE" 'recordCommitFrameAttempt('
require_fixed "$SERVICE" 'long decisionStartedElapsed) {'
require_fixed "$SERVICE" '0L, event.elapsedRealtime);'
require_fixed "$SERVICE" 'current.preFrameOffProtectionStartedElapsed'
require_fixed "$SERVICE" 'ACTION_RESUME_AUTO'
require_fixed "$SERVICE" 'MANUAL_AUTO_GATE.hasPendingManualCommands()'
require_fixed "$SERVICE" 'LightSensorService::onManualCommandClosed'
require_fixed "$SERVICE" 'PENDING_AUTO_RESUME_GENERATION.compareAndSet(generation, 0L)'
require_fixed "$SERVICE" 'lastManualIntentFenceElapsedRealtime = ingressElapsedRealtime;'
require_fixed "$SERVICE" 'ApplyEngine.capturePhysicalWakeGeneration()'
require_fixed "$SERVICE" 'current.physicalWakeGeneration'
require_fixed "$SERVICE" 'request.physicalWakeGeneration'
if grep -Fq 'request.carSignalEpoch' "$SERVICE"; then
    fail "formed headlight commits must not depend on fallback CarSignal lifetime"
fi
require_fixed "$SERVICE" 'ApplyEngine.isPhysicalWakeGenerationActive(candidate.wakeGeneration)'
require_fixed "$SERVICE" 'externalOffAdjudicationRunnable'
require_fixed "$SERVICE" 'timerHandler.postDelayed(externalOffAdjudicationRunnable, remaining);'
require_fixed "$SERVICE" 'request.frameAttemptIdentity = attemptIdentity;'
require_fixed "$SERVICE" 'lastSuccessfulCommit = request;'
require_fixed "$SERVICE" 'candidate.frameAttemptIdentity != request.frameAttemptIdentity'
require_fixed "$SERVICE" 'finishExternalOffAdjudicationForCommit(request, result);'
require_fixed "$SERVICE" 'clearExternalOffForNewFrameAttemptOnMain('
require_fixed "$SERVICE" 'if (pendingExternalOff != null) {'
require_fixed "$SERVICE" 'frameOwner.frameAttemptedElapsed + HEADLIGHT_GUARD_MS'
require_fixed "$SERVICE" 'auto-light retry waits for provisional external OFF adjudication'
require_fixed "$SERVICE" 'sendCompensatingExternalOff();'
require_fixed "$SERVICE" 'subscription.forgetLightStatus();'
require_fixed "$SET_MODES" 'startLightSensorService(true);'
require_fixed "$SET_MODES" 'startLightSensorService(false);'
require_fixed "$SET_MODES" 'LightSensorService.cancelExplicitAutoResume();'
require_fixed "$SERVICE" 'lastFrameAttemptElapsed = now'
require_fixed "$SERVICE" 'if (current && result == ApplyEngine.WakeActionResult.SUCCESS) {'
require_fixed "$SERVICE" 'request.retriesUsed++'
require_fixed "$SERVICE" 'HeadlightCanTransport.awaitReadyAfterFailure(this);'
require_fixed "$TRANSPORT" 'CanSender.beginFrameAttemptForCurrentGuard()'
require_fixed "$TRANSPORT" 'MAX_REBIND_RETRIES_PER_EVENT = 1'
require_fixed "$TRANSPORT" 'rebindRetryBudget.claim(scope)'
require_fixed "$TRANSPORT" 'mainHandler.postDelayed(stuckBindRunnable, Math.max(1L, delayMs));'
require_fixed "$TRANSPORT" 'private final class BindConnection implements ServiceConnection'
require_fixed "$TRANSPORT" 'activeBindingGeneration == generation'
require_fixed "$TRANSPORT" 'if (alreadyRecovering) return;'
require_fixed "$TRANSPORT" 'CanBus bind retry exhausted; waiting for next wake/light event'
NULL_BIND_BLOCK=$(awk '
    /public void onNullBinding\(ComponentName name\)/ { capture = 1 }
    capture { print }
    capture && /^        }$/ { exit }
' "$TRANSPORT")
if printf '%s\n' "$NULL_BIND_BLOCK" | grep -Fq 'openRecoveryScope'; then
    fail "null binding is a failed attempt and must not renew its own retry budget"
fi
if grep -Fq 'commitRetryRunnable' "$SERVICE"; then
    fail "headlight commit retry must wait for transport readiness, not another timer"
fi
if grep -Fq 'postDelayed(this' "$TRANSPORT"; then
    fail "headlight recovery must use bounded named callbacks, not a recurring self-loop"
fi
if grep -Fq 'mainHandler.post(this::bindCanBus)' "$TRANSPORT"; then
    fail "missing/dead TX58 transport must not post the old no-op bind path"
fi

echo "PASS: CAN event handling is bounded; one independent safety watchdog is retained"
