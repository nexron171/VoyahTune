#!/usr/bin/env python3
"""A single library driver installs signed fixtures A/B, offline imports and removes B."""

import hashlib, json, os, shutil, unittest, zipfile
from pathlib import Path
from integration import InstallerTests, ROOT, DRIVER

FIXTURES = ROOT / "Releases/build/installer-remake-acceptance" / "unified"


class EvolutionTests(InstallerTests):
    def use(self, payload):
        p = self.bundle / "payload"
        p.unlink()
        p.symlink_to(payload, target_is_directory=True)

    def archive(self, payload, name):
        out = self.base / (name + ".zip")
        with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
            for p in payload.rglob("*"):
                if p.is_file():
                    z.write(p, p.relative_to(payload))
        return out

    def test_one_binary_installs_a_b_and_removes_with_compiled_recipe(self):
        binary = hashlib.sha256(DRIVER.read_bytes()).hexdigest()
        cache = self.base / "cache"
        legacy = self.device / "data/local/bin/voyahtune-new"
        legacy.mkdir(parents=True, exist_ok=True)
        for name in ["agents.json", "runtime-manifest.json"]:
            (legacy / name).write_text("old runtime manifest")
        for label in ["A", "B"]:
            archive = self.archive(FIXTURES / label, label)
            response = json.loads(self.cli("import", str(archive), str(cache)).stdout)
            self.use(Path(response["payloadRoot"]))
            self.apply(self.plan("install"))
            runtime = self.device / "data/local/bin/voyahtune-new"
            self.assertEqual(json.loads((runtime / "voyahtune_keyboard_en_config.json").read_text())["acceptanceFixture"], label)
            self.assertTrue((runtime / "voyahtune-inject-arm").is_file())
            self.assertEqual((runtime / "voyahtune-loader").stat().st_mode & 0o777, 0o755)
            self.assertFalse((runtime / "agents.json").exists())
            self.assertFalse((runtime / "runtime-manifest.json").exists())
        saved = self.base / "recovery"
        self.cli("removal", response["payloadRoot"], str(saved))
        shutil.rmtree(cache)
        self.use(saved)
        self.apply(self.plan("remove"))
        self.assertFalse(runtime.exists())
        self.assertEqual(binary, hashlib.sha256(DRIVER.read_bytes()).hexdigest())

    def change_metadata(self, payload, **changes):
        apk = payload / "common/restore_mode.apk"
        with zipfile.ZipFile(apk) as archive:
            entries = [(item, archive.read(item)) for item in archive.infolist()]
        with zipfile.ZipFile(apk, "w") as archive:
            for item, data in entries:
                if item.filename == "assets/voyahtune-build.json":
                    metadata = json.loads(data)
                    metadata.update(changes)
                    data = json.dumps(metadata).encode()
                archive.writestr(item, data)

    def test_future_format_and_installer_minimum_reject_before_adb(self):
        for changes in [{"payloadFormat": 99}, {"minimumInstallerVersion": "99.0.0"}]:
            with self.subTest(changes=changes):
                p = self.base / ("future-" + next(iter(changes)))
                shutil.copytree(FIXTURES / "B", p)
                self.change_metadata(p, **changes)
                self.use(p)
                result = self.cli("plan", "--device", "CAR-001", "--action", "install", okay=False)
                self.assertIn("INSTALLER_UPDATE_REQUIRED", result.stdout)
                self.assertFalse((self.base / "calls.jsonl").exists())

    def test_corrupt_zip_never_enters_cache(self):
        archive = self.base / "bad.zip"
        archive.write_bytes(b"broken zip")
        cache = self.base / "cache"
        self.assertNotEqual(
            self.cli("import", str(archive), str(cache), okay=False).returncode, 0
        )
        self.assertEqual(json.loads(self.cli("cache-list", str(cache)).stdout), [])

    def test_component_tampering_is_rejected_by_signed_apk_hashes(self):
        p = self.base / "tampered"
        shutil.copytree(FIXTURES / "B", p)
        (p / "common/voyahtune-new-agents.dex").write_bytes(b"changed agent code")
        self.use(p)
        result = self.cli("plan", "--device", "CAR-001", "--action", "install", okay=False)
        self.assertIn("APK_RUNTIME_HASH", result.stdout)
        self.assertFalse((self.base / "calls.jsonl").exists())


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(
        EvolutionTests(n) for n in EvolutionTests.__dict__ if n.startswith("test_")
    )


if __name__ == "__main__":
    unittest.main(verbosity=2)
