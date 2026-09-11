#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
AGENT="$ROOT/Packaging/inject/app_client.js"
LOADER="$ROOT/Packaging/system/load.bin"

fail() { echo "MapKit DPI client contract test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() {
    if grep -Fq -- "$2" "$1"; then fail "$1: forbidden $2"; fi
}

if command -v node > /dev/null 2>&1; then
    node --check "$AGENT"
fi
sh -n "$LOADER"

# Only these public application IDs may turn a per-app DPI selection into a MapKit client attach.
for PACKAGE in \
        ru.yandex.yandexnavi \
        ru.yandex.yandexmaps \
        com.yango.maps.android; do
    require "$AGENT" "packageName === \"$PACKAGE\""
    require "$LOADER" "$PACKAGE"
done

# The renderer stays on its physical Surface. Density is mirrored through MapKit's own absolute
# pixels-per-independent-point value and returns to the captured baseline in Auto mode.
for REQUIRED in \
        'var DEFAULT_DENSITY_DPI = 160;' \
        'com.yandex.mapkit.map.MapWindow' \
        'com.yandex.mapkit.mapview.MapView' \
        'com.yandex.mapkit.internal.MapKitBinding' \
        'DPI_SETTING_PREFIX + packageName' \
        'return dpi / DEFAULT_DENSITY_DPI;' \
        'typedWindow.getScaleFactor()' \
        'typedWindow.setScaleFactor(target);' \
        'mapkitDpi > 0 ? mapkitScaleForDpi(mapkitDpi) : baseline' \
        'MapViewClass.getMapWindow.overload()' \
        'MapKitBinding.createMapWindow.overloads' \
        'MapViewClass.class.isInstance(view)' \
        'ViewGroup.class.isInstance(view)' \
        'WindowManagerGlobal.getInstance().getWindowViews()' \
        'refreshPolicy("WIN_RELOAD")' \
        '[mapkit-dpi] hook ready v1'; do
    require "$AGENT" "$REQUIRED"
done
forbid "$AGENT" 'SurfaceControl'
forbid "$AGENT" 'setFixedSize('
forbid "$AGENT" 'setZoom('

# Loader takes MapKit packages from the DPI snapshot, ignores every other DPI-only package, and
# requires the MapKit marker before recording a successful exact-process injection.
for REQUIRED in \
        'APP_CLIENT_DPI_SETTING=voyahtune_dpi_packages' \
        'FC_DPI_LIST=$(timeout -k 1 1 settings get global "$APP_CLIENT_DPI_SETTING"' \
        'for FC_DPI_PACKAGE in $FC_DPI_LIST' \
        'FC_REQUIRE_MAPKIT=0' \
        '*",$FC_PACKAGE,"*) FC_REQUIRE_MAPKIT=1' \
        'FC_TARGET_REQUIRE_MAPKIT=${10}' \
        'grep -qF "$MAPKIT_DPI_CLIENT_READY" "$FC_TRY"' \
        '"$FC_REQUIRE_MAPKIT"'; do
    require "$LOADER" "$REQUIRED"
done

inject_function=$(awk '
    /^inject_app_client_bg\(\) \{/ { capture = 1 }
    /^discover_app_client\(\) \{/ { capture = 0 }
    capture { print }
' "$LOADER")
mapkit_ready_line=$(printf '%s\n' "$inject_function" \
    | grep -nF 'grep -qF "$MAPKIT_DPI_CLIENT_READY"' | cut -d: -f1)
mark_line=$(printf '%s\n' "$inject_function" \
    | grep -nF 'mv -f "$FC_MARK_TMP" "$FC_TARGET_MARK"' | cut -d: -f1)
[ "$mapkit_ready_line" -lt "$mark_line" ] \
    || fail "active marker precedes mandatory MapKit readiness"

echo "MapKit DPI client contract: OK"
