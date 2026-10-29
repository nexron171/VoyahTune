#!/usr/bin/env python3
"""Unified installation over old mode flags with data-preserving migration."""

import unittest

from integration import InstallerTests


class ModeTests(InstallerTests):
    def test_previous_mode_flags_allow_installation_without_plan_writes(self):
        self.seed_apps()
        for flag in [None, "light", "full", "invalid"]:
            with self.subTest(flag=flag):
                self.state["settings"] = {} if flag is None else {"voyahtune_install_mode": flag}
                self.write_state()
                self.plan("install")
        self.assertFalse(any(call["args"][0] in ["push", "install", "uninstall", "reboot", "remount"] for call in self.calls()))

    def test_unified_install_preserves_data_and_removes_legacy_manifests(self):
        self.seed_apps()
        self.state["settings"]["voyahtune_install_mode"] = "full"
        self.write_state()
        runtime = self.device / "data/local/bin/voyahtune-new"
        runtime.mkdir(parents=True, exist_ok=True)
        for name in ["agents.json", "runtime-manifest.json"]:
            (runtime / name).write_text("legacy runtime configuration")
        self.apply(self.plan("install"))
        self.assert_app_data(True)
        self.assertTrue((runtime / "voyahtune-loader").is_file())
        self.assertTrue((runtime / "voyahtune-inject-arm").is_file())
        self.assertFalse((runtime / "agents.json").exists())
        self.assertFalse((runtime / "runtime-manifest.json").exists())

    def test_failed_package_install_preserves_data_and_blocks_final_reboot(self):
        self.seed_apps()
        self.state["settings"]["voyahtune_install_mode"] = "light"
        self.state["installError"] = "INSTALL_FAILED_INSUFFICIENT_STORAGE"
        self.write_state()
        result = self.apply(self.plan("install"), okay=False)
        self.assertNotEqual(result.returncode, 0)
        self.assert_app_data(True)
        self.assertFalse(any(call["args"] == ["reboot"] for call in self.calls()))
        self.assertNotIn("operation-completed", result.stdout)


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(ModeTests(name) for name in ModeTests.__dict__ if name.startswith("test_"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
