#!/usr/bin/env python3
"""Export OTA diagnostics through root ADB, independently of Android apps."""
import argparse
import datetime
import json
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb', help='ADB executable, including the copy bundled with Installer')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True, help='New directory for diagnostics')
    parser.add_argument('--release-stale-desktop-lock', action='store_true',
                        help='Only after closing all installers: release an abandoned desktop lock; never an OTA lock')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    base = [args.adb, '-s', args.serial]

    def adb(*command):
        result = subprocess.run([*base, *command], capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result.stdout

    adb('root')
    adb('wait-for-device')
    if adb('shell', 'id -u').strip() != '0':
        raise RuntimeError('Root ADB is required')
    for name in ['state.json', 'state.corrupt.json', 'updater.log', 'updater.log.1', 'command.log']:
        remote = f'/data/local/voyahtune-updater/{name}'
        if adb('shell', f'if [ -f {remote} ]; then echo PRESENT; fi').strip() == 'PRESENT':
            adb('pull', remote, str(args.output / name))
    checks = {
        'service': 'getprop init.svc.voyahtune_updater',
        'boot': 'cat /proc/sys/kernel/random/boot_id',
        'owner': 'cat /data/local/voyahtune-install.lock/owner 2>/dev/null || true',
        'block': 'if [ -f /data/local/bin/voyahtune-update.block ]; then echo present; else echo absent; fi',
        'hooks': 'cat /data/local/tmp/voyahtune-hook-status.v1 2>/dev/null || true',
    }
    report = {name: adb('shell', command).strip() for name, command in checks.items()}
    report['exportedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (args.output / 'diagnostics.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if args.release_stale_desktop_lock:
        # Explicit operator action after closing every desktop installer; no age-based lock stealing.
        adb('shell', '''lock=/data/local/voyahtune-install.lock
[ ! -L "$lock" ] || exit 1
case "$(cat "$lock/owner" 2>/dev/null)" in
  desktop:*) rm "$lock/owner" "$lock/boot" && rmdir "$lock" && sync ;;
  *) echo 'Refusing: lock is absent or is not owned by a desktop installer' >&2; exit 1 ;;
esac''')
    print(args.output)


if __name__ == '__main__':
    main()
