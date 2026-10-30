#!/usr/bin/env python3
"""Run the production backup helper against real files and Android command stubs."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "Packaging/installer/common/application-data.sh"
PACKAGE = "ru.big.town.restoremode"


class ApplicationDataTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="application-data-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.backup = self.root / "data/local/voyahtune-application-backups" / PACKAGE
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.environment = dict(os.environ, TEST_ROOT=str(self.root),
                                TEST_UID=str(os.getuid()), TEST_LOCKED="0", TEST_FAIL="")
        tool_path = "/opt/homebrew/opt/coreutils/libexec/gnubin"
        self.environment["PATH"] = f"{self.bin}:{tool_path}:{os.environ['PATH']}"
        self.write_command("am", '''
if [ "$1" = get-started-user-state ]; then
    [ "$TEST_LOCKED" = 0 ] && echo RUNNING_UNLOCKED || echo RUNNING_LOCKED
fi
exit 0
''')
        self.write_command("pm", '''
if [ "$1" = path ]; then echo package:/data/app/base.apk
else echo "package:ru.big.town.restoremode uid:$TEST_UID"; fi
''')
        self.write_command("cmd", "exit 0\n")
        self.write_command("restorecon", '[ "$TEST_FAIL" != restorecon ]\n')
        self.write_command("sync", "exit 0\n")
        self.script = SCRIPT.read_text().replace("/data/", str(self.root / "data") + "/")

    def write_command(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)

    def directory(self, area, user=0):
        if area in ("ce", "de"):
            path = self.root / "data" / ("user" if area == "ce" else "user_de") / str(user)
        else:
            path = self.root / "data/media" / str(user) / "Android" / area
        return path / PACKAGE

    def seed(self, user=0):
        for area in ("ce", "de", "data", "media", "obb"):
            directory = self.directory(area, user)
            directory.mkdir(parents=True)
            (directory / ".hidden").write_bytes(b"\x00saved settings\xff")
            (directory / "shared_prefs").mkdir()
            (directory / "shared_prefs/settings.xml").write_text('<map><boolean name="enabled" value="true" /></map>')
            (directory / "database").mkdir()
            (directory / "database/main.db").write_text(f"{area}:{user}")
            (directory / "database/main.db-wal").write_text("uncheckpointed")
            (directory / "database/main.db").chmod(0o600)

    def run_helper(self, action, okay=True):
        result = subprocess.run(
            ["sh", "-c", f"application_data_action={action}\napplication_package={PACKAGE}\n{self.script}"],
            env=self.environment, text=True, capture_output=True, timeout=15,
        )
        self.assertEqual(result.returncode == 0, okay, result.stdout + result.stderr)
        return result

    def test_roundtrip_all_storage_and_users_after_deletion(self):
        for user in (0, 10):
            self.seed(user)
        self.run_helper("backup")
        for user in (0, 10):
            for area in ("ce", "de", "data", "media", "obb"):
                shutil.rmtree(self.directory(area, user))
            self.directory("ce", user).mkdir()
            (self.directory("ce", user) / "new-default").write_text("discard")
        self.run_helper("restore")
        for user in (0, 10):
            for area in ("ce", "de", "data", "media", "obb"):
                directory = self.directory(area, user)
                self.assertEqual((directory / ".hidden").read_bytes(), b"\x00saved settings\xff")
                self.assertEqual((directory / "shared_prefs/settings.xml").read_text(), '<map><boolean name="enabled" value="true" /></map>')
                self.assertEqual((directory / "database/main.db").read_text(), f"{area}:{user}")
                self.assertEqual((directory / "database/main.db-wal").read_text(), "uncheckpointed")
                self.assertEqual((directory / "database/main.db").stat().st_mode & 0o777, 0o600)
                self.assertFalse((directory / "new-default").exists())
        self.assertTrue(self.backup.exists())
        self.run_helper("cleanup")
        self.assertFalse(self.backup.exists())
        self.run_helper("restore")

    def test_registered_package_does_not_wait_for_install_existing(self):
        self.seed()
        self.run_helper("backup")
        self.write_command("cmd", "echo unexpected-registration >&2\nexit 1\n")
        self.run_helper("restore")
        self.assertEqual((self.directory("ce") / "shared_prefs/settings.xml").read_text(),
                         '<map><boolean name="enabled" value="true" /></map>')

    def test_missing_user_registration_is_restored_without_waiting_for_launch(self):
        self.seed()
        self.run_helper("backup")
        self.write_command("pm", '''
if [ "$1" = path ]; then echo package:/data/app/base.apk
elif [ -f "$TEST_ROOT/registered" ]; then echo "package:ru.big.town.restoremode uid:$TEST_UID"; fi
''')
        self.write_command("cmd", '''
for argument in "$@"; do [ "$argument" != --wait ] || exit 1; done
touch "$TEST_ROOT/registered"
''')
        self.run_helper("restore")
        self.assertTrue((self.root / "registered").exists())
        self.assertEqual((self.directory("ce") / "database/main.db-wal").read_text(), "uncheckpointed")

    def test_existing_backup_is_not_overwritten_on_retry(self):
        self.seed()
        self.run_helper("backup")
        (self.directory("ce") / ".hidden").write_text("new defaults")
        self.run_helper("backup")
        self.run_helper("restore")
        self.assertEqual((self.directory("ce") / ".hidden").read_bytes(), b"\x00saved settings\xff")

    def test_corrupt_archive_stops_before_touching_data(self):
        self.seed()
        self.run_helper("backup")
        (self.backup / "ce-0.tar").write_bytes(b"corrupt")
        self.run_helper("restore", okay=False)
        self.assertEqual((self.directory("ce") / ".hidden").read_bytes(), b"\x00saved settings\xff")
        self.assertTrue(self.backup.exists())

    def test_failed_restore_keeps_backup_and_can_retry(self):
        self.seed()
        self.run_helper("backup")
        self.environment["TEST_FAIL"] = "restorecon"
        self.run_helper("restore", okay=False)
        self.assertTrue(self.backup.exists())
        self.environment["TEST_FAIL"] = ""
        self.run_helper("restore")
        self.run_helper("cleanup")
        self.assertFalse(self.backup.exists())

    def test_copy_failure_never_marks_backup_complete(self):
        self.seed()
        self.write_command("tar", "exit 1\n")
        self.run_helper("backup", okay=False)
        self.assertFalse(self.backup.exists())
        self.assertTrue((self.directory("ce") / ".hidden").exists())

    def test_locked_or_missing_ce_is_rejected(self):
        self.seed()
        self.environment["TEST_LOCKED"] = "1"
        self.run_helper("backup", okay=False)
        self.assertFalse(self.backup.exists())

    def test_missing_ce_with_existing_de_is_rejected(self):
        self.seed()
        shutil.rmtree(self.directory("ce"))
        self.run_helper("backup", okay=False)
        self.assertFalse(self.backup.exists())

    def test_new_uid_is_applied_and_ownership_failure_keeps_backup(self):
        self.seed()
        self.run_helper("backup")
        self.environment["TEST_UID"] = str(os.getuid() + 100)
        self.write_command("chown", 'printf "%s\\n" "$*" >> "$TEST_ROOT/ownership"\nexit 1\n')
        self.run_helper("restore", okay=False)
        self.assertTrue(self.backup.exists())
        self.assertIn(str(os.getuid() + 100), (self.root / "ownership").read_text())
        self.write_command("chown", "exit 0\n")
        self.run_helper("restore")
        self.run_helper("cleanup")
        self.assertFalse(self.backup.exists())

    def test_empty_install_has_valid_empty_backup(self):
        self.run_helper("backup")
        self.run_helper("restore")
        self.run_helper("cleanup")
        self.assertFalse(self.backup.exists())

    def test_cleanup_keeps_unrestored_backup(self):
        self.seed()
        self.run_helper("backup")
        self.run_helper("cleanup", okay=False)
        self.assertTrue((self.backup / "ce-0.tar").exists())

    def test_cleanup_removes_pending_and_restored_staging(self):
        for suffix in (".pending", ".restored"):
            directory = Path(str(self.backup) + suffix)
            directory.mkdir(parents=True)
            (directory / "temporary").write_text("data")
        self.run_helper("cleanup")
        self.assertFalse(self.backup.parent.exists())

    def test_backup_symlink_is_rejected(self):
        self.backup.parent.mkdir(parents=True)
        self.backup.symlink_to(self.directory("ce"))
        self.run_helper("backup", okay=False)


if __name__ == "__main__":
    unittest.main()
