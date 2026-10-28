#!/bin/sh
set -eu
SOURCE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$SOURCE/scripts/build_runtime.py"
