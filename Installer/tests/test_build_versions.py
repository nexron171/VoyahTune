#!/usr/bin/env python3
"""Version propagation and synchronization without running a build."""

import json
import sys
import tempfile
import tomllib
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from build_versions import build_environment, sync_installer_version


class BuildVersionsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.write_versions("2.3.4")

    def write_versions(self, installer):
        (self.root / "versions.json").write_text(
            json.dumps({"installer": installer, "voyahtune": "5.6.7"})
        )

    def test_environment_uses_json_and_explicit_compatibility_minimum(self):
        defaults = build_environment(self.root)
        self.assertEqual(defaults["VOYAH_INSTALLER_VERSION"], "2.3.4")
        self.assertEqual(defaults["VOYAH_RELEASE_VERSION"], "5.6.7")
        self.assertEqual(defaults["VOYAH_MINIMUM_INSTALLER_VERSION"], "2.3.4")
        selected = build_environment(self.root, "5.6.7-beta", "1.9.0")
        self.assertEqual(selected["VOYAH_RELEASE_VERSION"], "5.6.7-beta")
        self.assertEqual(selected["VOYAH_MINIMUM_INSTALLER_VERSION"], "1.9.0")
        self.assertEqual(
            json.loads((self.root / "versions.json").read_text())["voyahtune"],
            "5.6.7",
        )

    def test_invalid_source_version_is_rejected(self):
        for value in ["", "2.3.4-beta", "02.3.4", 234]:
            with self.subTest(value=value):
                self.write_versions(value)
                with self.assertRaises(ValueError):
                    build_environment(self.root)

    def test_json_change_updates_all_local_packages_and_removes_tauri_duplicate(self):
        local_packages = {
            "Installer/Cargo.lock": [
                "installer-build",
                "installer-core",
                "qnx-fonts",
                "release-core",
                "voyahtune-desktop",
            ],
            "Updater/daemon/Cargo.lock": ["qnx-fonts", "release-core"],
            "Packaging/new/tests/install-contract-driver/Cargo.lock": ["release-core"],
        }
        manifest = self.root / "Installer/Cargo.toml"
        tauri = self.root / "Installer/desktop/src-tauri/tauri.conf.json"
        tauri.parent.mkdir(parents=True)
        manifest.write_text(
            '[workspace.package]\nversion = "0.1.0"\nedition = "2021"\n'
            '\n[workspace.dependencies]\nother = "0.1.0"\n'
        )
        for relative, names in local_packages.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                "version = 4\n"
                + "".join(
                    f'\n[[package]]\nname = "{name}"\nversion = "0.1.0"\n'
                    for name in [*names, "unrelated-local-package"]
                )
                + '\n[[package]]\nname = "release-core"\nversion = "0.1.0"\n'
                'source = "registry+https://example.test"\n'
            )
        for initial in [{"version": "0.1.0"}, {}]:
            with self.subTest(tauri_config=initial):
                tauri.write_text(json.dumps({"productName": "VoyahTune", **initial}))
                for version in ["2.3.4", "2.4.0"]:
                    self.write_versions(version)
                    sync_installer_version(self.root)
                    cargo = tomllib.loads(manifest.read_text())
                    self.assertEqual(cargo["workspace"]["package"]["version"], version)
                    self.assertEqual(cargo["workspace"]["dependencies"]["other"], "0.1.0")
                    self.assertEqual(
                        json.loads(tauri.read_text()), {"productName": "VoyahTune"}
                    )
                    for relative, names in local_packages.items():
                        packages = tomllib.loads((self.root / relative).read_text())[
                            "package"
                        ]
                        for package in packages:
                            expected = (
                                version
                                if package["name"] in names and "source" not in package
                                else "0.1.0"
                            )
                            self.assertEqual(
                                package["version"], expected, (relative, package)
                            )
                paths = [
                    manifest, tauri,
                    *(self.root / relative for relative in local_packages),
                ]
                first = [path.read_bytes() for path in paths]
                sync_installer_version(self.root)
                self.assertEqual([path.read_bytes() for path in paths], first)


if __name__ == "__main__":
    unittest.main()
