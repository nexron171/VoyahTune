"""Toolchain discovery without downloading models, installing Rust or building Android."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_voice", Path(__file__).parents[1] / "prepare_voice.py")
voice = importlib.util.module_from_spec(spec)
spec.loader.exec_module(voice)


class RustEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "repo/RestoreMode"
        self.root.mkdir(parents=True)
        self.home = self.base / "home"
        self.cache = self.root.parent / "Releases/cache"
        self.enterContext(patch.object(voice, "ROOT", self.root))
        self.enterContext(patch.object(Path, "home", return_value=self.home))
        self.enterContext(patch.dict(os.environ, {"PATH": str(self.base / "empty")}, clear=True))

    def cargo(self, directory):
        binary = directory / "bin/cargo"
        binary.parent.mkdir(parents=True)
        binary.write_text("#!/bin/sh\nexit 0\n")
        binary.chmod(0o755)
        return binary

    def test_project_cache_works_without_shell_rust_setup(self):
        binary = self.cargo(self.cache / "cargo")
        cargo, env = voice.rust_environment()
        self.assertEqual(binary, cargo)
        self.assertEqual(str(self.cache / "cargo"), env["CARGO_HOME"])
        self.assertEqual(str(self.cache / "rustup"), env["RUSTUP_HOME"])
        self.assertEqual(str(binary.parent), env["PATH"].split(os.pathsep)[0])
        self.assertNotIn("CARGO_HOME", os.environ)  # No changes to the parent's environment.

    def test_cache_on_path_still_gets_matching_homes(self):
        binary = self.cargo(self.cache / "cargo")
        os.environ["PATH"] = str(binary.parent)
        _, env = voice.rust_environment()
        self.assertEqual(str(self.cache / "cargo"), env["CARGO_HOME"])
        self.assertEqual(str(self.cache / "rustup"), env["RUSTUP_HOME"])

    def test_explicit_cargo_home_and_toolchain_take_precedence(self):
        binary = self.cargo(self.base / "custom-cargo")
        on_path = self.cargo(self.base / "path-cargo")
        self.cargo(self.cache / "cargo")
        os.environ.update(CARGO_HOME=str(binary.parent.parent), PATH=str(on_path.parent),
                          RUSTUP_HOME=str(self.base / "custom-rustup"), RUSTUP_TOOLCHAIN="custom")
        cargo, env = voice.rust_environment()
        self.assertEqual(binary, cargo)
        self.assertEqual(os.environ["RUSTUP_HOME"], env["RUSTUP_HOME"])
        self.assertEqual("custom", env["RUSTUP_TOOLCHAIN"])

    def test_path_installation_wins_over_home_and_cache(self):
        binary = self.cargo(self.base / "path-cargo")
        self.cargo(self.home / ".cargo")
        self.cargo(self.cache / "cargo")
        os.environ["PATH"] = str(binary.parent)
        cargo, env = voice.rust_environment()
        self.assertEqual(binary, cargo)
        self.assertNotIn("RUSTUP_HOME", env)

    def test_home_rustup_proxy_keeps_cargo_basename(self):
        binary = self.cargo(self.home / ".cargo")
        proxy = binary.with_name("rustup")
        binary.rename(proxy)
        binary.symlink_to("rustup")
        cargo, _ = voice.rust_environment()
        self.assertEqual(binary, cargo)
        self.assertNotEqual(proxy, cargo)

    def test_missing_rust_preserves_previous_jni_output(self):
        build = self.base / "build"
        library = build / "jniLibs/arm64-v8a/libvoyah_df.so"
        library.parent.mkdir(parents=True)
        library.write_bytes(b"last successful build")
        with patch.object(voice, "BUILD", build), patch.object(voice, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "Rust cargo was not found"):
                voice.native(self.base / "ndk", ["arm64-v8a"])
            run.assert_not_called()
        self.assertEqual(b"last successful build", library.read_bytes())


if __name__ == "__main__":
    unittest.main()
