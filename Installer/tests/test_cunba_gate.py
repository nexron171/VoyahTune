#!/usr/bin/env python3
"""Exercise OD plan/apply blocking using a signed payload and fake ADB only."""

import json
import unittest

from integration import InstallerTests


class CunbaGateTests(InstallerTests):
    def setUp(self):
        super().setUp()
        process_list = self.base / "bin/ps"
        process_list.unlink()
        process_list.write_text("#!/bin/sh\nprintf 'PID ARGS\\n1 init\\n'\n")
        process_list.chmod(0o755)

    def seed_cunba(self):
        self.marker = self.device / "data/local/bin/frida-inject2"
        self.marker.write_text("foreign injector")

    def assert_no_installation_writes(self):
        self.assertEqual(self.marker.read_text(), "foreign injector")
        self.assertFalse((self.device / "system/priv-app/Native/Native.apk").exists())
        self.assertFalse((self.device / "data/local/voyahtune-install.lock").exists())
        self.assertFalse(any(call["args"][0] == "push" for call in self.calls()))
        self.assertFalse(any("setprop ctl." in (call["script"] or "") for call in self.calls()))

    def test_file_alone_blocks_plan_but_allows_removal_plan(self):
        self.seed_cunba()
        result = self.cli("plan", "--device", "CAR-001", "--action", "install", okay=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CUNBA_DETECTED", result.stdout + result.stderr)
        self.assert_no_installation_writes()
        self.assertEqual(self.plan("remove")["request"]["action"], "remove")

    def test_stale_clean_plan_cannot_bypass_apply_guard(self):
        plan = self.plan()
        self.seed_cunba()
        result = self.apply(plan, okay=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CUNBA_DETECTED", result.stdout + result.stderr)
        self.assert_no_installation_writes()
        report = json.loads(next((self.base / "logs").rglob("report.json")).read_text())
        self.assertFalse(report["success"])
        self.assertEqual(report["error"]["code"], "CUNBA_DETECTED")
        self.assertNotIn("warning", report)


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(
        CunbaGateTests(name)
        for name in CunbaGateTests.__dict__
        if name.startswith("test_")
    )


if __name__ == "__main__":
    unittest.main(verbosity=2)
