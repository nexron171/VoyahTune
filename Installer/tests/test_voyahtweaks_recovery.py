#!/usr/bin/env python3
"""Run the actual OD detection and isolation scripts on a fake device filesystem."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "crates/installer-core/src"
WATCHDOG = "(\nwhile [ 1 ]; do\n  /system/bin/sh /data/local/tmp/load.bin\n  sleep 2\ndone\n) &\n"
STARTUP = (
    "#!/system/bin/sh\nstart_logcat\nchmod +x /data/local/tmp/load.bin\n"
    + WATCHDOG
    + "wait $logcat_pid\n"
)
CLEAN_STARTUP = (
    SOURCE.parents[3] / "Packaging/system/init.logcat.original.sh"
).read_text()


class VoyahTweaksRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(
            prefix="isolated-device-", dir="/tmp"
        )
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.environment = dict(os.environ, FAKE_DEVICE=str(self.root))
        self.environment["PATH"] = f"{self.root}/bin:/usr/bin:/bin"
        self.write("proc/sys/kernel/random/boot_id", "boot-one\n")
        self.write("proc/mounts", "device / ext4 rw,seclabel 0 0\n")
        self.write("system/etc/init.logcat.sh", self.relocate(STARTUP))
        self.write(
            "data/local/tmp/load.bin", "# VoyahTweaksSH ru.kachalin.voyahtweaks\n"
        )
        self.write("data/local/tmp/launcher.bin", "tweaks hook")
        self.write("data/local/tmp/frida-inject", "tweaks injector")
        self.write("data/local/bin/load.bin", "VoyahTune loader")
        self.write("data/local/bin/frida-inject", "VoyahTune injector")
        self.write("package-state", "1")
        self.write("processes", "PID ARGS\n1 init\n")
        self.command("id", "echo 0")
        self.command("sync", ":")
        self.command("sha256sum", 'shasum -a 256 "$1"')
        self.command("ps", 'cat "$FAKE_DEVICE/processes"')
        self.command(
            "mount",
            '''echo "$*" >> "$FAKE_DEVICE/mount-calls"
sed 's/ ro,/ rw,/' "$FAKE_DEVICE/proc/mounts" > "$FAKE_DEVICE/proc/mounts.new"
mv "$FAKE_DEVICE/proc/mounts.new" "$FAKE_DEVICE/proc/mounts"''',
        )
        self.command("date", 'echo "2026-10-18_14:25:10"')
        self.command("chcon", ":")
        self.command(
            "ls",
            'if [ "$1" = -Zd ]; then echo "u:object_r:system_file:s0 $2"; else /bin/ls "$@"; fi',
        )
        self.command(
            "pm",
            """case "$1" in
 list)
   echo package:android
   [ "$(cat "$FAKE_DEVICE/package-state")" != absent ] || exit 0
   if [ "$3" != -d ] || [ "$(cat "$FAKE_DEVICE/package-state")" = 3 ]; then
     echo package:ru.kachalin.voyahtweaks
   fi ;;
 disable-user) echo 3 > "$FAKE_DEVICE/package-state" ;;
 enable) echo 1 > "$FAKE_DEVICE/package-state" ;;
 default-state) echo 0 > "$FAKE_DEVICE/package-state" ;;
 disable) echo 2 > "$FAKE_DEVICE/package-state" ;;
 disable-until-used) echo 4 > "$FAKE_DEVICE/package-state" ;;
 *) exit 1 ;;
