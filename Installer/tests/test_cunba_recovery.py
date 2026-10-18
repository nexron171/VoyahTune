#!/usr/bin/env python3
"""Exercise the device transaction on an isolated fake filesystem, without ADB."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "crates/installer-core/src"


class CunbaRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="isolated-device-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.environment = dict(os.environ, FAKE_DEVICE=str(self.root))
        self.environment["PATH"] = f"{self.root}/bin:/usr/bin:/bin"
        self.clean_startup = (
            SOURCE.parents[3] / "Packaging/od/system/init.logcat.original.sh"
        ).read_text().rstrip() + "\n"
        self.script = (SOURCE / "cunba_recovery.sh").read_text().replace(
            "__CUNBA_CLEAN_STARTUP__", self.clean_startup.rstrip()
        )
        self.script = self.expand_system_access(self.script)
        self.script = self.script.replace("/system/bin/sh -n", "/bin/sh -n")
        for name in ["data", "system", "proc", "sdcard", "storage"]:
            self.script = self.script.replace(f"/{name}/", f"{self.root}/{name}/")
        self.script = self.script.replace(r"\/sdcard\/", str(self.root).replace("/", r"\/") + r"\/sdcard\/")
        self.write("proc/sys/kernel/random/boot_id", "boot-one\n")
        self.write("proc/mounts", "device / ext4 rw,seclabel 0 0\n")
        self.write("data/local/bin/.keep", "")
        self.write("system/etc/init.logcat.sh", "#!/system/bin/sh\necho OEM\n")
        self.write("package-state", "1")
        self.command("id", "echo 0")
        self.command("sync", ":")
        self.command("mount", '''echo "$*" >> "$FAKE_DEVICE/mount-calls"
cat "$FAKE_DEVICE/package-state" >> "$FAKE_DEVICE/package-state-at-remount"
[ "$1" = -o ] && [ "$2" = remount,rw ] || exit 1
sed 's/ ro,/ rw,/' "$FAKE_DEVICE/proc/mounts" > "$FAKE_DEVICE/proc/mounts.new"
mv "$FAKE_DEVICE/proc/mounts.new" "$FAKE_DEVICE/proc/mounts"''')
        self.command(
            "ls",
            'if [ "$1" = -Zd ]; then echo "u:object_r:system_file:s0 $2"; else /bin/ls "$@"; fi',
        )
        self.command("chcon", ":")
        self.command("sha256sum", 'shasum -a 256 "$1"')
        self.command("pm", '''case "$1" in
 list)
   echo package:android
   if [ "$3" != -d ] || [ "$(cat "$FAKE_DEVICE/package-state")" = 3 ]; then
     echo package:com.cunba.lantu.otan.motorstore
   fi ;;
 disable-user) echo 3 > "$FAKE_DEVICE/package-state" ;;
 enable) echo 1 > "$FAKE_DEVICE/package-state" ;;
 default-state) echo 0 > "$FAKE_DEVICE/package-state" ;;
 disable) echo 2 > "$FAKE_DEVICE/package-state" ;;
 disable-until-used) echo 4 > "$FAKE_DEVICE/package-state" ;;
 *) exit 1 ;;
esac''')
        self.command(
            "dumpsys",
            'echo "User 0: installed=true enabled=$(cat "$FAKE_DEVICE/package-state")"',
        )
        self.write("data/media/0/Download/cunba/patch/load.bin", "known")
        self.write("data/media/0/Download/cunba/overlay/user.json", "user data")
        self.write("data/local/bin/frida-inject2", "injector")
        self.write("data/local/bin/frida-inject", "VoyahTune injector")
        self.write("data/local/bin/load.bin", "VoyahTune loader")

    def expand_system_access(self, script):
        return script.replace(
            "__CUNBA_SYSTEM_ACCESS__",
            (SOURCE / "cunba_system_access.sh").read_text().rstrip(),
        ).replace(
            "__OD_STARTUP_BACKUP__",
            (SOURCE / "od_startup_backup.sh").read_text().rstrip(),
        )

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        return target

    def command(self, name, text):
        self.write(f"bin/{name}", f"#!/bin/sh\n{text}\n").chmod(0o755)

    def run_action(self, action, success=True):
        result = subprocess.run(
            ["/bin/sh"],
            input=f"action={action}\n{self.script}",
            env=self.environment,
            text=True,
            capture_output=True,
            timeout=10,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def assert_original(self):
        self.assertEqual((self.root / "package-state").read_text().strip(), "1")
        self.assertEqual((self.root / "data/local/bin/frida-inject2").read_text(), "injector")
        self.assertEqual(
            (self.root / "data/media/0/Download/cunba/overlay/user.json").read_text(),
            "user data",
        )
        self.assertEqual(
            (self.root / "data/local/bin/frida-inject").read_text(),
            "VoyahTune injector",
        )
        self.assertEqual((self.root / "data/local/bin/load.bin").read_text(), "VoyahTune loader")

    def test_success_requires_new_boot_and_keeps_backups(self):
        self.run_action("prepare")
        self.run_action("apply")
        self.run_action("commit", success=False)
        self.write("proc/sys/kernel/random/boot_id", "boot-two\n")
        self.run_action("commit")
        self.assertFalse((self.root / "data/local/bin/frida-inject2").exists())
        self.assertTrue((self.root / "data/local/voyahtune-cunba-recovery/injector").exists())
        self.assertFalse((self.root / "data/local/voyahtune-install.lock").exists())

    def test_rollback_restores_state_and_preserves_regenerated_files(self):
        self.run_action("prepare")
        self.run_action("apply")
        self.write("data/media/0/Download/cunba/new.txt", "regenerated")
        self.run_action("rollback")
        self.assert_original()
        self.assertEqual(
            (self.root / "data/local/voyahtune-cunba-recovery/regenerated-directory/new.txt").read_text(),
            "regenerated",
        )

    def test_different_loader_and_injector_contents_are_restored(self):
        loader = self.write("data/media/0/Download/cunba/patch/load.bin", "another loader version")
        injector = self.write("data/local/bin/frida-inject2", "another injector version")
        self.run_action("prepare")
        self.run_action("apply")
        self.assertFalse(loader.exists())
        self.assertFalse(injector.exists())
        self.run_action("rollback")
        self.assertEqual(loader.read_text(), "another loader version")
        self.assertEqual(injector.read_text(), "another injector version")

    def test_loader_symlink_is_rejected_without_changes(self):
        loader = self.root / "data/media/0/Download/cunba/patch/load.bin"
        loader.unlink()
        loader.symlink_to(self.root / "data/local/bin/load.bin")
        self.run_action("prepare", success=False)
        self.assert_original()
        self.assertFalse((self.root / "data/local/voyahtune-install.lock").exists())

    def test_unknown_startup_is_replaced_and_restored_whole(self):
        original = "# custom cunba boot\nexec /unknown/cunba-loader --watch\n"
        startup = self.write("system/etc/init.logcat.sh", original)
        startup.chmod(0o750)
        self.run_action("prepare")
        self.run_action("apply")
        backups = list(startup.parent.glob("init.logcat.sh_*_backup"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(), original)
        self.assertNotIn("cunba", startup.read_text())
        self.assertIn("start_logcat", startup.read_text())
        self.assertEqual(startup.stat().st_mode & 0o777, 0o750)
        self.run_action("rollback")
        self.assertEqual(startup.read_text(), original)
        self.assert_original()

    def test_startup_backup_failure_prevents_disabling(self):
        self.run_action("prepare")
        self.command("cp", 'case "$3" in *_backup) exit 1 ;; *) /bin/cp "$@" ;; esac')
        self.run_action("apply", success=False)
        self.assert_original()
        self.assertEqual(
            (self.root / "system/etc/init.logcat.sh").read_text(),
            "#!/system/bin/sh\necho OEM\n",
        )

    def test_failed_atomic_replacement_keeps_original(self):
        startup = self.root / "system/etc/init.logcat.sh"
        original = startup.read_text()
        self.run_action("prepare")
        self.command("chcon", 'exit 1')
        self.run_action("apply", success=False)
        self.assertEqual(startup.read_text(), original)
        self.assertFalse(Path(str(startup) + ".voyahtune-cunba-new").exists())
        self.run_action("rollback")
        self.assert_original()

    def test_read_only_root_is_prepared_before_disabling_and_stays_writable(self):
        self.write("proc/mounts", "device / ext4 ro,seclabel 0 0\n")
        self.run_action("prepare")
        self.run_action("apply")
        self.assertEqual((self.root / "mount-calls").read_text(), "-o remount,rw /\n")
        self.assertEqual((self.root / "package-state-at-remount").read_text().strip(), "1")
        self.assertIn("rw,seclabel", (self.root / "proc/mounts").read_text())
        self.assertFalse(list((self.root / "system/etc").glob("*.voyahtune-write-test.*")))
        self.run_action("rollback")
        self.assertEqual((self.root / "mount-calls").read_text(), "-o remount,rw /\n")
        self.assert_original()

    def test_remount_uses_system_mount_instead_of_root(self):
        target = self.root / "system"
        self.write(
            "proc/mounts",
            f"root / ext4 rw,seclabel 0 0\nsystem {target} ext4 ro,seclabel 0 0\n",
        )
        self.run_action("prepare")
        self.run_action("apply")
        self.assertEqual((self.root / "mount-calls").read_text(), f"-o remount,rw {target}\n")

    def test_failed_remount_does_not_disable_components(self):
        self.write("proc/mounts", "device / ext4 ro,seclabel 0 0\n")
        self.command("mount", "exit 1")
        self.run_action("prepare")
        failure = self.run_action("apply", success=False)
        self.assertIn("Read-only file system", failure.stderr)
        self.assert_original()
        self.assertEqual(
            (self.root / "system/etc/init.logcat.sh").read_text(),
            "#!/system/bin/sh\necho OEM\n",
        )
        self.assertEqual(
            (self.root / "data/local/voyahtune-cunba-recovery/state").read_text().strip(),
            "prepared",
        )
        self.run_action("rollback")
        self.assert_original()

    def test_successful_remount_without_write_access_does_not_disable_components(self):
        self.write("proc/mounts", "device / ext4 ro,seclabel 0 0\n")
        self.command("mktemp", 'echo "Read-only file system" >&2; exit 1')
        self.run_action("prepare")
        self.run_action("apply", success=False)
        self.assert_original()
        self.assertFalse(
            (self.root / "data/local/voyahtune-cunba-recovery/startup-write-started").exists(),
        )
        self.run_action("rollback")

    def test_rollback_after_reboot_prepares_read_only_system(self):
        self.run_action("prepare")
        self.run_action("apply")
        self.write("proc/sys/kernel/random/boot_id", "boot-two\n")
        self.write("proc/mounts", "device / ext4 ro,seclabel 0 0\n")
        self.run_action("rollback")
        self.assertEqual((self.root / "mount-calls").read_text(), "-o remount,rw /\n")
        self.assertEqual(
            (self.root / "system/etc/init.logcat.sh").read_text(),
            "#!/system/bin/sh\necho OEM\n",
        )
        self.assert_original()

    def test_failed_rollback_remount_can_be_retried(self):
        self.run_action("prepare")
        self.run_action("apply")
        self.write("proc/mounts", "device / ext4 ro,seclabel 0 0\n")
        mount = (self.root / "bin/mount").read_text()
        self.command("mount", "exit 1")
        self.run_action("rollback", success=False)
        self.assertEqual(
            (self.root / "data/local/voyahtune-cunba-recovery/state").read_text().strip(),
            "restoring",
        )
        self.assertTrue((self.root / "data/local/voyahtune-install.lock").exists())
        (self.root / "bin/mount").write_text(mount)
        self.run_action("rollback")
        self.assert_original()

    def test_read_only_failure_can_retry_with_archived_journal(self):
        self.run_action("prepare")
        self.command("cp", '''case "$2" in
 "$FAKE_DEVICE/data/local/voyahtune-cunba-recovery/startup")
   echo 'Read-only file system' >&2; exit 1 ;;
esac
exec /bin/cp "$@"''')
        self.run_action("apply", success=False)
        self.run_action("rollback")
        self.run_action("archive-rollback")
        archives = list((self.root / "data/local/voyahtune-cunba-history").glob("attempt.*/journal"))
        self.assertEqual(len(archives), 1)
        self.assertEqual((archives[0] / "state").read_text().strip(), "rolled-back")
        self.assertEqual((archives[0] / "startup").read_text(), "#!/system/bin/sh\necho OEM\n")
        self.assertFalse((self.root / "data/local/voyahtune-cunba-recovery").exists())
        self.assertFalse((self.root / "data/local/voyahtune-install.lock").exists())
        self.command("cp", 'exec /bin/cp "$@"')
        self.run_action("prepare")
        self.run_action("apply")
        self.write("proc/sys/kernel/random/boot_id", "boot-two\n")
        self.run_action("commit")
        self.assertTrue(archives[0].exists())

    def test_retry_preserves_multiple_journals(self):
        for _ in range(2):
            self.run_action("prepare")
            self.run_action("rollback")
            self.run_action("archive-rollback")
        archives = list((self.root / "data/local/voyahtune-cunba-history").glob("attempt.*/journal"))
        self.assertEqual(len(archives), 2)

    def test_startup_only_failure_can_retry_without_a_cunba_package(self):
        self.command("pm", "echo package:android")
        (self.root / "data/local/bin/frida-inject2").unlink()
        source = self.root / "data/media/0/Download/cunba"
        for path in sorted(source.rglob("*"), reverse=True):
            if path.is_dir():
                path.rmdir()
            else:
                path.unlink()
        source.rmdir()
        self.run_action("prepare")
        self.run_action("rollback")
        self.run_action("archive-rollback")
        self.run_action("prepare")
        self.assertEqual(
            (self.root / "data/local/voyahtune-cunba-recovery/package-state").read_text().strip(),
            "absent",
        )

    def test_changed_rollback_evidence_blocks_retry(self):
        self.run_action("prepare")
        self.run_action("apply")
        self.run_action("rollback")
        changes = {
            "system/etc/init.logcat.sh": "changed startup",
            "data/local/bin/frida-inject2": "changed injector",
            "data/local/voyahtune-cunba-recovery/startup": "changed backup",
            "data/local/voyahtune-cunba-recovery/state": "applying",
            "package-state": "3",
        }
        for name, content in changes.items():
            with self.subTest(path=name):
                path = self.root / name
                original = path.read_text()
                path.write_text(content)
                self.run_action("archive-rollback", success=False)
                self.assertTrue((self.root / "data/local/voyahtune-cunba-recovery").exists())
                self.assertFalse((self.root / "data/local/voyahtune-install.lock").exists())
                path.write_text(original)

    def test_retry_keeps_foreign_lock_and_quarantined_data(self):
        self.run_action("prepare")
        self.run_action("rollback")
        self.write("data/local/voyahtune-install.lock/owner", "ota:other")
        self.run_action("archive-rollback", success=False)
        self.assertEqual(
            (self.root / "data/local/voyahtune-install.lock/owner").read_text(),
            "ota:other",
        )
        (self.root / "data/local/voyahtune-install.lock/owner").unlink()
        (self.root / "data/local/voyahtune-install.lock").rmdir()
        saved = self.write("data/local/voyahtune-cunba-recovery/injector", "saved data")
        self.run_action("archive-rollback", success=False)
        self.assertEqual(saved.read_text(), "saved data")

    def test_changed_backup_blocks_rollback(self):
        self.run_action("prepare")
        self.run_action("apply")
        self.write("data/local/voyahtune-cunba-recovery/startup", "corrupt")
        self.run_action("rollback", success=False)
        self.assertIn("start_logcat", (self.root / "system/etc/init.logcat.sh").read_text())

    def test_foreign_lock_is_not_removed(self):
        self.write("data/local/voyahtune-install.lock/owner", "ota:other")
        self.run_action("prepare", success=False)
        self.assertEqual(
            (self.root / "data/local/voyahtune-install.lock/owner").read_text(),
            "ota:other",
        )
        self.assert_original()

    def test_prepared_transaction_can_rollback_without_apply(self):
        self.run_action("prepare")
        self.run_action("rollback")
        self.assert_original()

    def test_supported_startup_is_restored_exactly(self):
        original = (
            "#!/system/bin/sh\necho OEM\n"
            f"sh {self.root}/sdcard/Download/cunba/patch/load.bin &\n"
            "echo keep\n"
        )
        startup = self.write("system/etc/init.logcat.sh", original)
        self.run_action("prepare")
        self.run_action("apply")
        self.assertNotIn("cunba/patch", startup.read_text())
        self.assertIn("start_logcat", startup.read_text())
        self.assertNotIn("echo keep\n", startup.read_text())
        self.run_action("rollback")
        self.assertEqual(startup.read_text(), original)
        self.assert_original()

    def test_package_not_disabled_aborts_and_can_restore(self):
        self.run_action("prepare")
        self.command("pm", 'echo package:android')
        self.run_action("apply", success=False)
        self.assertTrue((self.root / "data/local/bin/frida-inject2").exists())
        self.run_action("rollback")
        self.assert_original()

    def test_probe_accepts_only_verified_disabled_package(self):
        self.run_action("prepare")
        self.run_action("apply")
        self.write("proc/sys/kernel/random/boot_id", "boot-two\n")
        self.run_action("commit")
        self.command("ps", 'echo "PID ARGS"; echo "1 init"')
        probe = (SOURCE / "cunba_probe.sh").read_text()
        for name in ["data", "system", "proc", "sdcard", "storage", "vendor", "odm", "product", "system_ext"]:
            probe = probe.replace(f"/{name}/", f"{self.root}/{name}/")
        def check():
            return subprocess.run(["/bin/sh"], input=probe, env=self.environment,
                                  text=True, capture_output=True, timeout=10).stdout
        self.assertEqual(check().strip(), "CUNBA_CLEAR")
        self.write("package-state", "1")
        self.assertIn("CUNBA_FOUND", check())
        self.write("package-state", "3")
        self.write("data/local/voyahtune-cunba-recovery/state", "reboot-pending")
        self.assertIn("CUNBA_FOUND", check())

    def test_changed_injector_blocks_rollback_without_overwriting_it(self):
        self.run_action("prepare")
        self.run_action("apply")
        self.write("data/local/bin/frida-inject2", "unexpected")
        self.command("sha256sum", 'shasum -a 256 "$1"')
        self.run_action("rollback", success=False)
        self.assertEqual((self.root / "data/local/bin/frida-inject2").read_text(), "unexpected")
        self.assertTrue((self.root / "data/local/voyahtune-cunba-recovery/injector").exists())

    def run_restore(self, action, success=True):
        script = self.expand_system_access((SOURCE / "cunba_restore.sh").read_text())
        script = script.replace("/system/bin/sh -n", "/bin/sh -n")
        for name in ["data", "system", "proc", "sdcard", "storage"]:
            script = script.replace(f"/{name}/", f"{self.root}/{name}/")
        script = script.replace(r"\/sdcard\/", str(self.root).replace("/", r"\/") + r"\/sdcard\/")
        result = subprocess.run(
            ["/bin/sh"],
            input=f"action={action}\n{script}",
            env=self.environment,
            text=True,
            capture_output=True,
            timeout=10,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def disable_for_restore(self):
        self.command("ps", 'echo "PID ARGS"; echo "1 init"')
        self.run_action("prepare")
        self.run_action("apply")
        self.write("proc/sys/kernel/random/boot_id", "disabled-boot")
        self.run_action("commit")

    def test_restore_availability_requires_verified_backup(self):
        self.assertEqual(self.run_restore("available").stdout.strip(), "RESTORE_UNAVAILABLE")
        self.disable_for_restore()
        self.assertEqual(self.run_restore("available").stdout.strip(), "RESTORE_AVAILABLE")
        self.write("data/local/voyahtune-cunba-recovery/injector", "corrupt")
        self.assertEqual(self.run_restore("available").stdout.strip(), "RESTORE_UNAVAILABLE")

    def test_restore_after_removal_requires_new_boot(self):
        self.disable_for_restore()
        self.run_restore("restore")
        self.assert_original()
        self.run_restore("verify", success=False)
        self.write("proc/sys/kernel/random/boot_id", "restored-boot")
        self.run_restore("verify")
        self.run_restore("finish")
        self.assertEqual(self.run_restore("available").stdout.strip(), "RESTORE_UNAVAILABLE")

    def test_restore_after_removal_prepares_read_only_system(self):
        self.disable_for_restore()
        self.write("proc/mounts", "device / ext4 ro,seclabel 0 0\n")
        self.run_restore("restore")
        self.assertEqual((self.root / "mount-calls").read_text(), "-o remount,rw /\n")
        self.assert_original()

    def test_restore_returns_complete_original_startup(self):
        self.write(
            "system/etc/init.logcat.sh",
            f"#!/system/bin/sh\necho old OEM\nsh {self.root}/sdcard/Download/cunba/patch/load.bin &\n",
        )
        self.disable_for_restore()
        startup = self.write(
            "system/etc/init.logcat.sh",
            "#!/system/bin/sh\necho new OEM\nexit 0\n",
        )
        self.run_restore("restore")
        content = startup.read_text()
        self.assertNotIn("echo new OEM", content)
        self.assertEqual(
            content,
            (self.root / "data/local/voyahtune-cunba-recovery/startup").read_text(),
        )
        self.run_restore("restore")
        self.assertEqual(startup.read_text(), content)

    def test_restore_refuses_existing_voyahtune(self):
        self.disable_for_restore()
        self.write("data/local/bin/voyahtune-updater", "installed")
        self.run_restore("restore", success=False)
        self.assertFalse((self.root / "data/local/bin/frida-inject2").exists())
        self.assertEqual((self.root / "package-state").read_text().strip(), "3")

    def test_restore_does_not_overwrite_new_cunba_directory(self):
        self.disable_for_restore()
        current = self.write("data/media/0/Download/cunba/new.txt", "new data")
        self.run_restore("restore", success=False)
        self.assertEqual(current.read_text(), "new data")
        self.assertTrue((self.root / "data/local/voyahtune-cunba-recovery/directory").exists())

    def test_restore_replaces_unrecognized_current_startup(self):
        self.disable_for_restore()
        startup = self.write(
            "system/etc/init.logcat.sh",
            "exec /sdcard/Download/cunba/patch/load.bin --custom\n",
        )
        self.run_restore("restore")
        self.assertEqual(
            startup.read_text(),
            (self.root / "data/local/voyahtune-cunba-recovery/startup").read_text(),
        )
        self.assert_original()

    def test_restore_retries_after_package_manager_failure(self):
        self.disable_for_restore()
        package_manager = (self.root / "bin/pm").read_text()
        self.command("pm", 'if [ "$1" = enable ]; then exit 1; fi\necho package:android')
        self.run_restore("restore", success=False)
        self.assertFalse((self.root / "data/local/voyahtune-install.lock").exists())
        (self.root / "bin/pm").write_text(package_manager)
        self.run_restore("restore")
        self.assert_original()


if __name__ == "__main__":
    unittest.main()
