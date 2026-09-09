#!/bin/sh
# Native x86-64 AppDir is embedded below. No FUSE or separate ADB installation.
set -eu
# ADB and the full release payload are inside the extracted AppDir.
case "$(uname -m)" in
  x86_64|amd64) architecture=x86_64 ;;
  *) printf '%s\n' 'VoyahTune: требуется x86-64 Linux (Intel/AMD).' >&2; exit 1 ;;
esac
archive_line=$(awk '/^__VOYAHTUNE_ARCHIVE__$/ {print NR+1; exit}' "$0")
if [ "${1:-}" = --extract ]; then
  [ "$#" = 2 ] || { echo 'Usage: installer.run --extract NEW_DIRECTORY' >&2; exit 2; }
  mkdir "$2"
  tail -n +"$archive_line" "$0" | tar -xz -C "$2"
  printf 'Распаковано: %s\n' "$2"
  exit 0
fi
work=$(mktemp -d "${TMPDIR:-/tmp}/voyahtune.XXXXXXXX")
trap 'rm -rf "$work"' EXIT
printf '%s\n' 'Подготовка VoyahTune…' >&2
tail -n +"$archive_line" "$0" | tar -xz -C "$work" "$architecture"
app="$work/$architecture"
export APPDIR="$app"
if [ "${1:-}" = --cli ]; then
  shift
  "$app/usr/bin/voyahtune" --bundle "$app/usr/share/voyahtune-installer/bundle" "$@"
else
  "$app/AppRun" "$@"
fi
exit $?
__VOYAHTUNE_ARCHIVE__
