#!/usr/bin/env python3
"""Unified runtime installation and removal through fake ADB and a signed payload."""

import json
import unittest

from integration import InstallerTests, PAYLOAD


class UnifiedInstallationTests(InstallerTests):
    def use(self, payload):
        target = self.bundle / "payload"
        target.unlink()
        target.symlink_to(payload, target_is_directory=True)

    def test_clean_install_and_remove(self):
        self.apply(self.plan())
        runtime = self.device / "data/local/bin/voyahtune-new"
        self.assertTrue((runtime / "voyahtune-loader").is_file())
        bootstrap = self.device / "system/etc/voyahtune-ota-bootstrap.json"
        self.assertEqual(json.loads(bootstrap.read_text())["infrastructure"], "unified")
        for name in ["load.bin", "loaderFrida", "injects.json"]:
            self.assertFalse((self.device / "data/local/bin" / name).exists(), name)
        self.apply(self.plan("remove"))
        self.assertFalse(runtime.exists())
        self.assertNotIn("big.town.runyn", self.read_state()["packages"])

    def test_reinstall_and_saved_remover_clean_runtime(self):
        self.apply(self.plan())
        self.apply(self.plan())
        backup = next((self.base / "logs").rglob("backup"))
        self.assertFalse((backup / "load.bin").exists())
        recovery = self.base / "recovery"
        self.cli("removal", str(PAYLOAD), str(recovery))
        self.use(recovery)
        self.apply(self.plan("remove"))
        self.assertFalse((self.device / "data/local/bin/voyahtune-new").exists())
        self.assertFalse((self.device / "data/local/bin/load.bin").exists())

    def test_foreign_loader_backup_is_restored(self):
        foreign = b"foreign shell supervisor"
        path = self.device / "data/local/bin/load.bin"
        path.write_bytes(foreign)
        self.apply(self.plan())
        self.assertFalse(path.exists())
        self.apply(self.plan("remove"))
        self.assertEqual(path.read_bytes(), foreign)

    def test_runyn_storage_error_preserves_existing_data(self):
        self.apply(self.plan())
        marker = self.device / "data/user/0/big.town.runyn/settings-marker"
        marker.write_text("keep settings")
        self.state = self.read_state()
        self.state["runynInstallError"] = "INSTALL_FAILED_INSUFFICIENT_STORAGE"
        self.write_state()
        result = self.apply(self.plan(), okay=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(), "keep settings")
        self.assertIn("big.town.runyn", self.read_state()["packages"])
        self.assertFalse(
            any(
                "pm uninstall big.town.runyn" in (call["script"] or "")
                for call in self.calls()
            )
        )


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(
        UnifiedInstallationTests(name)
        for name in UnifiedInstallationTests.__dict__
        if name.startswith("test_")
    )


if __name__ == "__main__":
    unittest.main(verbosity=2)
