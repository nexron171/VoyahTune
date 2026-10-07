#!/usr/bin/env python3
"""Verify the real RestoreMode Application guard on a rooted Android emulator."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    adb = str(Path(sdk)/'platform-tools/adb') if sdk else shutil.which('adb')
    if not adb:
        parser.error('Set ANDROID_HOME or put adb on PATH')
    def command(*argv, check=True):
        return subprocess.run([adb, '-s', args.serial, *argv], check=check, capture_output=True, text=True)
    if not args.serial.startswith('emulator-') or command('shell','getprop','ro.kernel.qemu').stdout.strip() != '1':
        parser.error('Only an Android emulator is allowed')
    command('root')
    command('wait-for-device')
    assert command('shell','id','-u').stdout.strip() == '0', 'A rooted emulator is required'
    block = '/data/local/bin/voyahtune-update.block'
    assert command('shell','test','-e',block,check=False).returncode != 0, 'A pre-existing block must be preserved'
    command('shell','mkdir','-p','/data/local/bin')
    command('shell','am','force-stop','ru.big.town.restoremode')
    try:
        command('shell','touch',block)
        command('shell','am','start','-W','-n','ru.big.town.restoremode/.OtaActivity')
        ota = command('shell','pidof','ru.big.town.restoremode:ota').stdout.strip()
        assert ota, 'OTA process was blocked'
        command('shell','am','start','-W','-n','ru.big.town.restoremode/.MainActivity',check=False)
        time.sleep(.5)
        assert not command('shell','pidof','ru.big.town.restoremode',check=False).stdout.strip(), 'Runtime escaped the OTA block'
        assert command('shell','pidof','ru.big.town.restoremode:ota').stdout.strip() == ota, 'OTA UI was killed with runtime'
        print('PASS: OTA Activity survives the block; RestoreMode runtime cannot start')
    finally:
        command('shell','rm','-f',block)
        command('shell','am','force-stop','ru.big.town.restoremode')


if __name__ == '__main__':
    main()
