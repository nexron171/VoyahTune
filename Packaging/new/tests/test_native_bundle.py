import hashlib
import importlib.util
import json
import stat
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
specification = importlib.util.spec_from_file_location("native_bundle", ROOT / "scripts/bundle.py")
bundle = importlib.util.module_from_spec(specification)
specification.loader.exec_module(bundle)


class NativeBundleTest(unittest.TestCase):
    def components(self, directory):
        directory.mkdir()
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[18:20] = (183).to_bytes(2, "little")
        for name in ("libvoyahtune-agent.so", "voyahtune-inject", "voyahtune-loader"):
            (directory / name).write_bytes(header + name.encode())
        header[:6] = b"\x7fELF\x01\x01"
        header[18:20] = (40).to_bytes(2, "little")
        for name in ("libvoyahtune-agent-arm.so", "voyahtune-inject-arm"):
            (directory / name).write_bytes(header + name.encode())
        (directory / "native-id.txt").write_text("native-" + "a" * 64 + "\n")
        (directory / "agents.dex").write_bytes(b"dex\n039\0test-component")
        return directory

    def test_emits_native_components_hashes_and_modes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            components = self.components(root / "components")
            output = root / "runtime"
            bundle.bundle(output, components)
            metadata = json.loads((output / "runtime-manifest.json").read_text())
            self.assertEqual(metadata["schema"], 2)
            self.assertEqual(metadata["nativeId"], "native-" + "a" * 64)
            self.assertTrue(metadata["runtimeId"].startswith("java-"))
            self.assertEqual(json.loads((output / "agents.json").read_text())["schema"], 2)
            self.assertIn("agents.dex", metadata["sha256"])
            self.assertIn("libvoyahtune-agent-arm.so", metadata["sha256"])
            self.assertIn("voyahtune-inject-arm", metadata["sha256"])
            for name, digest in metadata["sha256"].items():
                file = output / name
                self.assertEqual(hashlib.sha256(file.read_bytes()).hexdigest(), digest)
                expected = 0o755 if name in ("voyahtune-loader", "voyahtune-inject", "voyahtune-inject-arm", "stop-loader.sh") else 0o644
                self.assertEqual(stat.S_IMODE(file.stat().st_mode), expected)
            self.assertFalse(any("frida" in file.name.lower() or file.suffix == ".js" for file in output.iterdir()))
            first = metadata["runtimeId"]
            (components / "voyahtune-loader").write_bytes((components / "voyahtune-loader").read_bytes() + b"supervisor change")
            bundle.bundle(output, components)
            self.assertEqual(json.loads((output / "runtime-manifest.json").read_text())["runtimeId"], first)
            (components / "agents.dex").write_bytes(b"dex\n039\0next-component")
            bundle.bundle(output, components)
            self.assertNotEqual(json.loads((output / "runtime-manifest.json").read_text())["runtimeId"], first)

    def test_missing_or_wrong_abi_component_preserves_previous_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            components = self.components(root / "components")
            output = root / "runtime"
            bundle.bundle(output, components)
            previous = (output / "runtime-manifest.json").read_bytes()
            (components / "voyahtune-inject-arm").write_bytes((components / "voyahtune-inject").read_bytes())
            with self.assertRaises(ValueError):
                bundle.bundle(output, components)
            self.assertEqual((output / "runtime-manifest.json").read_bytes(), previous)
            (components / "voyahtune-inject").unlink()
            with self.assertRaises(ValueError):
                bundle.bundle(output, components)
            self.assertEqual((output / "runtime-manifest.json").read_bytes(), previous)
            (components / "voyahtune-inject").write_bytes(b"\x7fELF\x01\x01" + b"0" * 58)
            with self.assertRaises(ValueError):
                bundle.bundle(output, components)
            self.assertEqual((output / "runtime-manifest.json").read_bytes(), previous)


if __name__ == "__main__":
    unittest.main()
