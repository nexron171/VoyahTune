#!/usr/bin/env python3
"""Exercise USB removal with leftover Native data on isolated fake devices."""

import json
import unittest

from integration import InstallerTests, ROOT


class RemovalDataTests(InstallerTests):
    def setUp(self):
        super().setUp()
        # Removal must work with the built-in recipe, without loading full application APKs.
        payload = self.bundle / "payload"
        payload.unlink()
        payload.symlink_to(
            ROOT / "Installer/desktop/src-tauri/resources/bundle/recovery",
            target_is_directory=True,
        )

    def seed_removal(self):
        self.seed_apps(broken=True)
        # A system APK may remain registered after both PackageManager uninstall calls.
        self.state["failUninstall"] = "ru.big.town.anative"
        self.state["settings"] = {
            "adb_enabled": "1",
            "other_setting": "voyahtune_value",
            "voyahtune_dpi_com.example.player": "320",
            "voyahtune_dock1SplitPresetId": "saved-preset",
            "voyahtune_cluster_navigation_enabled": "1",
            "open_voyah_future_setting": "1",
        }
        self.write_state()
        for parent in ["data/user/0", "data/user_de/0", "data/data"]:
            native = self.device / parent / "ru.big.town.anative"
            native.mkdir(parents=True, exist_ok=True)
            (native / "settings-marker").write_text("old Native settings")
            other = self.device / parent / "com.example.other"
            other.mkdir(parents=True, exist_ok=True)
            (other / "settings-marker").write_text("keep")

    def assert_no_reboot(self, result):
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("operation-completed", result.stdout)
        self.assertFalse(any(call["args"] == ["reboot"] for call in self.calls()))

    def test_removal_clears_data_even_when_native_uninstall_fails(self):
        self.seed_removal()
        plan = self.plan("remove")
        result = self.apply(plan)
        self.assertEqual(
            self.read_state()["settings"],
            {"adb_enabled": "1", "other_setting": "voyahtune_value"},
        )
        for parent in ["data/user/0", "data/user_de/0", "data/data"]:
            self.assertFalse((self.device / parent / "ru.big.town.anative").exists())
            self.assertEqual(
                (self.device / parent / "com.example.other/settings-marker").read_text(),
                "keep",
            )
        events = [json.loads(line) for line in result.stdout.splitlines()]
        steps = [event["stepId"] for event in events if event.get("type") == "step-started"]
        self.assertEqual(steps, [step["id"] for step in plan["steps"]])
        self.assertLess(steps.index("packages"), steps.index("settings"))
        self.assertLess(steps.index("reboot"), steps.index("settings"))
        calls = self.calls()
        reboot = next(index for index, call in enumerate(calls) if call["args"] == ["reboot"])
        cleanup = next(index for index, call in enumerate(calls) if "owned_global_keys()" in (call.get("script") or ""))
        self.assertLess(reboot, cleanup)

    def test_native_data_cleanup_failure_stops_removal(self):
        self.seed_removal()
        self.state["failShell"] = "Данные Native не удалены:"
        self.write_state()
        result = self.apply(self.plan("remove"), okay=False)
        self.assert_no_reboot(result)
        self.assertIn("NATIVE_DATA_REMOVE_FAILED", result.stdout)
        self.assertTrue((self.device / "data/user/0/ru.big.town.anative/settings-marker").exists())

    def test_global_settings_cleanup_failure_stops_removal(self):
        self.seed_removal()
        self.state["failShell"] = "owned_global_keys()"
        self.write_state()
        result = self.apply(self.plan("remove"), okay=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("operation-completed", result.stdout)
        self.assertTrue(any(call["args"] == ["reboot"] for call in self.calls()))
        self.assertIn("REMOVAL_DATA_CLEANUP_FAILED", result.stdout)
        self.assertIn("voyahtune_dpi_com.example.player", self.read_state()["settings"])

    def test_force_stop_failure_keeps_native_data(self):
        self.seed_removal()
        self.state["failForceStop"] = "ru.big.town.anative"
        self.write_state()
        result = self.apply(self.plan("remove"), okay=False)
        self.assert_no_reboot(result)
        self.assertTrue((self.device / "data/user/0/ru.big.town.anative/settings-marker").exists())


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(
        RemovalDataTests(name)
        for name in RemovalDataTests.__dict__
        if name.startswith("test_")
    )


if __name__ == "__main__":
    unittest.main(verbosity=2)
