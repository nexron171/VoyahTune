#!/bin/sh
set -eu
REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SRC="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative"
ENGINE="$SRC/ApplyEngine.java"
PROVIDER="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/DriveSelectionPreferences.java"
HOOK="$REPO_ROOT/Packaging/od/inject/voyahtune_acc_restore.js"
# OD starts the automatic Native pass only from ACC; PI retains door/gear restoration.
! grep -Rq 'ApplyEngine.scheduleApply(' "$SRC"
grep -Fq 'if (!accHooks)' "$SRC/VehicleStateControllers.java"
grep -Fq 'if (!InfrastructureProfile.read(context).usesAccHooks()) return;' "$ENGINE"
grep -Fq 'driverDoorStateController.accept(' "$SRC/VehicleStateControllers.java"
grep -Fq 'gearStateController.accept(event.first)' "$SRC/VehicleStateControllers.java"
grep -Fq 'ApplyEngine.scheduleAccApply(this)' "$SRC/SetModesService.java"
grep -Fq '"claimSettings"' "$ENGINE" "$PROVIDER"
grep -Fq '"completeSettings"' "$ENGINE" "$PROVIDER"
grep -Fq '"dispatchSettings"' "$HOOK"
# Automatic pass excludes drive/energy, which are handled by the OEM-origin hook.
grep -Fq 'MainActivity.createCanRestorePlan(manual || !usesAccHooks())' "$ENGINE"
grep -Fq 'if (includeModes && driveEnabled)' "$SRC/MainActivity.java"
grep -Fq 'includeModes && energyEnabled, energy, includeModes && forcedEv' "$SRC/MainActivity.java"
[ -s "$SRC/EarlyDriveModeRestore.java" ]
[ -s "$SRC/ModeRestoreTriggers.java" ]
python3 "$REPO_ROOT/Packaging/tests/test_acc_preferences.py"
echo "PASS: OD ACC-cycle settings pass and PI native door/Drive restore are separated"
