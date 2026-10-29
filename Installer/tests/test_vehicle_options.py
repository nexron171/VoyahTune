#!/usr/bin/env python3
"""Optional OEM changes through the USB engine and a filesystem-backed fake car."""

import json
import shutil
import unittest

import integration


class VehicleOptionsTests(unittest.TestCase):
    def fixture(self):
        fixture = integration.InstallerTests()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        account = fixture.device / "private/configs/token/accountInfo"
        account.parent.mkdir(parents=True)
        account.write_text("Original owner")
        navigation = fixture.device / "system/priv-app/dueros/dueros.apk"
        navigation.parent.mkdir()
        navigation.write_text("OEM navigation")
        return fixture

    def selected_plan(self, fixture):
        return json.loads(
            fixture.cli(
                "plan", "--device", "CAR-001", "--action", "install",
                "--fake-account", "--disable-chinese-navigation",
            ).stdout
        )

    def apply_plan(self, fixture, plan, okay=True):
        path = fixture.base / "selected-plan.json"
        path.write_text(json.dumps(plan))
        return fixture.cli(
            "apply-plan", str(path), "--yes", "--logs", str(fixture.base / "logs"),
            okay=okay,
        )

    def test_selected_options_survive_reinstall_and_removal_of_installer_data(self):
        fixture = self.fixture()
        default = fixture.plan()
        self.assertTrue(default["oemOptionsAvailable"])
        self.assertEqual(
            default["request"]["options"],
            {"fakeAccount": False, "disableChineseNavigation": False},
        )
        self.assertNotIn("fake-account", [step["id"] for step in default["steps"]])

        selected = self.selected_plan(fixture)
        self.assertEqual(
            [step["title"] for step in selected["steps"] if step["id"] == "fake-account"],
            ["Активация Fake аккаунта"],
        )
        result = self.apply_plan(fixture, selected)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertTrue(any(
            event["type"] == "step-completed" and event.get("stepId") == "fake-account"
            for event in events
        ))
        self.assertTrue((fixture.device / "fake_login").is_file())
        self.assertTrue((fixture.device / "tmp/fake_login").is_file())
        self.assertEqual(
            (fixture.device / "private/configs/token/accountInfo").read_text(),
            "Owner%+79991234567%1%100001%100001\n",
        )
        self.assertFalse((fixture.device / "system/priv-app/dueros").exists())

        shutil.rmtree(fixture.base / "logs")
        self.apply_plan(fixture, self.selected_plan(fixture))
        fixture.apply(fixture.plan("remove"))
        self.assertEqual(
            (fixture.device / "data/local/voyahtune-backups/fake-account/accountInfo").read_text(),
            "Original owner",
        )
        self.assertEqual(
            (fixture.device / "sdcard/dueros/dueros.apk").read_text(), "OEM navigation",
        )

    def test_h97xy_hide_options_and_reject_forged_requests(self):
        for identity in ["PRJ_H97x_Platform_8155", "H97YSA8155"]:
            with self.subTest(identity=identity):
                fixture = self.fixture()
                fixture.state.update({"model": "H97C", "description": identity})
                fixture.write_state()
                plan = fixture.plan()
                self.assertFalse(plan["oemOptionsAvailable"])
                plan["request"]["options"] = {"fakeAccount": True, "disableChineseNavigation": True}
                result = self.apply_plan(fixture, plan, okay=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("VEHICLE_OPTIONS_UNAVAILABLE", result.stdout)
                self.assertTrue((fixture.device / "system/priv-app/dueros/dueros.apk").is_file())
                self.assertEqual(
                    (fixture.device / "private/configs/token/accountInfo").read_text(),
                    "Original owner",
                )
                self.assertFalse((fixture.device / "fake_login").exists())

    def test_navigation_backup_errors_stop_the_preparation_step(self):
        fixture = self.fixture()
        plan = self.selected_plan(fixture)
        fixture.state["failShell"] = 'cp -R "$source" "$staging"'
        fixture.write_state()
        result = self.apply_plan(fixture, plan, okay=False)
        self.assertNotEqual(result.returncode, 0)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertTrue(any(
            event["type"] == "step-failed" and event.get("stepId") == "system"
            for event in events
        ))
        self.assertTrue((fixture.device / "system/priv-app/dueros/dueros.apk").is_file())
        self.assertFalse((fixture.device / "fake_login").exists())
        self.assertFalse(any(call["args"] == ["reboot"] for call in fixture.calls()))


if __name__ == "__main__":
    unittest.main()
