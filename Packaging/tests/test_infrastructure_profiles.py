#!/usr/bin/env python3
"""Check the selected release's source assets and run its host-side contracts.

These checks do not contact a device. Signed APK/payload matching is additionally
validated by installer-build and release-core when the actual payload is built.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TESTS = ROOT / 'Packaging/tests'
COMMON = (
    'test_android11_package_lifecycle.sh',
    'test_classic_release_identity.py',
    'test_stop_loader.py',
    'test_battery_heat_event_driven.sh', 'test_light_sensor_event_driven.sh',
    'test_mode_feedback.sh', 'test_mode_restore_events.sh',
    'test_other_system_metrics.sh', 'test_power_hold_oem.sh',
    'test_saved_config_startup_wake.sh', 'test_trip_stats_can_coalescing.sh',
    'test_vehicle_state_controllers.sh', 'test_wash_mode_oem.sh',
)
OD = (
    'test_acc_restore_hook.sh',
    'test_apollo_direct_only.sh', 'test_apollo_safe_device.sh', 'test_app_client.sh',
    'test_drive_reset_hook.sh', 'test_hook_status.sh', 'test_keyboard_modes.sh',
    'test_loader_fault_backoff.sh', 'test_loader_pipe_records.sh',
    'test_mapkit_dpi_client.sh', 'test_screen_lift_resize_restore.sh',
    'test_vd_hot_hooks_disabled.sh', 'test_vd_reparent_replay.sh',
    'test_native_task_removal.js', 'test_vehicle_drive_hook.js',
    'test_parallel_hook_loader.py', 'test_updater_ui_worker.py',
)


def run(command, **kwargs):
    print('+', ' '.join(map(str, command)), flush=True)
    kwargs.setdefault('cwd', ROOT)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def interpreter(path):
    if path.suffix == '.sh':
        # Some shipped diagnostic/research scripts use Bash syntax. Android sh
        # scripts are still checked with the host POSIX shell.
        with path.open() as source:
            shebang = source.readline().strip()
        return 'bash' if shebang.startswith('#!') and 'bash' in shebang else 'sh'
    return {'.py': 'python3', '.js': 'node'}[path.suffix]


def assets(profile):
    root = ROOT / 'Packaging' / profile
    required = [root/'system'/name for name in ('voyahtune.load.rc', 'voyahtune.load.sh')]
    required += [root/'installer/device'/name for name in ('install.sh', 'install.bat', 'remove.sh', 'remove.bat')]
    required += [root/'installer/common'/name for name in ('dns-overlay.sh', 'dns-overlay.bat', 'dns-overlay-device.sh', 'apollo-safe-device.sh', 'stop-loader-device.sh')]
    required += [root/'tools/frida-inject-16.2.1-android-arm64']
    if profile == 'od':
        required += [root/'system/load.bin']
        required += [root/'inject'/name for name in ('app_client.js', 'voyahtune_acc_restore.js', 'voyahtune_drive_reset.js')]
        assert not (root/'loaderFrida').exists(), 'OD must use its own shell loader'
    else:
        required += [root/'loaderFrida'/name for name in ('main.go', 'go.mod', 'build.sh', 'injects.json')]
        config = json.loads((root/'loaderFrida/injects.json').read_text())
        assert config and all(isinstance(items, list) and items for items in config.values())
        required += [root/'inject'/name for items in config.values() for name in items]
        assert not (root/'system/load.bin').exists(), 'PI must not package the obsolete OD-style shell loader'
        for name in ('app_client.js', 'voyahtune_acc_restore.js', 'voyahtune_drive_reset.js'):
            assert not (root/'inject'/name).exists(), f'OD hook leaked into PI: {name}'
    for path in required:
        assert path.is_file() and path.stat().st_size, f'Missing release asset: {path}'
    for path in sorted((root/'inject').glob('*.js')):
        run(['node', '--check', path])
    for path in sorted((root/'inject').glob('*.json')):
        json.loads(path.read_text())
    for path in sorted(root.rglob('*.sh')):
        run([interpreter(path), '-n', path])
    if profile == 'od':
        run(['sh', '-n', root/'system/load.bin'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', required=True, choices=('pi', 'od'))
    args = parser.parse_args()
    assets(args.profile)
    for name in COMMON + (OD if args.profile == 'od' else ()):
        path = TESTS/name
        run([interpreter(path), path])
    if args.profile == 'pi':
        go = os.environ.get('GO_BIN')
        if not go:
            import shutil
            go = shutil.which('go')
        if not go:
            candidates = sorted((ROOT/'Releases/cache/go-toolchain').glob('*/go/bin/go'))
            if candidates:
                go = str(candidates[-1])
        if not go:
            raise SystemExit('PI tests require Go; set GO_BIN to the Go compiler')
        env = {**os.environ, 'GOCACHE': str(ROOT/'Releases/cache/go-build')}
        run([go, 'test', '-race', './...'], cwd=ROOT/'Packaging/pi/loaderFrida', env=env)
    else:
        run(['bash', ROOT/'Utils/android11-oem-stubs/tests/static-checks.sh'])
    run(['python3', ROOT/'Installer/scripts/sync-classic-commands.py', '--check'])
    print(f'PASS: {args.profile.upper()} infrastructure source checks')


if __name__ == '__main__':
    main()
