#!/usr/bin/env python3
"""Build and run OTA/Native/RestoreMode contracts on an explicitly selected emulator."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RESTORE_TEST_CLASSES = (
    "ru.big.town.restoremode.RestoreIntegrationTest",
    "ru.big.town.restoremode.NewFeaturesIntegrationTest",
    "ru.big.town.restoremode.LazySettingsIntegrationTest",
)


def run(command, **options):
    print("+", " ".join(map(str, command)), flush=True)
    return subprocess.run(list(map(str, command)), check=True, **options)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--no-build", action="store_true", help="Use previously built debug APKs")
    parser.add_argument(
        "--suite",
        action="append",
        choices=["ota", "native", "restore"],
        help="Select suites; default: all",
    )
    parser.add_argument(
        "--screenshot-dir",
        type=Path,
        help="Capture new UI states on the emulator and copy PNGs here",
    )
    arguments = parser.parse_args()
    sdk_path = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = str(Path(sdk_path) / "platform-tools/adb") if sdk_path else shutil.which("adb")
    if not adb:
        parser.error("Set ANDROID_HOME or put adb on PATH")

    device = [adb, "-s", arguments.serial]
    emulator_flag = run(
        device + ["shell", "getprop", "ro.kernel.qemu"], capture_output=True, text=True
    ).stdout.strip()
    if not arguments.serial.startswith("emulator-") or emulator_flag != "1":
        parser.error("Only an Android emulator is allowed; no car/device runs")

    if not arguments.no_build:
        for project in ["Native", "RestoreMode"]:
            run(
                [
                    ROOT / project / "gradlew",
                    "-p",
                    ROOT / project,
                    "-PvoyahMinifyDebug=false",
                    ":app:assembleDebug",
                    ":app:assembleDebugAndroidTest",
                    ":app:testDebugUnitTest",
                ],
                cwd=ROOT,
            )
        run(
            [
                ROOT / "Updater/gradlew",
                "-p",
                ROOT / "Updater",
                ":updater-ui:assembleDebugAndroidTest",
                ":updater-ui:testDebugUnitTest",
            ],
            cwd=ROOT,
        )

    # Native owns the signature permission shared by the APKs and mocks.
    for apk in [
        "Native/app/build/outputs/apk/debug/app-debug.apk",
        "Native/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
        "RestoreMode/app/build/outputs/apk/debug/app-debug.apk",
        "RestoreMode/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
        "Updater/ui/build/outputs/apk/androidTest/debug/updater-ui-debug-androidTest.apk",
    ]:
        run(device + ["install", "-r", ROOT / apk])

    suites = [
        ("native", "ru.big.town.anative", "ru.big.town.anative.NativeIntegrationTest"),
        ("restore", "ru.big.town.restoremode", ",".join(RESTORE_TEST_CLASSES)),
        ("ota", "ru.big.town.updater.ui", "ru.big.town.updater.ui.UpdaterIntegrationTest"),
    ]
    for suite, package, test_classes in suites:
        if arguments.suite and suite not in arguments.suite:
            continue
        capture = (
            ["-e", "screenshots", "true"]
            if arguments.screenshot_dir and suite == "restore"
            else []
        )
        result = run(
            device
            + ["shell", "am", "instrument", "-w", "-r"]
            + capture
            + ["-e", "class", test_classes, package + ".test/androidx.test.runner.AndroidJUnitRunner"],
            capture_output=True,
            text=True,
        )
        print(result.stdout, flush=True)
        if not re.search(r"^OK \(\d+ tests?\)", result.stdout, re.MULTILINE):
            raise SystemExit(
                f"Instrumentation failed or did not complete: {test_classes}\n{result.stderr}"
            )

    if arguments.screenshot_dir and (not arguments.suite or "restore" in arguments.suite):
        arguments.screenshot_dir.mkdir(parents=True, exist_ok=True)
        user = run(
            device + ["shell", "am", "get-current-user"], capture_output=True, text=True
        ).stdout.strip()
        if not user.isdigit():
            raise SystemExit("Cannot resolve the emulator foreground user")
        access = ["exec-out", "run-as", "ru.big.town.restoremode", "--user", user]
        names = run(
            device + access + ["ls", "files/integration-screenshots"],
            capture_output=True,
            text=True,
        ).stdout.split()
        for name in names:
            if Path(name).name != name or not name.endswith(".png"):
                continue
            with (arguments.screenshot_dir / name).open("wb") as output:
                run(device + access + ["cat", "files/integration-screenshots/" + name], stdout=output)


if __name__ == "__main__":
    main()
