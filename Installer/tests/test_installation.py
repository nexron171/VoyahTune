#!/usr/bin/env python3
"""Installation failures, migration and rollback through the real Rust engine."""

import json
import unittest

from integration import InstallerTests


class InstallationTests(unittest.TestCase):
    def fixture(self, state=None):
        f = InstallerTests()
        f.setUp()
        self.addCleanup(f.tearDown)
        if state:
            f.state.update(state)
            f.write_state()
        return f

    def apply(self, action, state=None, seed=None, success=True):
        fixture = self.fixture(state)
        if seed:
            seed(fixture)
        result = fixture.apply(fixture.plan(action), okay=False)
        self.assertEqual(result.returncode == 0, success, result.stdout[-3500:])
        return fixture

    def test_install_repairs_restrictive_directory_permissions(self):
        def seed(f):
            for path in ["data/local", "data/local/bin", "data/local/tmp"]:
                (f.device / path).chmod(0o2700)
            config = f.device / "data/local/bin/unrelated-private-file"
            config.write_text("keep")
            config.chmod(0o600)

        fixture = self.apply("install", seed=seed)
        for f in [fixture]:
            for path, mode, owner in [
                ("data/local", 0o751, "0:0"),
                ("data/local/bin", 0o755, "0:0"),
                ("data/local/tmp", 0o771, "2000:2000"),
            ]:
                self.assertEqual((f.device / path).stat().st_mode & 0o7777, mode)
                self.assertEqual(f.read_state()["owners"][path], owner)
            self.assertEqual(
                (f.device / "data/local/bin/unrelated-private-file").stat().st_mode
                & 0o7777,
                0o600,
            )

    def test_repeated_install_keeps_directory_permissions(self):
        fixture = self.apply("install")
        fixture.apply(fixture.plan("install"))
        self.assertEqual(
            (fixture.device / "data/local/bin").stat().st_mode & 0o7777, 0o755
        )

    def test_install_directory_preparation_failure_stops_before_push(self):
        fixture = self.apply(
            "install", {"failShell": "chmod 00755 /data/local/bin"}, success=False
        )
        self.assertFalse((fixture.device / "data/local/bin/voyahtune-new").exists())
        self.assertFalse(any(call["args"] == ["reboot"] for call in fixture.calls()))

    def seed_client_migration(self, f):
        for path in [
            "data/local/bin/fullscreen_client.js",
            "data/local/bin/fullscreen_client.js.voyahtune.new",
            "data/local/tmp/voyahtune_fullscreen_client.old",
            "data/local/tmp/voyahtune_app_client.old",
        ]:
            (f.device / path).write_text("legacy client")
        f.state["settings"][
            "voyahtune_fullscreen_apps"
        ] = "ru.yandex.yandexnavi,com.example.player"
        f.write_state()

    def test_old_clients_are_replaced_by_unified_runtime(self):
        fixture = self.apply("install", seed=self.seed_client_migration)
        self.assertTrue(
            (fixture.device / "data/local/bin/voyahtune-new/app-client.js").is_file()
        )
        for filename in ["app_client.js", "fullscreen_client.js"]:
            self.assertFalse((fixture.device / "data/local/bin" / filename).exists())

    def test_remove_cleans_both_client_generations(self):
        def seed(f):
            self.seed_client_migration(f)
            for path in [
                "data/local/bin/app_client.js",
                "data/local/bin/app_client.js.voyahtune.new",
            ]:
                (f.device / path).write_text("new client")

        for action in ["remove"]:
            with self.subTest(action=action):
                port = self.apply(action, seed=seed)
                self.assertFalse(
                    (port.device / "data/local/bin/app_client.js").exists()
                )
                self.assertFalse(
                    (port.device / "data/local/bin/fullscreen_client.js").exists()
                )

    def test_remove_and_cleans_settings_after_reboot(self):
        port = self.apply(
            "remove", seed=lambda fixture: fixture.seed_apps(old_key=True, broken=True)
        )
        calls = port.calls()
        last_reboot = max(
            index for index, call in enumerate(calls) if call["args"] == ["reboot"]
        )
        self.assertTrue(
            any(
                "owned_global_keys()" in (call.get("script") or "")
                for call in calls[last_reboot + 1 :]
            )
        )

    def test_install_backup_failure_stops_before_runtime(self):
        def seed(f):
            f.seed_apps()
            (f.device / "data/local/bin/load.bin").write_text("foreign loader")

        self.apply("install", {"failPull": True}, seed, success=False)

    def test_install_ignores_freeform_setting_failure(self):
        self.apply(
            "install", {"failShell": "settings put global enable_freeform_support"}
        )

    def test_atomic_publish_failure_stops_and_restores_loader(self):
        self.apply("install", {"failShell": "&& mv -f"}, success=False)

    def test_obsolete_engine_records_do_not_block(self):
        f = self.fixture()
        base = f.device / "data/local/voyahtune-installer"
        (base / "lock").mkdir(parents=True)
        (base / "lock/owner").write_text("dead-operation")
        (base / "load.bin.installed").write_text("invalid-old-hash")
        p = f.plan("install")
        (f.device / "data/local/bin/keyboard_ru.js").write_text("changed after plan")
        self.assertEqual(f.apply(p).returncode, 0)
        self.assertFalse((base / "lock").exists())

    def test_gui_rejects_corrupted_active_apk_after_push(self):
        port = self.fixture({"corruptPush": True})
        result = port.apply(port.plan("install"), okay=False)
        self.assertNotEqual(result.returncode, 0)

    def test_readonly_remove_has_no_extra_reboot_attempt(self):
        self.apply("remove", {"readOnly": True}, success=False)

    def seed_legacy(self, f):
        p = f.device / "system/etc/init.logcat.sh"
        p.write_text(
            "#!/system/bin/sh\n# init.logcat.sh Open Voyah:\n/system/bin/logcat -v threadtime\n"
        )

    def test_legacy_migration(self):
        self.apply("install", seed=self.seed_legacy)

    def test_legacy_hook_repair_preserves_app_data(self):
        port = self.fixture()
        self.seed_legacy(port)
        port.seed_apps()
        port.apply(port.plan("install"))
        port.assert_app_data(True)
        self.assertNotIn(
            "Open Voyah:", (port.device / "system/etc/init.logcat.sh").read_text()
        )

    def test_legacy_rollback_on_boot_publish_failure(self):
        fixture = self.apply(
            "install",
            {"failShell": "mv -f /system/etc/.voyahtune.load.sh.new"},
            self.seed_legacy,
            success=False,
        )
        self.assertIn(
            "Open Voyah:", (fixture.device / "system/etc/init.logcat.sh").read_text()
        )

    def test_changed_signatures_reset_both_apps(self):
        f = self.fixture()
        f.seed_apps(old_key=True)
        result = f.apply(f.plan("install"))
        f.assert_app_data(False)
        self.assertEqual(f.read_state()["reboots"], 2)
        self.assertIn("package-reset", result.stdout)

    def test_matching_signatures_keep_data(self):
        f = self.fixture()
        f.seed_apps()
        f.apply(f.plan("install"))
        f.assert_app_data(True)

    def test_android_signature_rejection_retries_once(self):
        f = self.fixture()
        f.seed_apps()
        f.state["rejectRestoreUpdate"] = True
        f.write_state()
        f.apply(f.plan("install"))
        self.assertEqual(
            sum(
                c["args"][0] == "install" and "restore" in c["args"][-1]
                for c in f.calls()
            ),
            2,
        )
        self.assertFalse(
            (f.device / "data/user/0/ru.big.town.restoremode/settings-marker").exists()
        )

    def test_storage_error_does_not_reset_data(self):
        f = self.fixture()
        f.seed_apps()
        f.state["installError"] = "INSTALL_FAILED_INSUFFICIENT_STORAGE"
        f.write_state()
        result = f.apply(f.plan("install"), okay=False)
        self.assertNotEqual(result.returncode, 0)
        f.assert_app_data(True)

    def test_steps_match_plan(self):
        f = self.fixture()
        plan = f.plan("install")
        events = [json.loads(s) for s in f.apply(plan).stdout.splitlines()]
        self.assertEqual(
            [s["id"] for s in plan["steps"]],
            [e["stepId"] for e in events if e.get("type") == "step-started"],
        )
        self.assertEqual(
            [s["id"] for s in plan["steps"]],
            [e["stepId"] for e in events if e.get("type") == "step-completed"],
        )


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(
        InstallationTests(name)
        for name in InstallationTests.__dict__
        if name.startswith("test_")
    )


if __name__ == "__main__":
    unittest.main()
