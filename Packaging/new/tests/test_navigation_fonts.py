"""Exercise exact-byte patching and interrupted multi-theme transactions."""

import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "install" / "navigation_fonts.py"
SPEC = importlib.util.spec_from_file_location("navigation_fonts", SCRIPT)
fonts = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fonts)


def fixture(path):
    prefix = "kzb://" + path.stem + "/Resource Files/Fonts/"
    original = (prefix + "HuaKangJinGangHei-Regular-2.ttf").encode()
    replacement = (prefix + "Roboto-Regular.ttf").encode()
    data = bytearray(b"\0" * 2048)
    data[-40:] = b"TBT.distance:0123456789".ljust(40, b"\0")
    fields = []
    bindings = ["TBT.road", "TBT.unit", "TBT.unit", "TBT.road", "TBTRoam.road"]
    for index, binding in enumerate(bindings):
        offset = 64 + 320 * index
        struct.pack_into("<I", data, offset - 26, 100)
        data[offset - 22:offset - 10] = b"\x09\x00Node.Font\0"
        struct.pack_into("<II", data, offset - 10, 0, 16)
        data[offset - 2:offset + len(original)] = struct.pack("<H", len(original)) + original
        data[offset + 150:offset + 150 + len(binding)] = binding.encode()
        fields.append([binding, offset, offset + 150])
    expected = data[:]
    for _, offset, _ in fields:
        expected[offset - 2:offset + len(original)] = (
            struct.pack("<H", len(replacement)) + replacement.ljust(len(original), b"\0")
        )
    profile = {
        "path": str(path),
        "size": len(data),
        "fields": fields,
        "originalSha256": fonts.digest(data),
        "patchedSha256": fonts.digest(expected),
    }
    return profile, bytes(data), bytes(expected)


class NavigationFontTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.fixtures = [fixture(self.root / (name + ".kzb"))
                         for name in ["gauge_normal", "gauge_classic", "gauge_elegant"]]
        self.profiles = [item[0] for item in self.fixtures]
        for profile, original, _ in self.fixtures:
            path = Path(profile["path"])
            path.write_bytes(original)
            path.chmod(0o755)
        self.state = self.root / "state"
        self.state.mkdir()
        self.readonly = True
        self.mount_changes = []

        def remount(readonly):
            self.readonly = readonly
            self.mount_changes.append(readonly)

        for replacement in [patch.object(fonts, "PROFILES", self.profiles),
                            patch.object(fonts, "STATE_DIRECTORY", self.state),
                            patch.object(fonts, "read_only", lambda: self.readonly),
                            patch.object(fonts, "remount", remount)]:
            replacement.start()
            self.addCleanup(replacement.stop)

    def assert_original(self):
        for profile, original, _ in self.fixtures:
            path = Path(profile["path"])
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(path.stat().st_mode & 0o777, 0o755)
        self.assertTrue(self.readonly)

    def test_round_trip_preserves_digits_and_every_unselected_byte(self):
        for profile, original, expected in self.fixtures:
            result = fonts.transform(profile, original)
            self.assertEqual(result, expected)
            self.assertEqual(result[-40:], original[-40:])
            self.assertEqual(fonts.transform(profile, result), result)
            self.assertEqual(fonts.transform(profile, result, restore=True), original)
            for bad in [original[:-1], b"!" + original[1:], expected[:300] + original[300:]]:
                with self.assertRaises(ValueError):
                    fonts.transform(profile, bad)

    def test_unknown_third_theme_prevents_any_resource_write(self):
        path = Path(self.profiles[2]["path"])
        path.write_bytes(b"unknown firmware")
        with self.assertRaises(ValueError):
            fonts.update("apply")
        self.assertEqual(self.mount_changes, [])
        for profile, original, _ in self.fixtures[:2]:
            self.assertEqual(Path(profile["path"]).read_bytes(), original)

    def test_apply_repeat_and_restore_all_three(self):
        fonts.update("apply")
        for profile, _, expected in self.fixtures:
            self.assertEqual(Path(profile["path"]).read_bytes(), expected)
        changes = self.mount_changes[:]
        fonts.update("apply")
        self.assertEqual(self.mount_changes, changes)
        fonts.update("restore")
        self.assert_original()
        self.assertFalse((self.state / "transaction.json").exists())

    def test_failure_on_third_rename_rolls_back_first_two(self):
        original_replace = fonts.os.replace
        failed = False

        def replace(source, destination):
            nonlocal failed
            if str(destination) == self.profiles[2]["path"] and not failed:
                failed = True
                raise OSError("injected rename failure")
            return original_replace(source, destination)

        with patch.object(fonts.os, "replace", replace):
            with self.assertRaisesRegex(OSError, "injected"):
                fonts.update("apply")
        self.assert_original()
        self.assertFalse((self.state / "transaction.json").exists())

    def test_interrupted_transaction_is_recovered_before_next_operation(self):
        record = {"schema": 1, "readOnly": True, "before": {}}
        for profile, original, _ in self.fixtures:
            fonts.before_path(profile).write_bytes(original)
            record["before"][profile["path"]] = fonts.digest(original)
        profile, _, expected = self.fixtures[0]
        Path(profile["path"]).write_bytes(expected)
        (self.state / "transaction.json").write_text(json.dumps(record))
        self.readonly = False
        fonts.update("restore")
        self.assert_original()
        self.assertFalse((self.state / "transaction.json").exists())

    def test_corrupt_backup_does_not_overwrite_a_theme(self):
        profile = self.profiles[0]
        (self.state / (Path(profile["path"]).name + ".original")).write_bytes(b"bad backup")
        with self.assertRaisesRegex(ValueError, "Invalid original backup"):
            fonts.update("apply")
        self.assert_original()
        self.assertEqual(self.mount_changes, [])


if __name__ == "__main__":
    unittest.main()
