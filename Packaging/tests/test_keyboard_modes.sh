#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
UI="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
SYNC="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/SplitConfigSync.java"
LAYOUT="$ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
NATIVE="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesConfigReceiver.java"
LOADER="$ROOT/Packaging/system/load.bin"
FULL_INSTALL="$ROOT/Packaging/installer/full/install.sh"
FULL_REMOVE="$ROOT/Packaging/installer/full/remove.sh"
LIGHT_INSTALL="$ROOT/Packaging/installer/light/install.sh"
LIGHT_REMOVE="$ROOT/Packaging/installer/light/remove.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
require() { grep -qF "$2" "$1" || fail "$1: missing $2"; }

for asset in keyboard_lock_en.js keyboard_ru.js voyahtune_keyboard_en_config.json \
        voyahtune_keyboard_ru_config.json voyahtune_skb_qwerty_ru.json; do
    [ -s "$ROOT/Packaging/inject/$asset" ] || fail "missing keyboard asset: $asset"
done
node --check "$ROOT/Packaging/inject/keyboard_lock_en.js"
node --check "$ROOT/Packaging/inject/keyboard_ru.js"

require "$LAYOUT" 'android:id="@+id/switchKeyboardEnglish"'
require "$LAYOUT" 'android:id="@+id/switchKeyboardRussian"'
require "$UI" 'prefs.getString("keyboardMode", "off")'
require "$UI" 'if (checked) switchKeyboardRussian.setChecked(false);'
require "$UI" 'if (checked) switchKeyboardEnglish.setChecked(false);'
require "$SYNC" 'pushKeyboard(context, prefs);'
require "$SYNC" 'configIntent("ru.big.town.anative.KEYBOARD_CONFIG")'

require "$NATIVE" 'Settings.Global.putString('
require "$NATIVE" '"voyahtune_keyboard_mode", mode'
require "$NATIVE" 'forceStopPackage.invoke(am, "com.qinggan.app.qgime")'
require "$NATIVE" 'return "en".equals(mode) || "ru".equals(mode) ? mode : "off";'

# The 10-second watchdog may discover qgime, but Settings is read only behind the exact-identity
# transition. Exactly one source call prevents accidental per-cycle duplicate reads.
[ "$(grep -F -c 'settings get global "$KEYBOARD_SETTING"' "$LOADER")" -eq 1 ] \
    || fail "loader must contain exactly one keyboard Settings read"
require "$LOADER" '[ "$KEYBOARD_SEEN_ID" != "$KEYBOARD_ID" ]'
require "$LOADER" 'KEYBOARD_SEEN_ID=$KEYBOARD_ID'
require "$LOADER" 'en) KEYBOARD_SCRIPT=$KEYBOARD_EN'
require "$LOADER" 'ru) KEYBOARD_SCRIPT=$KEYBOARD_RU'
require "$LOADER" 'KEYBOARD_SCRIPT='
require "$LOADER" 'inject_ret "$KEYBOARD_PID" "$KEYBOARD_SCRIPT"'

for installer in "$FULL_REMOVE" "$LIGHT_INSTALL"; do
    require "$installer" 'com.qinggan.app.qgime'
done
for installer in "$FULL_REMOVE" "$LIGHT_INSTALL" "$LIGHT_REMOVE"; do
    require "$installer" 'voyahtune_keyboard_mode'
done
require "$FULL_INSTALL" 'install_required_data_file keyboard_lock_en.js'
require "$FULL_INSTALL" 'install_required_data_file keyboard_ru.js'
require "$FULL_REMOVE" '/data/local/tmp/voyahtune_keyboard.attempt'
require "$LIGHT_INSTALL" '/data/local/bin/keyboard_lock_en.js'

echo "PASS: keyboard modes are opt-in, mutually exclusive, identity-latched and removable"
