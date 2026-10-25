#!/usr/bin/env python3
"""Release selection and replacement with tiny local build fixtures (no SDK/ADB)."""

import importlib.util
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "release", REPO / "Installer/scripts/release.py"
)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "Installer").mkdir()
        (self.root / "Installer/rust-toolchain.toml").write_text(
            '[toolchain]\nchannel="1.98.1"\n'
        )
        (self.root / "Installer/Cargo.toml").write_text(
            '[workspace.package]\nversion="1.0.0"\n'
        )
        self.generation = 0
        self.selected = []
        self.fail_build = False

    def fake_run(self, command, **kwargs):
        args = list(map(str, command))
        if Path(args[0]).name == "installer-build":
            if self.fail_build:
                raise subprocess.CalledProcessError(1, args)
            payload = Path(args[args.index("--output") + 1])
            payload.mkdir()
            version = args[args.index("--version") + 1]
            (payload / "manifest.json").write_text(
                json.dumps(
                    {
                        "schema": 4,
                        "requirements": {
                            "minInstallerVersion": "1.0.0",
                            "requiredCapabilities": ["files-v1"],
                        },
                        "releaseVersion": version,
                        "buildRevision": "fixture",
                        "infrastructure": args[args.index("--infrastructure") + 1],
                        "generation": self.generation,
                    }
                )
            )
        elif Path(args[0]).name == "build-all-macos.sh":
            dest = Path(args[args.index("--output") + 1])
            dest.mkdir()
            self.assertNotIn("--pi", args)
            self.assertNotIn("--od", args)
            names = [
                name
                for flag, name in [
                    ("--mac", "macos"),
                    ("--windows", "windows"),
                    ("--linux", "linux"),
                ]
                if flag in args
            ]
            self.selected = names
            entries = {}
            architectures = (
                [args[args.index("--windows-arch") + 1]]
                if "--windows-arch" in args
                else ["x64", "x86", "arm64"]
            )
            artifacts = [
                artifact
                for name in names
                for artifact in (
                    [f"windows-{architecture}" for architecture in architectures]
                    if name == "windows"
                    else [name]
                )
            ]
            for name in artifacts:
                filename = {
                    "macos": "macos-universal.tar.gz",
                    "linux": "linux.run",
                }.get(name, f"{name}.exe")
                artifact = dest / filename
                if name == "macos":
                    app = dest / "VoyahTune Installer.app"
                    app.mkdir()
                    (app / "fixture").write_text(str(self.generation))
                    with tarfile.open(artifact, "w:gz") as archive:
                        archive.add(app, arcname=app.name)
                else:
                    artifact.write_text(str(self.generation))
                entries[name] = {"file": filename, "sha256": release.sha(artifact)}
            record = dict(
                installerVersion="1.0.0",
                embeddedPayload="--payload" in args,
                platforms=entries,
                generation=self.generation,
            )
            (dest / "build-info.json").write_text(json.dumps(record))

    def shell_arguments(self, *arguments):
        script = self.root / "make_release.sh"
        script.write_text((REPO / "make_release.sh").read_text())
        executables = self.root / "bin"
        executables.mkdir(exist_ok=True)
        python = executables / "python3"
        python.write_text(
            f"#!{sys.executable}\n"
            "import json\n"
            "import sys\n"
            "print(json.dumps(sys.argv[1:]))\n"
        )
        python.chmod(0o755)
        result = subprocess.run(
            ["sh", str(script), *arguments],
            env={
                **os.environ,
                "PATH": str(executables) + os.pathsep + os.environ["PATH"],
            },
            text=True,
            capture_output=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        command = json.loads(result.stdout)
        self.assertEqual(command[0], str(self.root / "Installer/scripts/release.py"))
        return command

    def run_release(self, *flags, through_shell=False):
        self.generation += 1
        arguments = [
            "release.py",
            "4.5.6",
            "--revision",
            "fixture",
            *(["--minimum-ota-version", "4.5.6"] if "--minimum-ota-version" not in flags else []),
            *flags,
        ]
        if through_shell:
            arguments = self.shell_arguments(*arguments[1:])
        with patch.object(release, "ROOT", self.root), patch.object(
            release, "run", self.fake_run
        ), patch.object(release.platform, "system", return_value="Darwin"), patch(
            "sys.argv", arguments
        ):
            release.main()

    def test_platform_selection_and_same_version_overwrite(self):
        for flags, expected in [
            ([], ["macos", "windows"]),
            (["--installers"], ["macos", "windows"]),
            (["--installers", "--linux"], ["macos", "windows", "linux"]),
            (["--mac"], ["macos"]),
            (["--windows"], ["windows"]),
            (["--linux"], ["linux"]),
            (["--mac", "--linux"], ["macos", "linux"]),
        ]:
            with self.subTest(flags=flags):
                self.run_release(*flags)
                self.assertEqual(self.selected, expected)
                dest = self.root / "Releases/dist/VoyahTune-Installer-1.0.0"
                self.assertEqual(
                    sorted(p.name for p in dest.glob("*.zip")),
                    sorted(
                        f"VoyahTune-Installer-1.0.0-{artifact}.zip"
                        for name in expected
                        for artifact in (
                            ["windows-x64", "windows-x86", "windows-arm64"]
                            if name == "windows"
                            else [name]
                        )
                    ),
                )
                self.assertEqual(
                    json.loads((dest / "release.json").read_text())["generation"],
                    self.generation,
                )

    def test_payload_only_skips_platform_packaging(self):
        self.run_release("--payload")
        self.assertEqual(self.selected, [])
        archive = self.root / "Releases/dist/payload_4.5.6.zip"
        entry = json.loads(archive.with_suffix(".json").read_text())
        self.assertEqual(entry["sha256"], release.sha(archive))
        self.assertEqual(
            set(entry),
            {
                "version",
                "url",
                "size",
                "sha256",
                "minimumInstallerVersion",
                "minimumOtaVersion",
            },
        )
        self.assertEqual(entry["minimumInstallerVersion"], "1.0.0")
        self.assertEqual(entry["minimumOtaVersion"], "4.5.6")
        self.assertEqual(entry["size"], archive.stat().st_size)
        with zipfile.ZipFile(archive) as payload_archive:
            self.assertIn("manifest.json", payload_archive.namelist())
        self.assertFalse((archive.parent / "VoyahTune-Installer-1.0.0").exists())

    def test_common_default_creates_payload_with_root_manifest(self):
        self.run_release(through_shell=True)
        archive = self.root / "Releases/dist/payload_4.5.6.zip"
        with zipfile.ZipFile(archive) as payload_archive:
            manifest = json.loads(payload_archive.read("manifest.json"))
        self.assertEqual(manifest["schema"], 4)
        self.assertEqual(manifest["releaseVersion"], "4.5.6")
        self.assertEqual(manifest["infrastructure"], "unified")
        self.assertEqual(self.selected, [])
        self.assertFalse((archive.parent / "VoyahTune-Installer-1.0.0").exists())

    def test_od_default_forwards_release_options(self):
        for arguments in [
            ["4.3.3", "--od"],
            ["--od", "4.3.3"],
            ["4.3.3-od", "--od", "--no-build", "--no-zip"],
            [
                "4.3.3-beta-od",
                "--od",
                "--minimum-ota-version",
                "4.2.1",
                "--catalog-url",
                "https://example.com/index.json",
                "--revision",
                "fixture",
            ],
        ]:
            with self.subTest(arguments=arguments):
                command = self.shell_arguments(*arguments)
                self.assertEqual(command[1:], [*arguments, "--payload"])

    def test_explicit_build_modes_keep_their_arguments(self):
        for profile in ("--od", "--pi"):
            for flag in ("--payload", "--installers", "--mac", "--windows", "--linux"):
                arguments = ["4.3.3", profile, flag]
                with self.subTest(arguments=arguments):
                    command = self.shell_arguments(*arguments)
                    self.assertEqual(command[1:], arguments)

    def test_classic_mode_requires_legacy_for_both_profiles(self):
        for arguments in [
            ["--legacy", "4.3.3", "--od"],
            ["--legacy", "4.3.3", "--pi"],
        ]:
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    ["sh", str(REPO / "make_release.sh"), *arguments, "--help"],
                    text=True,
                    capture_output=True,
                    timeout=10,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("classic ZIP", result.stdout)
                self.assertNotIn("release.py", result.stdout)

    def test_ota_minimum_can_require_usb_for_a_root_service_update(self):
        self.run_release("--payload", "--minimum-ota-version", "4.1.0")
        entry = json.loads(
            (self.root / "Releases/dist/payload_4.5.6.json").read_text()
        )
        self.assertEqual(entry["minimumOtaVersion"], "4.1.0")

    def test_pi_catalog_entry_keeps_its_previous_format(self):
        self.run_release("--payload", "--pi")
        entry = json.loads(
            (self.root / "Releases/dist/payload_4.5.6-pi.json").read_text()
        )
        self.assertEqual(set(entry), {"version", "url", "size", "sha256"})

    def test_failed_rebuild_preserves_previous_outputs(self):
        self.run_release("--mac")
        payload = self.root / "Releases/build/installer-payload-4.5.6/manifest.json"
        record = self.root / "Releases/dist/VoyahTune-Installer-1.0.0/release.json"
        previous = (payload.read_bytes(), record.read_bytes())
        self.fail_build = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_release("--windows")
        self.assertEqual((payload.read_bytes(), record.read_bytes()), previous)

    def test_publication_failure_rolls_back_both_outputs(self):
        sources = [self.root / "new-payload", self.root / "new-packages"]
        dests = [self.root / "payload", self.root / "packages"]
        for path in sources + dests:
            path.mkdir()
            (path / "value").write_text(path.name)
        original = Path.rename

        def rename(path, target):
            if path == sources[1]:
                raise OSError("publication failed")
            return original(path, target)

        with patch.object(Path, "rename", rename), self.assertRaises(OSError):
            release.publish_outputs(zip(sources, dests))
        for path in sources + dests:
            self.assertEqual((path / "value").read_text(), path.name)

    def test_mac_flag_dispatches_without_installers_flag(self):
        result = subprocess.run(
            ["sh", str(REPO / "make_release.sh"), "4.5.6", "--mac", "--help"],
            text=True,
            capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--windows", result.stdout)
        self.assertIn("release.py", result.stdout)

    def test_prebuild_checks_cover_unified_runtime_and_shared_contracts(self):
        with patch.object(self, "fake_run", wraps=self.fake_run) as fake:
            self.run_release("--mac")
            commands = [list(map(str, call.args[0])) for call in fake.call_args_list]
        checks = [c for c in commands if c[0] == "python3"]
        self.assertEqual(
            checks,
            [
                [
                    "python3",
                    str(self.root / "Packaging/tests/test_infrastructure_profiles.py"),
                    "--profile",
                    "od",
                ]
            ],
        )
        self.assertIn(
            "test_infrastructure_profiles.py", (REPO / "make_release.sh").read_text()
        )
        runtime_check = ["sh", str(self.root / "Packaging/new/check.sh")]
        self.assertIn(runtime_check, commands)
        build_index = next(
            index for index, command in enumerate(commands)
            if command[:2] == ["cargo", "build"]
        )
        self.assertLess(commands.index(runtime_check), build_index)

    def test_unified_runtime_check_failure_stops_release_before_build(self):
        commands = []
        build_fixture = self.fake_run

        def fail_runtime_check(command, **kwargs):
            arguments = list(map(str, command))
            commands.append(arguments)
            if arguments == ["sh", str(self.root / "Packaging/new/check.sh")]:
                raise subprocess.CalledProcessError(1, arguments)
            build_fixture(command, **kwargs)

        with patch.object(self, "fake_run", side_effect=fail_runtime_check):
            with self.assertRaises(subprocess.CalledProcessError):
                self.run_release("--payload")
        self.assertEqual(commands, [["sh", str(self.root / "Packaging/new/check.sh")]])
        self.assertFalse((self.root / "Releases/build/.release.lock").exists())

    def test_profile_outputs_coexist(self):
        self.run_release("--od", "--mac")
        archive = self.root / "Releases/dist/payload_4.5.6-od.zip"
        previous = archive.read_bytes()
        self.run_release("--pi", "--mac", "--offline-bundle")
        self.assertEqual(archive.read_bytes(), previous)
        record = json.loads(
            (
                self.root / "Releases/dist/VoyahTune-Installer-1.0.0/release.json"
            ).read_text()
        )
        self.assertTrue(record["embeddedPayload"])
        self.assertTrue((self.root / "Releases/dist/payload_4.5.6-pi.zip").exists())

    def test_windows_architectures_do_not_overwrite_each_other(self):
        self.run_release("--od", "--windows")
        destination = self.root / "Releases/dist/VoyahTune-Installer-1.0.0"
        originals = {path: path.read_bytes() for path in destination.glob("*.zip")}
        self.assertEqual(len(originals), 3)
        for architecture in ("x64", "x86", "arm64"):
            with self.subTest(architecture=architecture):
                self.run_release("--od", "--windows-arch", architecture)
                for path, content in originals.items():
                    self.assertEqual(path.read_bytes(), content)
                selected = destination.with_name(
                    destination.name + f"-windows-{architecture}"
                )
                self.assertEqual(
                    [path.name for path in selected.glob("*.zip")],
                    [f"VoyahTune-Installer-1.0.0-windows-{architecture}.zip"],
                )

    def test_release_version_carries_selected_infrastructure(self):
        self.assertEqual(release.normalize_version("v4.5.6", "unified"), "4.5.6")
        self.assertEqual(release.normalize_version("4.5.6-beta.1", "unified"), "4.5.6-beta.1")
        for version in ["4.5.6-od", "4.5.6-beta-pi"]:
            with self.assertRaises(ValueError):
                release.normalize_version(version, "unified")
        self.assertEqual(release.normalize_version("3.22.0", "od"), "3.22.0-od")
        self.assertEqual(release.normalize_version("v3.22.0-pi", "pi"), "3.22.0-pi")
        self.assertEqual(
            release.normalize_version("3.22.0-beta.1+test", "pi"),
            "3.22.0-beta.1-pi+test",
        )
        with self.assertRaises(ValueError):
            release.normalize_version("3.22.0-od", "pi")

    def test_common_requires_ota_minimum_and_rejects_conflicting_legacy_profiles(self):
        for flags in ([], ["--pi", "--od"]):
            with self.subTest(flags=flags), patch(
                "sys.argv", ["release.py", "4.5.6", "--payload", *flags]
            ):
                with self.assertRaises(SystemExit) as raised:
                    release.main()
                self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
