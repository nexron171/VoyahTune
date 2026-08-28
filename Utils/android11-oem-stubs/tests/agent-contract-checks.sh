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

reject_text() {
    local file="$1"
    local text="$2"
    if grep -Fq "$text" "$file"; then
        fail "obsolete '$text' is present in $file"
    fi
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
require_text "$AGENT_ROOT/launcherdock.js" 'com.qinggan.launcher.navigation.NavigationBarSecond'
require_text "$AGENT_ROOT/launcherdock.js" 'com.qinggan.launcher.navigation.NavigationBarController'
reject_text "$AGENT_ROOT/launcherdock.js" 'dockPassenger'
for method in updateTheme initScreenUpViews updateSelectedApp onClick; do
    require_text "$AGENT_ROOT/launcherdock.js" "NavigationBarMain.$method"
done
for passenger_field in mScreenUpAirView mScreenUpSeatView; do
    reject_text "$AGENT_ROOT/launcherdock.js" "$passenger_field"
done
require_text "$AGENT_ROOT/launcherdock.js" 'var driverTemperature = dockField(instance, "mScreenUpTemperatureContentView");'
require_text "$AGENT_ROOT/launcherdock.js" 'setDockViewVisibility(driverTemperature, compact ? 8 : 0, "driverTemperature");'
reject_text "$AGENT_ROOT/launcherdock.js" 'NavigationBarMain.doScreenLift'
reject_text "$AGENT_ROOT/launcherdock.js" 'NavigationBarSecond.doScreenLift'
reject_text "$AGENT_ROOT/launcherdock.js" 'this.startLauncherMain('
reject_text "$AGENT_ROOT/launcherdock.js" 'this.openAllApp('
for fixture_method in \
    'void updateTheme()' \
    'void initScreenUpViews()' \
    'View mScreenUpTemperatureContentView' \
    'void updateSelectedApp(String packageName, String activityName)' \
    'void onClick(View view)'; do
    require_fixture_text \
        'launcher/java/com/qinggan/launcher/navigation/NavigationBarMain.java' \
        "$fixture_method"
done
for passenger_fixture in \
    'class NavigationBarSecond implements INavigationBar, View.OnClickListener' \
    'View mScreenUpAirView' \
    'View mScreenUpSeatView' \
    'View mScreenUpTemperatureContentView' \
    'void updateSelectedApp(String packageName, String activityName)' \
    'void onClick(View view)'; do
    require_fixture_text \
        'launcher/java/com/qinggan/launcher/navigation/NavigationBarSecond.java' \
        "$passenger_fixture"
done
reject_text \
    "$HARNESS_ROOT/app/src/launcher/java/com/qinggan/launcher/navigation/NavigationBarSecond.java" \
    'extends NavigationBarMain'
for controller_fixture in \
    'int mScreenId' \
    'INavigationBar mNavigationBar' \
    'void doScreenLift(int type)' \
    'void show()' \
    'void dismiss()' \
    'boolean isShowing()'; do
    require_fixture_text \
        'launcher/java/com/qinggan/launcher/navigation/NavigationBarController.java' \
        "$controller_fixture"
done
for launcher_lifecycle in \
    'void onReceive(Context context, Intent intent)' \
    'void handleUpdateMainNavigationBar(' \
    'void handleUpdateSecondNavigationBar(' \
    'void onMoveStart(' \
    'void onMoveStop('; do
    require_fixture_text \
        'launcher/java/com/qinggan/app/launcher/LauncherModel.java' \
        "$launcher_lifecycle"
done

# Full All Apps is a primary part of launcherdock.js now: both physical lists, both bind overloads,
# owner-screen click routing, package-driven reload, and the optional OD passenger home rail.
for allapps_class in \
    'com.qinggan.launcher.base.bean.AppBean' \
    'com.qinggan.launcher.base.allapp.AllAppDataManager' \
    'com.qinggan.launcher.base.adapter.AllAppAdapter' \
    'com.qinggan.launcher.base.allapp.AllAppBarView' \
    'com.qinggan.launcher.base.utils.AppLauncher'; do
    require_text "$AGENT_ROOT/launcherdock.js" "$allapps_class"
done
require_text "$AGENT_ROOT/launcherdock.js" "Data.getAllApps.overload('int')"
require_text "$AGENT_ROOT/launcherdock.js" 'Data.reload.overload()'
require_text "$AGENT_ROOT/launcherdock.js" "AllAppBarView.onClick.overload('android.view.View')"
require_text "$AGENT_ROOT/launcherdock.js" 'AppLauncher.startApp(ctx(), intent, screenId)'
require_text "$AGENT_ROOT/launcherdock.js" "'com.qinggan.launcher.base.adapter.AllAppAdapter\$AppViewHolder', 'int'"
require_text "$AGENT_ROOT/launcherdock.js" "'int', 'java.util.List'"
for package_action in PACKAGE_ADDED PACKAGE_REMOVED PACKAGE_CHANGED; do
    require_text "$AGENT_ROOT/launcherdock.js" "android.intent.action.$package_action"
done
require_text "$AGENT_ROOT/launcherdock.js" 'packageFilter.addDataScheme("package")'

for appbean_method in \
    'AppBean(int icon, int nameRes, String packageName)' \
    'int getIcon()' \
    'int getNameRes()' \
    'String getPackageName()' \
    'int getType()' \
    'String getSubType()' \
    'void setSubType(String subType)'; do
    require_fixture_text \
        'launcher/java/com/qinggan/launcher/base/bean/AppBean.java' \
        "$appbean_method"
done
for data_method in \
    'List<AppBean> getAllApps(int screenId)' \
    'void reload()'; do
    require_fixture_text \
        'launcher/java/com/qinggan/launcher/base/allapp/AllAppDataManager.java' \
        "$data_method"
done
require_fixture_text \
    'launcher/java/com/qinggan/launcher/base/allapp/AllAppBarView.java' \
    'void onClick(View view)'
require_fixture_text \
    'launcher/java/com/qinggan/launcher/base/allapp/AllAppBarView.java' \
    'int mScreenId'
for bind_signature in \
    'void onBindViewHolder(AppViewHolder holder, int position)' \
    'void onBindViewHolder(AppViewHolder holder, int position, List<Object> payloads)'; do
    require_fixture_text \
        'launcher/java/com/qinggan/launcher/base/adapter/AllAppAdapter.java' \
        "$bind_signature"
done
require_fixture_text \
    'launcher/java/com/qinggan/launcher/base/utils/AppLauncher.java' \
    'void startApp(Context context, Intent intent, int screenId)'

require_text "$AGENT_ROOT/launcherdock.js" \
    'com.qinggan.secondlauncher.adapter.SecondAllAppAdapter'
require_text "$AGENT_ROOT/launcherdock.js" \
    'com.qinggan.secondlauncher.fragment.SecondMainFragment'
require_fixture_text \
    'launcher/java/com/qinggan/secondlauncher/adapter/SecondAllAppAdapter.java' \
    'void onBindViewHolder(ViewHolder holder, int position)'
require_fixture_text \
    'launcher/java/com/qinggan/secondlauncher/adapter/SecondAllAppAdapter.java' \
    'List<AppBean> allAppList'
require_fixture_text \
    'launcher/java/com/qinggan/secondlauncher/fragment/SecondMainFragment.java' \
    'void onItemClick(AppBean appBean)'

require_text "$AGENT_ROOT/multidisplay.js" 'com.qinggan.systemservice.multidisplay.MultiDisplayImpl'
require_text "$AGENT_ROOT/multidisplay.js" 'var method = MDI.isWhiteListApp;'
require_text "$AGENT_ROOT/multidisplay.js" 'method.overloads.length === 0'
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
