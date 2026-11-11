#!/usr/bin/env python3
"""Exercise forced stopping against an isolated process registry."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "Packaging/installer/common/force-stop-runtime-device.sh"


class ForcedRuntimeStopTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="forced-runtime-stop-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.processes = self.directory / "proc"
        self.processes.mkdir()
        self.packages = self.directory / "packages.list"
        self.packages.write_text(
            "ru.big.town.anative 1000 0\n"
            "ru.big.town.restoremode 10100 0\n"
            "big.town.runyn 10101 0\n"
            "ru.big.town.updater 10102 0\n"
        )

    def process(self, pid, command, uid=0, state="S"):
        directory = self.processes / str(pid)
        directory.mkdir()
        directory.joinpath("cmdline").write_bytes(command.encode() + b"\0")
        fields = [state] + ["0"] * 18 + [str(pid * 10)]
        directory.joinpath("stat").write_text(f"{pid} (runtime process) {' '.join(fields)}\n")
        directory.joinpath("status").write_text(f"Uid:\t{uid}\t{uid}\t{uid}\t{uid}\n")

    def stop(self, stuck=False, denied=False, service="stopped"):
        fixture = r'''
setprop() { printf '%s\n' "$*" >> "$TEST_ROOT/init"; }
getprop() { printf '%s\n' "$TEST_SERVICE"; }
sleep() { :; }
kill() {
    printf '%s\n' "$*" >> "$TEST_ROOT/kills"
    [ "$TEST_DENIED" != 1 ] || return 1
    [ "$TEST_STUCK" = 1 ] || rm -rf "$VOYAHTUNE_PROC_ROOT/$2"
}
. "$1"
'''
        return subprocess.run(
            ["sh", "-c", fixture, "forced-stop", str(HELPER)],
            env=dict(
                os.environ,
                VOYAHTUNE_PROC_ROOT=str(self.processes),
                VOYAHTUNE_PACKAGES_LIST=str(self.packages),
                TEST_ROOT=str(self.directory),
                TEST_STUCK=str(int(stuck)),
                TEST_DENIED=str(int(denied)),
                TEST_SERVICE=service,
            ),
            capture_output=True,
            text=True,
            timeout=5,
        )

    def test_kills_owned_processes_and_preserves_updater_and_oem(self):
        owned = {
            101: ("/data/local/bin/voyahtune-new/voyahtune-loader", 0),
            102: ("/data/local/bin/voyahtune-new/voyahtune-inject", 0),
            103: ("/data/local/bin/voyahtune-new/voyahtune-inject-arm", 0),
            104: ("ru.big.town.anative", 1000),
            105: ("ru.big.town.restoremode", 10100),
            106: ("ru.big.town.restoremode:worker", 10100),
            107: ("big.town.runyn", 10101),
            108: ("/system/bin/sh\0/data/local/bin/load.bin", 0),
        }
        preserved = {
            201: ("ru.big.town.updater", 10102),
            202: ("/data/local/bin/voyahtune-updater", 0),
            203: ("system_server", 1000),
            204: ("com.qinggan.canbus.service", 1000),
            205: ("ru.big.town.restoremode.other", 10100),
            206: ("ru.big.town.anative", 10300),
            207: ("/other/voyahtune-inject", 0),
            208: ("/system/bin/sh\0-c\0kill /data/local/bin/load.bin", 0),
            209: ("/other/program\0/data/local/bin/load.bin", 0),
        }
        for pid, (command, uid) in (owned | preserved).items():
            self.process(pid, command, uid)
        result = self.stop()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({int(path.name) for path in self.processes.iterdir()}, set(preserved))
        self.assertEqual(self.directory.joinpath("init").read_text(), "ctl.stop voyahtune_load\n")
        self.assertEqual(
            set(self.directory.joinpath("kills").read_text().splitlines()),
            {f"-9 {pid}" for pid in owned},
        )

    def test_nonresponsive_or_unkillable_process_fails_without_waiting_for_ack(self):
        for denied in (False, True):
            with self.subTest(denied=denied):
                if not (self.processes / "101").exists():
                    self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
                result = self.stop(stuck=True, denied=denied)
                self.assertNotEqual(result.returncode, 0)
                self.assertTrue((self.processes / "101").exists())

    def test_zombie_no_longer_blocks_replacement(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader", state="Z")
        self.assertEqual(self.stop().returncode, 0)
        self.assertFalse((self.directory / "kills").exists())

    def test_missing_components_are_already_stopped(self):
        self.assertEqual(self.stop().returncode, 0)
        self.assertFalse((self.directory / "kills").exists())

    def test_running_init_service_cannot_be_treated_as_stopped(self):
        self.assertNotEqual(self.stop(service="running").returncode, 0)

    def test_missing_package_registry_does_not_skip_app_termination(self):
        self.packages.unlink()
        self.assertNotEqual(self.stop().returncode, 0)


if __name__ == "__main__":
    unittest.main()