esac""",
        )
        self.command(
            "dumpsys",
            'echo "User 0: installed=true enabled=$(cat "$FAKE_DEVICE/package-state")"',
        )

    def relocate(self, script):
        script = script.replace("/system/bin/sh -n", "/bin/sh -n")
        for name in [
            "data",
            "system",
            "proc",
            "vendor",
            "odm",
            "product",
            "system_ext",
        ]:
            script = script.replace(f"/{name}/", f"{self.root}/{name}/")
            script = script.replace(
                r"\/" + name + r"\/",
                str(self.root).replace("/", r"\/") + r"\/" + name + r"\/",
            )
        return script

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
        return target

    def command(self, name, content):
        self.write(f"bin/{name}", f"#!/bin/sh\n{content}\n").chmod(0o755)

    def run_script(self, script, success=True):
        result = subprocess.run(
            ["/bin/sh"],
            input=script,
            env=self.environment,
            text=True,
            capture_output=True,
            timeout=10,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        return result.stdout

    def action(self, action, success=True):
        script = (SOURCE / "voyahtweaks_recovery.sh").read_text()
        script = script.replace("__VOYAHTWEAKS_CLEAN_STARTUP__", CLEAN_STARTUP.rstrip())
        script = script.replace(
            "__VOYAHTWEAKS_SYSTEM_ACCESS__",
            (SOURCE / "cunba_system_access.sh").read_text().rstrip(),
        )
        script = script.replace(
            "__STARTUP_BACKUP__",
            (SOURCE / "startup_backup.sh").read_text().rstrip(),
        )
        return self.run_script(f"action={action}\n{self.relocate(script)}", success)

    def probe(self, verifying=False, success=True):
        script = self.relocate((SOURCE / "voyahtweaks_probe.sh").read_text())
        return self.run_script(
            f"VOYAHTWEAKS_RECOVERY_VERIFY={int(verifying)}\n{script}", success
        )

    def startup_backups(self):
        return list((self.root / "system/etc").glob("init.logcat.sh_*_backup"))

    def assert_original(self):
        self.assertEqual(
            (self.root / "system/etc/init.logcat.sh").read_text(),
            self.relocate(STARTUP),
        )
        self.assertEqual((self.root / "package-state").read_text().strip(), "1")
        self.assertEqual(
            (self.root / "data/local/tmp/launcher.bin").read_text(), "tweaks hook"
        )
        self.assertEqual(
            (self.root / "data/local/bin/load.bin").read_text(), "VoyahTune loader"
        )

    def test_disable_requires_new_boot_and_keeps_oem_and_voyahtune(self):
        self.assertIn("VOYAHTWEAKS_FOUND", self.probe())
        self.action("prepare")
        self.action("apply")
        self.assertEqual(self.probe(verifying=True).strip(), "VOYAHTWEAKS_CLEAR")
        self.assertIn("VOYAHTWEAKS_FOUND", self.probe())
        self.action("commit", success=False)
        self.write("proc/sys/kernel/random/boot_id", "boot-two\n")
        self.action("commit")
        self.assertEqual(self.probe().strip(), "VOYAHTWEAKS_CLEAR")
        startup = (self.root / "system/etc/init.logcat.sh").read_text()
        self.assertEqual(startup, self.relocate(CLEAN_STARTUP))
        self.assertEqual(
            (self.root / "data/local/bin/frida-inject").read_text(),
            "VoyahTune injector",
        )
        self.assertTrue(
            (
                self.root / "data/local/voyahtune-voyahtweaks-recovery/files/load.bin"
            ).exists()
        )
        backup = self.root / "system/etc/init.logcat.sh_2026-10-18_14:25:10_backup"
        self.assertEqual(self.startup_backups(), [backup])
        self.assertEqual(backup.read_text(), self.relocate(STARTUP))
        self.assertFalse((self.root / "data/local/voyahtune-install.lock").exists())

    def test_rollback_restores_files_startup_and_package(self):
        self.action("prepare")
        self.action("apply")
        self.action("rollback")
        self.assert_original()

    def test_foreign_startup_is_replaced_whole_and_restored(self):
        original = self.relocate(STARTUP + "sh /data/local/tmp/load.bin --unknown\n")
        startup = self.write("system/etc/init.logcat.sh", original)
        startup.chmod(0o750)
        self.action("prepare")
        self.action("apply")
        self.assertEqual(startup.read_text(), self.relocate(CLEAN_STARTUP))
        self.assertEqual(startup.stat().st_mode & 0o777, 0o750)
        self.assertEqual(self.startup_backups()[0].read_text(), original)
        self.action("rollback")
        self.assertEqual(startup.read_text(), original)
        self.assertTrue((self.root / "data/local/tmp/load.bin").exists())
        self.assertFalse((self.root / "data/local/voyahtune-install.lock").exists())

    def test_foreign_loader_and_symlink_are_not_modified(self):
        self.write("system/etc/init.logcat.sh", self.relocate(CLEAN_STARTUP))
        self.write("data/local/tmp/load.bin", "foreign loader")
        self.action("prepare", success=False)
        self.assertFalse((self.root / "data/local/voyahtune-install.lock").exists())
        self.write("system/etc/init.logcat.sh", self.relocate(STARTUP))
        loader = self.root / "data/local/tmp/load.bin"
        loader.unlink()
        loader.symlink_to(self.root / "data/local/bin/load.bin")
        self.action("prepare", success=False)
        self.assert_original()

    def assert_single_loader_marker(self, marker):
        self.write("data/local/tmp/load.bin", f"# {marker}\n")
        original = self.relocate(CLEAN_STARTUP)
        self.write("system/etc/init.logcat.sh", original)
        self.action("prepare")
        self.action("apply")
        self.assertEqual(self.probe(verifying=True).strip(), "VOYAHTWEAKS_CLEAR")
        self.assertEqual(
            (self.root / "system/etc/init.logcat.sh").read_text(), original
        )
        self.assertEqual(self.startup_backups(), [])

    def test_log_tag_alone_identifies_loader(self):
        self.assert_single_loader_marker("VoyahTweaksSH")

    def test_package_name_alone_identifies_loader(self):
        self.assert_single_loader_marker("ru.kachalin.voyahtweaks")

    def test_startup_without_loader_or_package_can_be_disabled(self):
        for path in (self.root / "data/local/tmp").iterdir():
            path.unlink()
        self.write("package-state", "absent")
        self.assertIn("startup:", self.probe())
        self.action("prepare")
        self.action("apply")
        self.assertEqual(self.probe(verifying=True).strip(), "VOYAHTWEAKS_CLEAR")
        self.assertEqual(self.startup_backups()[0].read_text(), self.relocate(STARTUP))
        self.action("rollback")
        self.assertEqual(
            (self.root / "system/etc/init.logcat.sh").read_text(),
            self.relocate(STARTUP),
        )

    def test_existing_timestamp_backup_is_preserved(self):
        backup = self.write(
            "system/etc/init.logcat.sh_2026-10-18_14:25:10_backup", "previous backup"
        )
        self.action("prepare")
        self.action("apply")
        self.assertEqual(backup.read_text(), "previous backup")
        self.assertEqual(len(self.startup_backups()), 2)
        self.assertEqual(
            (
                self.root / "system/etc/init.logcat.sh_2026-10-18_14:25:10_1_backup"
            ).read_text(),
            self.relocate(STARTUP),
        )

    def test_backup_failure_prevents_changes(self):
        self.action("prepare")
        self.command("cp", 'case "$3" in *_backup) exit 1 ;; *) /bin/cp "$@" ;; esac')
        self.action("apply", success=False)
        self.assert_original()

    def test_failed_atomic_replacement_preserves_original(self):
        self.action("prepare")
        self.command("chcon", "exit 1")
        self.action("apply", success=False)
        self.assertEqual(
            (self.root / "system/etc/init.logcat.sh").read_text(),
            self.relocate(STARTUP),
        )
        self.action("rollback")
        self.assert_original()

    def test_ota_lock_is_preserved(self):
        self.write("data/local/voyahtune-install.lock/owner", "ota:other")
        self.action("prepare", success=False)
        self.assertEqual(
            (self.root / "data/local/voyahtune-install.lock/owner").read_text(),
            "ota:other",
        )
        self.assert_original()

    def test_changed_file_aborts_and_rolls_back_partial_moves(self):
        self.action("prepare")
        self.write("data/local/tmp/launcher.bin", "changed hook")
        self.action("apply", success=False)
        self.action("rollback")
        self.assertTrue((self.root / "data/local/tmp/load.bin").exists())
        self.assertEqual(
            (self.root / "data/local/tmp/launcher.bin").read_text(), "changed hook"
        )
        self.assertEqual(
            (self.root / "system/etc/init.logcat.sh").read_text(),
            self.relocate(STARTUP),
        )

    def test_probe_detects_other_startup_and_process_after_isolation(self):
        self.action("prepare")
        self.action("apply")
        self.write(
            "vendor/etc/init/other.rc",
            self.relocate("service tweaks /data/local/tmp/load.bin\n"),
        )
        self.assertIn("startup:", self.probe(verifying=True))
        (self.root / "vendor/etc/init/other.rc").unlink()
        self.write(
            "processes", self.relocate("PID ARGS\n42 sh /data/local/tmp/load.bin\n")
        )
        self.assertIn("process:", self.probe(verifying=True))

    def test_read_only_mount_is_prepared_for_following_installation(self):
        self.write("proc/mounts", "device / ext4 ro,seclabel 0 0\n")
        self.action("prepare")
        self.action("apply")
        self.assertEqual(
            (self.root / "mount-calls").read_text().splitlines(), ["-o remount,rw /"]
        )

    def test_failed_inventory_never_passes(self):
        self.command("pm", "exit 1")
        for path in (self.root / "data/local/tmp").iterdir():
            path.unlink()
        self.probe(success=False)


if __name__ == "__main__":
    unittest.main()
