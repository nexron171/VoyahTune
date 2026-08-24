#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ACTIVITY="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
LAYOUT="$ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"

fail() {
    echo "other system metrics test failed: $*" >&2
    exit 1
}

grep -Fq 'android:id="@+id/textRamStatus"' "$LAYOUT" || fail "RAM value is absent from Other"
grep -Fq 'android:id="@+id/textCpuStatus"' "$LAYOUT" || fail "CPU value is absent from Other"
grep -Fq 'SYSTEM_METRICS_INTERVAL_MS = 5_000L' "$ACTIVITY" || fail "metrics interval is not 5 seconds"
grep -Fq 'activityResumed && currentSection == 6' "$ACTIVITY" || fail "poll is not gated by visible Other section"
grep -Fq 'manager.getMemoryInfo(info)' "$ACTIVITY" || fail "RAM does not use ActivityManager.MemoryInfo"
grep -Fq 'new java.io.FileReader("/proc/stat")' "$ACTIVITY" || fail "CPU does not use cumulative system counters"
grep -Fq 'uiHandler.postDelayed(systemMetricsTick, SYSTEM_METRICS_INTERVAL_MS)' "$ACTIVITY" \
    || fail "5-second update is not self-scheduled after a completed visible sample"
grep -Fq 'uiHandler.removeCallbacks(systemMetricsTick)' "$ACTIVITY" || fail "metrics callback is not cancellable"
grep -Fq 'systemMetricsExecutor.shutdownNow()' "$ACTIVITY" || fail "metrics worker survives Activity destruction"

awk '
    /protected void onPause\(\)/ { in_pause = 1 }
    in_pause && /activityResumed = false;/ { stopped = 1 }
    in_pause && /updateSystemMetricsPolling\(\);/ { updated = 1 }
    in_pause && /^    }/ { exit(stopped && updated ? 0 : 1) }
    END { if (!(stopped && updated)) exit 1 }
' "$ACTIVITY" || fail "leaving the Activity does not stop metrics"

echo "PASS: RAM/CPU update every 5s only while Other is visible"
