#!/bin/sh
set -eu
REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative"
ENGINE="$SRC/ApplyEngine.java"
PROVIDER="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/DriveSelectionPreferences.java"
HOOK="$REPO_ROOT/Packaging/inject/voyahtune_acc_restore.js"
# Only an ACC cycle may start the automatic Native pass; door/gear still feed their own controllers.
! grep -Rq 'ApplyEngine.scheduleApply(' "$SRC"
! grep -q 'ModeRestoreTriggers' "$SRC/VehicleStateControllers.java"
grep -Fq 'driverDoorStateController.accept(' "$SRC/VehicleStateControllers.java"
grep -Fq 'gearStateController.accept(event.first)' "$SRC/VehicleStateControllers.java"
grep -Fq 'ApplyEngine.scheduleAccApply(this)' "$SRC/SetModesService.java"
grep -Fq '"claimSettings"' "$ENGINE" "$PROVIDER"
grep -Fq '"completeSettings"' "$ENGINE" "$PROVIDER"
grep -Fq '"dispatchSettings"' "$HOOK"
# Automatic pass excludes drive/energy, which are handled by the OEM-origin hook.
grep -Fq 'MainActivity.createCanRestorePlan(manual)' "$ENGINE"
grep -Fq 'if (includeModes && driveEnabled)' "$SRC/MainActivity.java"
grep -Fq 'includeModes && energyEnabled, energy, includeModes && forcedEv' "$SRC/MainActivity.java"
[ ! -e "$SRC/EarlyDriveModeRestore.java" ]
echo "PASS: one ACC-cycle Native settings pass; no door/Drive restore trigger"
