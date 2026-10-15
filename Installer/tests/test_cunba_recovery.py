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
        self.script = (SOURCE / "cunba_recovery.sh").read_text()
        self.script = self.script.replace("/system/bin/sh -n", "/bin/sh -n")
        for name in ["data", "system", "proc", "sdcard", "storage"]:
            self.script = self.script.replace(f"/{name}/", f"{self.root}/{name}/")
        self.script = self.script.replace(r"\/sdcard\/", str(self.root).replace("/", r"\/") + r"\/sdcard\/")
        self.write("proc/sys/kernel/random/boot_id", "boot-one\n")
        self.write("data/local/bin/.keep", "")
        self.write("system/etc/init.logcat.sh", "#!/system/bin/sh\necho OEM\n")
        self.write("package-state", "1")
        self.command("id", "echo 0")
        self.command("sync", ":")
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
        self.command("dumpsys", 'echo "User 0: installed=true enabled=$(cat "$FAKE_DEVICE/package-state")"')
        self.write("data/media/0/Download/cunba/patch/load.bin", "known")
        self.write("data/media/0/Download/cunba/overlay/user.json", "user data")
        self.write("data/local/bin/frida-inject2", "injector")
        self.write("data/local/bin/frida-inject", "VoyahTune injector")
        self.write("data/local/bin/load.bin", "VoyahTune loader")

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        return target

    def command(self, name, text):
        self.write(f"bin/{name}", f"#!/bin/sh\n{text}\n").chmod(0o755)

    def run_action(self, action, success=True):
        result = subprocess.run(
            ["/bin/sh"], input=f"action={action}\n{self.script}",
            env=self.environment, text=True, capture_output=True, timeout=10,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def assert_original(self):
        self.assertEqual((self.root / "package-state").read_text().strip(), "1")
        self.assertEqual((self.root / "data/local/bin/frida-inject2").read_text(), "injector")
        self.assertEqual((self.root / "data/media/0/Download/cunba/overlay/user.json").read_text(), "user data")
        self.assertEqual((self.root / "data/local/bin/frida-inject").read_text(), "VoyahTune injector")
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
        self.assertEqual((self.root / "data/local/voyahtune-cunba-recovery/regenerated-directory/new.txt").read_text(), "regenerated")

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

    def test_unknown_startup_is_rejected_without_changes(self):
        self.write("system/etc/init.logcat.sh", "exec /unknown/cunba-loader --watch\n")
        self.run_action("prepare", success=False)
        self.assert_original()

    def test_foreign_lock_is_not_removed(self):
        self.write("data/local/voyahtune-install.lock/owner", "ota:other")
        self.run_action("prepare", success=False)
        self.assertEqual((self.root / "data/local/voyahtune-install.lock/owner").read_text(), "ota:other")
        self.assert_original()

    def test_prepared_transaction_can_rollback_without_apply(self):
        self.run_action("prepare")
        self.run_action("rollback")
        self.assert_original()

    def test_supported_startup_is_restored_exactly(self):
        original = f"#!/system/bin/sh\necho OEM\nsh {self.root}/sdcard/Download/cunba/patch/load.bin &\necho keep\n"
        startup = self.write("system/etc/init.logcat.sh", original)
        self.run_action("prepare")
        self.run_action("apply")
        self.assertNotIn("cunba/patch", startup.read_text())
        self.assertIn("echo OEM\n", startup.read_text())
        self.assertIn("echo keep\n", startup.read_text())
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
        script = (SOURCE / "cunba_restore.sh").read_text().replace("/system/bin/sh -n", "/bin/sh -n")
        for name in ["data", "system", "proc", "sdcard", "storage"]:
            script = script.replace(f"/{name}/", f"{self.root}/{name}/")
        script = script.replace(r"\/sdcard\/", str(self.root).replace("/", r"\/") + r"\/sdcard\/")
        result = subprocess.run(["/bin/sh"], input=f"action={action}\n{script}",
                                env=self.environment, text=True, capture_output=True, timeout=10)
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

    def test_restore_preserves_current_oem_startup(self):
        self.write("system/etc/init.logcat.sh", f"#!/system/bin/sh\necho old OEM\nsh {self.root}/sdcard/Download/cunba/patch/load.bin &\n")
        self.disable_for_restore()
        startup = self.write("system/etc/init.logcat.sh", "#!/system/bin/sh\necho new OEM\nexit 0\n")
        self.run_restore("restore")
        content = startup.read_text()
        self.assertIn("echo new OEM", content)
        self.assertNotIn("echo old OEM", content)
        self.assertLess(content.index("cunba/patch/load.bin"), content.index("exit 0"))
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

    def test_restore_refuses_unrecognized_current_startup(self):
        self.disable_for_restore()
        startup = self.write("system/etc/init.logcat.sh", "exec /sdcard/Download/cunba/patch/load.bin --custom\n")
        self.run_restore("restore", success=False)
        self.assertIn("--custom", startup.read_text())
        self.assertFalse((self.root / "data/local/bin/frida-inject2").exists())

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
