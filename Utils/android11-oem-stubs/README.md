# Android 11 OEM process stubs

This harness builds and runs five minimal APKs whose package and Linux process
names match the Qinggan targets used by VoyahTune:

| Gradle flavor | Package and process |
|---|---|
| `launcher` | `com.qinggan.app.launcher` |
| `systemservice` | `com.qinggan.systemservice` |
| `qgime` | `com.qinggan.app.qgime` |
| `vehiclesetting` | `com.qinggan.app.vehiclesetting` |
| `keymanager` | `com.qinggan.keymanager.service` |

The APKs start an exported foreground service and package a deliberately small
synthetic OEM Java surface. They do not request network, storage, vehicle, CAN,
accessibility, or privileged permissions.

`system_server` is intentionally not a sixth APK: Android cannot replace that core process with a
stub package. Consequently `vd_bypass.js` is covered by manifest/static guards only; its framework
hook still requires a matching API 30 system image or the head unit.

The test contract is split into explicit tiers:

- **Tier 1 — process/lifecycle:** install, start, package identity, process
  identity, and cleanup. `verify.sh` checks only this tier.
- **Tier 2 — primary hook resolution:** target-side Java fixtures let the six
  current Frida agents resolve and assign their primary hooks. Injection is
  driven by the injector under test; this harness does not claim that
  `verify.sh` injected anything.
- **Not covered — OEM behavior:** real launcher views, physical key delivery,
  system move/display orchestration, ADAS provider call sites, IME rendering,
  private firmware signatures, and CAN behavior.

The exact supported and unsupported ABI is documented in
[`HOOK_ABI.md`](HOOK_ABI.md). The fixtures prove that the current scripts can
resolve a controlled synthetic surface. They are not a replacement for testing
against an H97C firmware image or a head unit.

## Safety boundary

The device scripts deliberately work only with an Android 11/API 30 emulator:

- `--serial` is mandatory; there is no implicit `adb` target.
- both the SDK level and the emulator system property are checked;
- install refuses to replace an existing package unless its version identifies
  it as this harness;
- cleanup only names the five packages above and skips anything that does not
  carry the harness version marker.

Never relax these checks to run the stubs on a head unit. Their package names
intentionally collide with real OEM software.

## Prerequisites

- an AOSP Android 11/API 30 emulator (a rooted image is only needed for the
  injector being tested, not for these stubs);
- Android SDK platform `android-30`;
- the repository's checked-in `Native/gradlew` wrapper and an already cached
  Android Gradle Plugin 8.12.0.

The build command always passes `--offline`; it never downloads dependencies.

Example AVD creation, if the API 30 image is already installed:

```sh
avdmanager create avd \
  --name voyahtune-api30 \
  --package 'system-images;android-30;default;x86_64' \
  --device pixel
```

## Usage

Run commands from this directory:

```sh
./build.sh
./install.sh --serial emulator-5554
./start.sh --serial emulator-5554
./verify.sh --serial emulator-5554
./cleanup.sh --serial emulator-5554
```

`install.sh` expects Gradle's normal output beneath
`app/build/outputs/apk`. A different APK output root can be supplied explicitly:

```sh
./install.sh --serial emulator-5554 --apk-dir /absolute/path/to/outputs/apk
```

`verify.sh` checks all of the following Tier 1 properties for every target:

1. the installed package has this harness's version marker;
2. the exact package process is alive;
3. `/proc/<pid>/cmdline` equals the expected OEM process name.

The foreground-service notifications are expected. They make process lifetime
deterministic while an external injection test is running.

## Static validation

This validation does not invoke Gradle or `adb`:

```sh
./tests/static-checks.sh
```

It checks shell syntax, the API 30 contract, all five identities, manifest
process wiring, minimal permissions, cleanup/install safety guards, and fixture
drift against all six current production agent scripts.
