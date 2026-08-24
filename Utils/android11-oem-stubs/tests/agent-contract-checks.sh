#!/usr/bin/env bash

set -euo pipefail

readonly HARNESS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT="$(cd "$HARNESS_ROOT/../.." && pwd)"
readonly AGENT_ROOT="$REPOSITORY_ROOT/Packaging/inject"

fail() {
    printf 'agent contract check failed: %s\n' "$*" >&2
    exit 1
}

require_text() {
    local file="$1"
    local text="$2"
    grep -Fq "$text" "$file" || fail "'$text' is missing from $file"
}

require_fixture_text() {
    local relative_file="$1"
    local text="$2"
    require_text "$HARNESS_ROOT/app/src/$relative_file" "$text"
}

for agent in \
    steeringwheelkeys.js \
    launcherdock.js \
    multidisplay.js \
    apollo_tech.js \
    keyboard_lock_en.js \
    keyboard_ru.js; do
    [[ -f "$AGENT_ROOT/$agent" ]] || fail "production agent is missing: $agent"
done

require_text "$AGENT_ROOT/steeringwheelkeys.js" 'com.qinggan.keymanager.service.engine.KeyManagerReader'
require_text "$AGENT_ROOT/steeringwheelkeys.js" 'Reader.onKeyEvent.overload("android.view.KeyEvent")'
require_fixture_text \
    'keymanager/java/com/qinggan/keymanager/service/engine/KeyManagerReader.java' \
    'boolean onKeyEvent(KeyEvent event)'

require_text "$AGENT_ROOT/launcherdock.js" 'com.qinggan.launcher.navigation.NavigationBarMain'
for method in updateTheme initScreenUpViews updateSelectedApp onClick; do
    require_text "$AGENT_ROOT/launcherdock.js" "NavigationBarMain.$method"
done
for fixture_method in \
    'void updateTheme()' \
    'void initScreenUpViews()' \
    'void updateSelectedApp(String packageName, String activityName)' \
    'void onClick(View view)' \
    'void dismiss()'; do
    require_fixture_text \
        'launcher/java/com/qinggan/launcher/navigation/NavigationBarMain.java' \
        "$fixture_method"
done

require_text "$AGENT_ROOT/multidisplay.js" 'com.qinggan.systemservice.multidisplay.MultiDisplayImpl'
require_text "$AGENT_ROOT/multidisplay.js" 'MDI.isWhiteListApp.overloads'
require_fixture_text \
    'systemservice/java/com/qinggan/systemservice/multidisplay/MultiDisplayImpl.java' \
    'boolean isWhiteListApp(String packageName)'

require_text "$AGENT_ROOT/apollo_tech.js" 'com.qinggan.app.vehiclesetting.fragments.driveassistance.adas.BaiduProviderUtil'
for method in doQuerySubscribeInfo doQueryNOALearnInfo; do
    require_text "$AGENT_ROOT/apollo_tech.js" "BaiduProviderUtil.$method"
    require_fixture_text \
        'vehiclesetting/java/com/qinggan/app/vehiclesetting/fragments/driveassistance/adas/BaiduProviderUtil.java' \
        "String $method()"
done

for agent in keyboard_lock_en.js keyboard_ru.js; do
    require_text "$AGENT_ROOT/$agent" 'com.qinggan.app.qgime.InputModeSwitcher'
    require_text "$AGENT_ROOT/$agent" 'com.qinggan.app.qgime.QGInputConfig'
    require_text "$AGENT_ROOT/$agent" 'com.qinggan.app.qgime.SkbPool'
done
require_fixture_text \
    'qgime/java/com/qinggan/app/qgime/InputModeSwitcher.java' \
    'int saveInputMode(int mode)'
require_fixture_text \
    'qgime/java/com/qinggan/app/qgime/QGInputConfig.java' \
    'boolean DISABLE_VOICE'
require_fixture_text \
    'qgime/java/com/qinggan/app/qgime/SkbPool.java' \
    'void resetCachedSkb()'

for ru_class in \
    SoftKey \
    SoftKeyToggle \
    SoftKeyboard \
    QingganIME \
    SkbContainer \
    EnglishInputProcessor \
    XmlKeyboardLoader; do
    require_text "$AGENT_ROOT/keyboard_ru.js" "com.qinggan.app.qgime.$ru_class"
    [[ -f "$HARNESS_ROOT/app/src/qgime/java/com/qinggan/app/qgime/$ru_class.java" ]] || {
        fail "Russian keyboard fixture is missing: $ru_class"
    }
done
require_text "$AGENT_ROOT/keyboard_ru.js" 'com.qinggan.theme.ThemeManager'
require_text "$AGENT_ROOT/keyboard_ru.js" 'com.pateo.material.dialog.QGToast'

printf 'Agent contracts match six production scripts.\n'
