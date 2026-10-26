#!/usr/bin/env python3
"""Run host-side behavioral tests and syntax checks for a compatibility profile.

These checks do not contact a device. Installer-build and release-core validate
signed APK/payload matching when the actual payload is built.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TESTS = ROOT / "Packaging/tests"
COMMON = (
    "test_phone_numbers.js",
    "test_classic_release_identity.py",
    "test_stop_loader.py",
    "test_acc_preferences.py",
    "test_power_hold_bridge.js",
)
OD = (
    "test_acc_restore_hook.sh",
    "test_apollo_safe_device.sh",
    "test_rds_restore.js",
    "test_rds_widget.js",
    "test_drive_reset_hook.sh",
    "test_loader_fault_backoff.sh",
    "test_loader_pipe_records.sh",
    "test_vd_stability.js",
    "test_native_task_removal.js",
    "test_carplay_allapps.js",
    "test_parallel_hook_loader.py",
    "test_updater_ui_worker.py",
)


def run(command, **kwargs):
    print("+", " ".join(map(str, command)), flush=True)
    kwargs.setdefault("cwd", ROOT)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def interpreter(path):
    if path.suffix == ".sh":
        with path.open() as source:
            shebang = source.readline().strip()
        return "bash" if shebang.startswith("#!") and "bash" in shebang else "sh"
    return {".py": "python3", ".js": "node"}[path.suffix]


def check_syntax(profile):
    root = ROOT / "Packaging" / profile
    for path in sorted((root / "inject").glob("*.js")):
        run(["node", "--check", path])
    for path in sorted((root / "inject").glob("*.json")):
        json.loads(path.read_text())
    for path in sorted(root.rglob("*.sh")):
        run([interpreter(path), "-n", path])
    if profile == "od":
        run(["sh", "-n", root / "system/load.bin"])


def run_pi_loader_tests():
    go = os.environ.get("GO_BIN") or shutil.which("go")
    if not go:
        candidates = sorted((ROOT / "Releases/cache/go-toolchain").glob("*/go/bin/go"))
        if candidates:
            go = str(candidates[-1])
    if not go:
        raise SystemExit("PI tests require Go; set GO_BIN to the Go compiler")
    environment = {**os.environ, "GOCACHE": str(ROOT / "Releases/cache/go-build")}
    run(
        [go, "test", "-race", "./..."],
        cwd=ROOT / "Packaging/pi/loaderFrida",
        env=environment,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True, choices=("pi", "od"))
    arguments = parser.parse_args()
    check_syntax(arguments.profile)
    for name in COMMON + (OD if arguments.profile == "od" else ()):
        path = TESTS / name
        run([interpreter(path), path])
    if arguments.profile == "pi":
        run_pi_loader_tests()
    print(f"PASS: {arguments.profile.upper()} infrastructure behavior and syntax")


if __name__ == "__main__":
    main()
