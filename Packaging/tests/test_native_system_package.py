import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / "Packaging/installer/common/prepare-native-system-package.sh"
VERIFY = ROOT / "Packaging/installer/common/verify-native-system-package.sh"


class NativeSystemPackageTests(unittest.TestCase):
    def run_script(self, script, mode, active, flags="SYSTEM", private="PRIVILEGED"):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "active").write_text(active)
            (root / "preferences").write_text("saved vehicle settings")
            fixture = r'''
backup_native_data() {
    [ "$TEST_MODE" != backup-failure ] || return 1
    cp "$TEST_ROOT/preferences" "$TEST_ROOT/backup"
    echo backup >> "$TEST_ROOT/actions"
}
pm() {
    case "$1" in
        path)
            path=$(cat "$TEST_ROOT/active")
            [ -z "$path" ] || printf 'package:%s\n' "$path" ;;
        uninstall)
            [ -f "$TEST_ROOT/backup" ] || return 91
            echo "$*" >> "$TEST_ROOT/actions"
            [ "$TEST_MODE" != uninstall-failure ] || return 1
            if [ "$TEST_MODE" != false-success ]; then
                printf '%s' "$VOYAHTUNE_SYSTEM_NATIVE_APK" > "$TEST_ROOT/active"
                rm "$TEST_ROOT/preferences"
            fi
            echo Success ;;
        *) return 92 ;;
    esac
}
dumpsys() {
    printf '    flags=[ %s ]\n    privateFlags=[ %s ]\n' "$TEST_FLAGS" "$TEST_PRIVATE"
    printf '    flags=[ SYSTEM ]\n    privateFlags=[ PRIVILEGED ]\n'
}
. "$1"
'''
            output = subprocess.run(
                ["sh", "-c", fixture, "native-system-test", str(script)],
                env=dict(os.environ, TEST_ROOT=str(root), TEST_MODE=mode,
                         TEST_FLAGS=flags, TEST_PRIVATE=private,
                         VOYAHTUNE_SYSTEM_NATIVE_APK="/system/priv-app/Native/Native.apk"),
                capture_output=True, text=True, timeout=5,
            )
            backup = root / "backup"
            actions = root / "actions"
            return output, backup.read_text() if backup.exists() else None, actions.read_text() if actions.exists() else ""

    def test_data_update_is_removed_only_after_a_completed_data_backup(self):
        result, backup, actions = self.run_script(PREPARE, "success", "/data/app/native/base.apk")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(backup, "saved vehicle settings")
        self.assertEqual(actions.splitlines(), ["backup", "uninstall -k ru.big.town.anative"])

    def test_backup_or_uninstall_failure_stops_migration(self):
        for mode in ("backup-failure", "uninstall-failure", "false-success"):
            result, backup, actions = self.run_script(PREPARE, mode, "/data/app/native/base.apk")
            self.assertNotEqual(result.returncode, 0, mode)
            if mode == "backup-failure":
                self.assertNotIn("uninstall", actions)
            else:
                self.assertEqual(backup, "saved vehicle settings")

    def test_system_and_absent_packages_do_not_require_uninstall(self):
        for active in ("", "/system/priv-app/Native/Native.apk"):
            result, backup, actions = self.run_script(PREPARE, "success", active)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIsNone(backup)
            self.assertEqual(actions, "")

    def test_verification_requires_system_origin_and_privileged_flags(self):
        for active, flags, private, expected in (
            ("/system/priv-app/Native/Native.apk", "SYSTEM", "PRIVILEGED", True),
            ("/data/app/native/base.apk", "SYSTEM UPDATED_SYSTEM_APP", "PRIVILEGED", False),
            ("/system/priv-app/Native/Native.apk", "SYSTEM UPDATED_SYSTEM_APP", "PRIVILEGED", False),
            ("/system/priv-app/Native/Native.apk", "SYSTEM", "OTHER_FLAG", False),
        ):
            result, _, actions = self.run_script(VERIFY, "success", active, flags, private)
            self.assertEqual(result.returncode == 0, expected, result.stderr)
            self.assertEqual(actions, "")


if __name__ == "__main__":
    unittest.main()
