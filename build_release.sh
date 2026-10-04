#!/bin/sh
# Compatibility entry point; both release builders require an explicit infrastructure.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$ROOT/make_release.sh" "$@"
