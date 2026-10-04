#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
node --check "$ROOT/Packaging/od/inject/voyahtune_drive_reset.js"
node "$ROOT/Packaging/tests/test_drive_reset_hook.js"

node "$ROOT/Packaging/tests/test_vehicle_drive_hook.js"
