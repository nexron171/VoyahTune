#!/bin/sh
# Build the unified payload, with optional desktop installers.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case " $* " in
    *" --installers "*|*" --mac "*|*" --windows "*|*" --windows-arch "*|*" --linux "*|*" --payload "*)
        exec python3 "$ROOT/Installer/scripts/release.py" "$@" ;;
    *) exec python3 "$ROOT/Installer/scripts/release.py" "$@" --payload ;;
esac
