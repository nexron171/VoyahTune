#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HOST="$ROOT/Native/app/src/main/java/ru/big/town/anative/SplitHostActivity.java"
RESTORER="$ROOT/Native/app/src/main/java/ru/big/town/anative/ScreenLiftTaskRestorer.java"
SERVICE="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
MANIFEST="$ROOT/Native/app/src/main/AndroidManifest.xml"
VD="$ROOT/Packaging/inject/vd_bypass.js"
DOCK="$ROOT/Packaging/inject/launcherdock.js"

fail() {
    echo "screen-lift resize/restore contract test failed: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq "$2" "$1" || fail "$1 does not contain: $2"
}

node --check "$VD"
node --check "$DOCK"

# The provisional compact viewport is one shared contract across the VD host, physical WM frames
# and the launcher dock. Keep 720 as the raised baseline until a head-unit measurement says otherwise.
require_fixed "$HOST" 'private static final int SCREEN_DOWN_HEIGHT_PX = 560;'
require_fixed "$HOST" 'private static final int SCREEN_UP_HEIGHT_PX = 720;'
require_fixed "$VD" 'compactBottom: 560'
require_fixed "$VD" 'voyahtune_win_compact_bottom", 560'
require_fixed "$DOCK" 'setDockViewHeight(up, compact ? 560 : 720, "screenUp");'
require_fixed "$DOCK" 'setDockViewHeight(group, compact ? 560 : -1, "radioGroup");'

# A new host reads the real lift state before any SurfaceView/VirtualDisplay is created. An existing
# host receives the completion event and lets the ordinary Surface lifecycle perform the actual VD resize.
read_line=$(grep -nF 'applyScreenLiftSize(readScreenLiftType());' "$HOST" | head -1 | cut -d: -f1)
surface_line=$(grep -nF 'setupSurface(left);' "$HOST" | head -1 | cut -d: -f1)
[ "$read_line" -lt "$surface_line" ] \
    || fail "new split reads the lift state after creating its Surface/VD"
require_fixed "$HOST" 'private static final String ACTION_SCREEN_LIFT_CHANGED = "action.qg.layout.changed";'
require_fixed "$HOST" 'int actualType = readScreenLiftProperty(type);'
require_fixed "$HOST" 'if (actualType != type) {'
require_fixed "$HOST" 'applyScreenLiftSize(type);'
require_fixed "$HOST" 'pane.vd.resize(width, height, effectiveDpi(pane));'
require_fixed "$HOST" 'unregisterReceiver(screenLiftReceiver);'

# Physical apps are resized through a normal WMS traversal; resolved/source-display bounds must not
# be copied during reparent.
require_fixed "$VD" 'requestFreeformTraversalOnce("screen lift type=" + FF.liftType);'
require_fixed "$VD" 'requestFreeformTraversalOnce("physical reparent pkg=" + pkg'
if grep -Eq 'task\.setBounds|task\.setAppBounds|\.mSizeCompatBounds' "$VD"; then
    fail "screen-lift/reparent path pins stale task bounds"
fi

# Preserve both physical foreground tasks across the OEM shell transition. Reuse the task id first
# (navigation stack intact), and never steal focus from a different third-party app opened meanwhile.
require_fixed "$RESTORER" 'filter.addAction(ACTION_START);'
require_fixed "$RESTORER" 'filter.addAction(ACTION_CHANGED);'
require_fixed "$RESTORER" 'if ((displayId != 0 && displayId != 1)'
require_fixed "$RESTORER" 'new SavedTask(task.taskId, displayId, top.getPackageName(), top)'
require_fixed "$RESTORER" 'activityManager.moveTaskToFront(saved.taskId, 0);'
require_fixed "$RESTORER" 'if (current != null && !LAUNCHER_PKG.equals(current.getPackageName())) {'
require_fixed "$RESTORER" 'options.setLaunchDisplayId(saved.displayId);'
require_fixed "$RESTORER" 'int actualType = readLiftProperty(type);'
require_fixed "$RESTORER" 'if (actualType != type) {'
require_fixed "$SERVICE" 'screenLiftTaskRestorer = new ScreenLiftTaskRestorer(getApplicationContext());'
require_fixed "$SERVICE" 'if (BuildConfig.IS_FULL) {'
require_fixed "$SERVICE" 'if (liftRestorer != null) liftRestorer.close();'
require_fixed "$MANIFEST" '<uses-permission android:name="android.permission.REORDER_TASKS" />'

# Compact dock keeps the regular bar and only hides the two surplus OEM entries: Home, overrides 1/2
# and All Apps remain addressable. Passenger long-click listeners are explicitly cleared.
require_fixed "$DOCK" 'setDockViewVisibility(down, 8, "screenDown");'
require_fixed "$DOCK" 'setDockViewVisibility(home, 0, "home");'
require_fixed "$DOCK" 'setDockViewVisibility(item1, 0, "slot1");'
require_fixed "$DOCK" 'setDockViewVisibility(item2, 0, "slot2");'
require_fixed "$DOCK" 'setDockViewVisibility(allApps, 0, "allApps");'
require_fixed "$DOCK" 'setDockViewVisibility(item3, compact ? 8 : 0, "slot3");'
require_fixed "$DOCK" 'setDockViewVisibility(item4, compact ? 8 : 0, "slot4");'
require_fixed "$DOCK" 'if (viewId === this.mScreenUpHomeView.value.getId()) {'
require_fixed "$DOCK" 'if (viewId === this.mScreenUpAllAppView.value.getId()) {'
require_fixed "$DOCK" 'av.setOnLongClickListener(null);'
require_fixed "$DOCK" 'sv1.setOnLongClickListener(null);'
require_fixed "$DOCK" 'sv2.setOnLongClickListener(null);'

echo "screen-lift resize/restore contract test: OK"
