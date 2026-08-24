# Hook ABI contract

This document prevents the stub APKs from being mistaken for an OEM firmware
emulator. All listed classes are synthetic and exist only in the flavor that
owns the corresponding process.

The seventh hook script, `vd_bypass.js`, targets Android's real `system_server`; it is deliberately
outside this five-APK harness and cannot be represented by an application process.

## Tier 1: process and lifecycle

Supported for all five targets:

- exact application ID;
- exact application process name;
- deterministic foreground-service start;
- PID and `/proc/<pid>/cmdline` verification;
- ownership-checked cleanup.

`verify.sh` reports only these properties. A passing `verify.sh` result says
nothing about whether Frida attached or a hook was installed.

## Tier 2: primary hook-resolution fixtures

These fixtures are sufficient for the current scripts to find the primary
class/method surface and assign their hook implementations:

| Agent | Target | Synthetic ABI available |
|---|---|---|
| `steeringwheelkeys.js` | `com.qinggan.keymanager.service` | `KeyManagerReader.onKeyEvent(android.view.KeyEvent): boolean` |
| `launcherdock.js` | `com.qinggan.app.launcher` | OD `NavigationBarMain.updateTheme()`, `initScreenUpViews()`, `updateSelectedApp(String,String)`, `onClick(View)`, `dismiss()`, plus the three view fields and `mScreenId` |
| `multidisplay.js` | `com.qinggan.systemservice` | `MultiDisplayImpl.isWhiteListApp(String): boolean` |
| `apollo_tech.js` | `com.qinggan.app.vehiclesetting` | static no-argument `BaiduProviderUtil.doQuerySubscribeInfo(): String` and `doQueryNOALearnInfo(): String` |
| `keyboard_lock_en.js` | `com.qinggan.app.qgime` | `InputModeSwitcher` English constants, `getInstance()`, `saveInputMode(int)`; `QGInputConfig.DISABLE_VOICE`; `SkbPool.getInstance()/resetCachedSkb()`; optional loader/reflection surface |
| `keyboard_ru.js` | `com.qinggan.app.qgime` | class/method/field resolution for the hook assignments in the current agent, including pool, soft-key, keyboard, IME, input-processor, loader, theme, resources, and toast facades |

An injector smoke test must independently assert the current agent's ready log
after injection. Expected success markers are:

| Agent | Marker |
|---|---|
| steering wheel | `[swk] keymanager hooks installed` |
| launcher dock | `[dock] NavigationBarMain hooks installed` |
| multidisplay | `isWhiteListApp hooked` |
| Apollo | `[apollo] hook ready` |
| English keyboard | `keyboard-lock-en-mod: Agent started` |
| Russian keyboard | `keyboard-ru-mod: Agent started` |

The exact log prefix can include a timestamp or Android log tag. The harness
does not currently invoke `frida-inject`, because injector lifecycle and status
contracts belong to the injector under test rather than the target APKs.

## Explicitly unsupported ABI and behavior

### `launcherdock.js`

The primary OD navigation-bar hook resolves. These optional or firmware-specific
surfaces are not provided:

- PI firmware `com.qinggan.mainlauncher.navigation.NavigationBar`;
- `com.qinggan.launcher.navigation.NavigationBarController.dismiss()`;
- `com.qinggan.app.launcher.LauncherModel.onReceive(Context,Intent)`,
  `handleUpdateMainNavigationBar(String,String,boolean)`,
  `isThirdShowFloatApp(String)`, and `onMoveStart(...)`;
- `com.qinggan.launcher.base.utils.AppUtils.getTopAppInfo(Context,int,int)`;
- `com.qinggan.account.AccountConstantUtil.SEPARATOR`;
- `com.qinggan.launcher.base.drag.ThirdAppUtil.isThirdShowFloatApp(String)`;
- `AllAppDataManager.getAllApps(int)`,
  `AllAppAdapter.onBindViewHolder(AllAppAdapter.AppViewHolder,int)`,
  `AppBean`, and `AppLauncher.startApp(Context,Intent,int)`.

No `NavigationBarMain` instance is created. Therefore `Java.choose`, drawable
replacement, display selection, click routing, dock restoration, floating-home
suppression, and all-app list behavior are not tested.

### `steeringwheelkeys.js`

Only the hook target signature is represented. The harness does not generate
physical QG key codes, provide VoyahTune's media `ContentProvider`, validate the
ordered media-proxy receiver, dispatch audio media keys, or receive the Native
action broadcasts.

### `multidisplay.js`

Only whitelist-hook resolution is represented. There is no WindowManager or
Qinggan display-transfer call site, gesture, animation, or firmware whitelist.

### `apollo_tech.js`

Only the two entitlement query signatures are represented. There is no OEM
provider caller or ADAS UI state machine. The stubs contain no CAN API and
cannot validate vehicle-side activation.

### Keyboard agents

The synthetic class surface can install the current hook assignments, but its
method signatures and object graph are not evidence of production firmware
compatibility. The harness does not validate:

- OEM keyboard XML/resources, key dimensions, drawable theme states, or cache
  semantics;
- `/data/local/bin/voyahtune_skb_qwerty_ru.json` or keyboard icon config
  provisioning;
- real `InputConnection.commitText`, candidate state, voice input, case changes,
  or English/Russian switching behavior;
- OEM overload count, private-field type, or ABI drift between firmware builds.

Consequently a keyboard ready marker is only a hook-installation smoke result;
layout rendering and text entry still require an H97C integration test.

## Drift rule

`tests/agent-contract-checks.sh` compares this fixture surface with the six
scripts currently under `Packaging/inject`. If a target class or primary method
changes, static validation fails until this document and the relevant fixture
are deliberately updated.
