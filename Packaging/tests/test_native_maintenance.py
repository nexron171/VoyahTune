#!/usr/bin/env python3
"""Exercise production maintenance helpers on an isolated filesystem."""

import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / "Packaging/installer/common"


class NativeMaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="native-maintenance-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.runtime = self.bin / "voyahtune-new"
        self.state = self.root / "state"
        self.boot = self.root / "boot"
        self.boot.write_text("boot-1\n")
        self.environment = dict(os.environ, VOYAHTUNE_BIN_DIRECTORY=str(self.bin),
                                VOYAHTUNE_BOOT_SOURCE=str(self.boot), VOYAHTUNE_RUNTIME_DIRECTORY=str(self.runtime),
                                VOYAHTUNE_STATE_DIRECTORY=str(self.state), PROBE_LOG=str(self.root / "calls"), PROBE_EXIT="0")

    def run_helper(self, name):
        result = subprocess.run(["sh", "-c", 'chown() { :; }; sync() { :; }; . "$1"', "maintenance", str(COMMON / name)],
                                env=self.environment, capture_output=True, text=True, timeout=5)
        return result

    def test_preparation_keeps_block_and_current_boot_until_reboot(self):
        result = self.run_helper("prepare-runtime-maintenance-device.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.bin / "voyahtune-native-migration.boot").read_text(), "boot-1\n")
        self.assertTrue((self.bin / "voyahtune-update.block").is_file())
        for path in self.bin.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.boot.write_text("boot-2\n")
        self.assertEqual(self.run_helper("prepare-runtime-maintenance-device.sh").returncode, 0)
        self.assertEqual((self.bin / "voyahtune-native-migration.boot").read_text(), "boot-2\n")
        self.assertEqual(len(list(self.bin.iterdir())), 2)

    def test_failed_boot_read_retains_install_block(self):
        self.boot.unlink()
        self.assertNotEqual(self.run_helper("prepare-runtime-maintenance-device.sh").returncode, 0)
        self.assertTrue((self.bin / "voyahtune-update.block").exists())
        self.assertFalse((self.bin / "voyahtune-native-migration.boot").exists())

    def test_symlink_destination_cannot_overwrite_another_file(self):
        self.bin.mkdir()
        source = self.root / "settings"
        source.write_text("preserved")
        (self.bin / "voyahtune-update.block").symlink_to(source)
        self.assertNotEqual(self.run_helper("prepare-runtime-maintenance-device.sh").returncode, 0)
        self.assertEqual(source.read_text(), "preserved")

    def fake_loader(self):
        self.runtime.mkdir(parents=True)
        loader = self.runtime / "voyahtune-loader"
        loader.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$PROBE_LOG"\nexit "$PROBE_EXIT"\n')
        loader.chmod(0o755)

    def test_native_component_or_orphan_journal_requires_confirmed_quiesce(self):
        for orphan in (False, True):
            with self.subTest(orphan=orphan):
                if not self.runtime.exists():
                    self.fake_loader()
                if orphan:
                    (self.runtime / "agents.dex").unlink()
                    (self.state / "residents").mkdir(parents=True)
                else:
                    (self.runtime / "agents.dex").write_bytes(b"dex")
                for code in (0, 7):
                    self.environment["PROBE_EXIT"] = str(code)
                    result = self.run_helper("quiesce-runtime-device.sh")
                    self.assertEqual(result.returncode, 0 if code == 0 else 1)
                    self.assertEqual((self.root / "calls").read_text().splitlines(),
                                     ["--runtime-dir", str(self.runtime), "--state-dir", str(self.state), "--quiesce", "--control-timeout", "60s"])

    def test_missing_loader_cannot_be_treated_as_drained(self):
        self.runtime.mkdir(parents=True)
        (self.runtime / "agents.dex").write_bytes(b"dex")
        self.assertNotEqual(self.run_helper("quiesce-runtime-device.sh").returncode, 0)
        self.assertFalse((self.root / "calls").exists())

    def test_fresh_or_legacy_install_has_no_native_actor_to_quiesce(self):
        self.assertEqual(self.run_helper("quiesce-runtime-device.sh").returncode, 0)
        self.assertFalse((self.root / "calls").exists())


if __name__ == "__main__":
    unittest.main()
