#!/usr/bin/env python3
"""Build and run the OTA/Native/RestoreMode contracts on one explicitly selected emulator."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(args, **kwargs):
    print('+', ' '.join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--no-build', action='store_true', help='Use previously built debug APKs')
    parser.add_argument('--suite', action='append', choices=['ota', 'native', 'restore'], help='Select suites; default: all')
    parser.add_argument('--screenshot-dir', type=Path, help='Capture new UI states on the emulator and copy PNGs here')
    args = parser.parse_args()
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    adb = str(Path(sdk) / 'platform-tools/adb') if sdk else shutil.which('adb')
    if not adb:
        parser.error('Set ANDROID_HOME or put adb on PATH')
    device = [adb, '-s', args.serial]
    qemu = run(device + ['shell', 'getprop', 'ro.kernel.qemu'], capture_output=True, text=True).stdout.strip()
    if not args.serial.startswith('emulator-') or qemu != '1':
        parser.error('Only an Android emulator is allowed; no car/device runs')
    if not args.no_build:
        for project in ['Native', 'RestoreMode']:
            run([ROOT/project/'gradlew', '-p', ROOT/project, '-PvoyahMinifyDebug=false',
                 ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:testDebugUnitTest'], cwd=ROOT)
        run([ROOT/'Updater/gradlew', '-p', ROOT/'Updater', ':updater-ui:assembleDebugAndroidTest',
             ':updater-ui:testDebugUnitTest'], cwd=ROOT)
    # Install Native first: it owns the signature permission used by both APKs and mocks.
    for apk in [
        'Native/app/build/outputs/apk/debug/app-debug.apk',
        'Native/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
        'RestoreMode/app/build/outputs/apk/debug/app-debug.apk',
        'RestoreMode/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
        'Updater/ui/build/outputs/apk/androidTest/debug/updater-ui-debug-androidTest.apk',
    ]:
        run(device + ['install', '-r', ROOT/apk])
    for suite, package, test in [
        ('native', 'ru.big.town.anative', 'ru.big.town.anative.NativeIntegrationTest'),
        ('restore', 'ru.big.town.restoremode', 'ru.big.town.restoremode.RestoreIntegrationTest,ru.big.town.restoremode.NewFeaturesIntegrationTest'),
        ('ota', 'ru.big.town.updater.ui', 'ru.big.town.updater.ui.UpdaterIntegrationTest'),
    ]:
        if args.suite and suite not in args.suite:
            continue
        capture = ['-e', 'screenshots', 'true'] if args.screenshot_dir and suite == 'restore' else []
        result = run(device + ['shell', 'am', 'instrument', '-w', '-r'] + capture + ['-e', 'class', test,
            package+'.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True)
        print(result.stdout, flush=True)
        if not re.search(r'^OK \(\d+ tests?\)', result.stdout, re.MULTILINE):
            raise SystemExit(f'Instrumentation failed or did not complete: {test}\n{result.stderr}')

    if args.screenshot_dir and (not args.suite or 'restore' in args.suite):
        args.screenshot_dir.mkdir(parents=True, exist_ok=True)
        user = run(device + ['shell', 'am', 'get-current-user'], capture_output=True, text=True).stdout.strip()
        if not user.isdigit():
            raise SystemExit('Cannot resolve the emulator foreground user')
        access = ['exec-out', 'run-as', 'ru.big.town.restoremode', '--user', user]
        names = run(device + access + ['ls', 'files/integration-screenshots'], capture_output=True, text=True).stdout.split()
        for name in names:
            if Path(name).name != name or not name.endswith('.png'):
                continue
            with (args.screenshot_dir/name).open('wb') as output:
                run(device + access + ['cat', 'files/integration-screenshots/'+name], stdout=output)


if __name__ == '__main__':
    main()
