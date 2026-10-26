#!/bin/sh
# Compatibility entry point for the unified release builder.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$ROOT/make_release.sh" "$@"
