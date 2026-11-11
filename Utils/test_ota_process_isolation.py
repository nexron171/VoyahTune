#!/usr/bin/env python3
"""Verify Updater isolation and the RestoreMode guard on a rooted emulator."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = str(Path(sdk) / "platform-tools/adb") if sdk else shutil.which("adb")
    if not adb:
        parser.error("Set ANDROID_HOME or put adb on PATH")

    def command(*arguments, check=True):
        return subprocess.run(
            [adb, "-s", args.serial, *arguments],
            check=check,
            capture_output=True,
            text=True,
        )

    if (
        not args.serial.startswith("emulator-")
        or command("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1"
    ):
        parser.error("Only an Android emulator is allowed")
    command("root")
    command("wait-for-device")
    assert command("shell", "id", "-u").stdout.strip() == "0", "A rooted emulator is required"
    block = "/data/local/bin/voyahtune-update.block"
    assert command("shell", "test", "-e", block, check=False).returncode != 0, (
        "A pre-existing block must be preserved"
    )
    command("shell", "mkdir", "-p", "/data/local/bin")
    command("shell", "am", "force-stop", "ru.big.town.restoremode")
    try:
        command("shell", "touch", block)
        command("shell", "am", "start", "-W", "-n", "ru.big.town.updater/.UpdaterActivity")
        updater = command("shell", "pidof", "ru.big.town.updater").stdout.strip()
        assert updater, "Updater process was blocked"
        command(
            "shell", "am", "start", "-W", "-n",
            "ru.big.town.restoremode/.MainActivity", check=False,
        )
        time.sleep(0.5)
        assert not command(
            "shell", "pidof", "ru.big.town.restoremode", check=False
        ).stdout.strip(), "Runtime escaped the update block"
        command("shell", "am", "force-stop", "ru.big.town.restoremode")
        assert command("shell", "pidof", "ru.big.town.updater").stdout.strip() == updater, (
            "Updater UI was killed with RestoreMode"
        )
        print("PASS: Updater survives RestoreMode stop; runtime cannot bypass the update block")
    finally:
        command("shell", "rm", "-f", block)
        command("shell", "am", "force-stop", "ru.big.town.restoremode")
        command("shell", "am", "force-stop", "ru.big.town.updater")


if __name__ == "__main__":
    main()
