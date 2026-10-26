"""Exercise exact-byte patching and interrupted multi-theme transactions."""

import importlib.util
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
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
        data[offset - 22 : offset - 10] = b"\x09\x00Node.Font\0"
        struct.pack_into("<II", data, offset - 10, 0, 16)
        data[offset - 2 : offset + len(original)] = (
            struct.pack("<H", len(original)) + original
        )
        data[offset + 150 : offset + 150 + len(binding)] = binding.encode()
        fields.append([binding, offset, offset + 150])
    expected = data[:]
    for _, offset, _ in fields:
        expected[offset - 2 : offset + len(original)] = struct.pack(
            "<H", len(replacement)
        ) + replacement.ljust(len(original), b"\0")
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
        self.fixtures = [
            fixture(self.root / (name + ".kzb"))
            for name in ["gauge_normal", "gauge_classic", "gauge_elegant"]
        ]
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

        for replacement in [
            patch.object(fonts, "PROFILES", self.profiles),
            patch.object(fonts, "STATE_DIRECTORY", self.state),
            patch.object(fonts, "read_only", lambda: self.readonly),
            patch.object(fonts, "remount", remount),
        ]:
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
            for bad in [
                original[:-1],
                b"!" + original[1:],
                expected[:300] + original[300:],
            ]:
                with self.assertRaises(ValueError):
                    fonts.transform(profile, bad)

    def test_unsupported_theme_skips_the_whole_operation_without_writes(self):
        for index, (profile, original, patched) in enumerate(self.fixtures):
            incompatible_files = [
                original[:-1],
                original + b"new firmware data",
                b"!" + original[1:],
                patched[:300] + original[300:],
            ]
            for data in incompatible_files:
                for action in ["inspect", "apply", "restore"]:
                    with self.subTest(theme=index, size=len(data), action=action):
                        for current, initial, _ in self.fixtures:
                            Path(current["path"]).write_bytes(initial)
                        Path(profile["path"]).write_bytes(data)
                        before = [
                            Path(item["path"]).read_bytes() for item in self.profiles
                        ]

                        result = (
                            fonts.inspect()
                            if action == "inspect"
                            else fonts.update(action)
                        )

                        self.assertTrue(result["skipped"])
                        self.assertEqual(result["reason"], "unsupported_theme")
                        self.assertFalse(result["themes"][index]["supported"])
                        self.assertEqual(len(result["themes"]), 3)
                        self.assertEqual(
                            [Path(item["path"]).read_bytes() for item in self.profiles],
                            before,
                        )
                        self.assertEqual(self.mount_changes, [])
                        self.assertEqual(list(self.state.iterdir()), [])

    def test_main_reports_unsupported_themes_as_successfully_skipped(self):
        Path(self.profiles[1]["path"]).write_bytes(b"unknown firmware")
        for model in ["H97X", "H97Y"]:
            for action in ["inspect", "apply", "restore"]:
                with self.subTest(model=model, action=action):
                    output = io.StringIO()
                    with (
                        patch.object(
                            fonts.sys, "argv", [str(SCRIPT), action, "--model", model]
                        ),
                        patch.object(
                            fonts.os,
                            "uname",
                            return_value=SimpleNamespace(sysname="QNX"),
                        ),
                        patch.object(fonts.os, "geteuid", return_value=0),
                        redirect_stdout(output),
                    ):
                        fonts.main()
                    result = json.loads(
                        output.getvalue().removeprefix(fonts.RESULT_PREFIX)
                    )
                    self.assertTrue(result["ok"])
                    self.assertTrue(result["skipped"])
                    self.assertEqual(result["action"], action)
                    self.assertEqual(result["model"], model)
                    self.assertEqual(len(result["themes"]), 3)
        self.assertEqual(self.mount_changes, [])

    def test_missing_theme_remains_an_io_error(self):
        Path(self.profiles[2]["path"]).unlink()
        with self.assertRaises(FileNotFoundError):
            fonts.update("apply")
        self.assertEqual(self.mount_changes, [])
        self.assertEqual(list(self.state.iterdir()), [])

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

    def test_unsupported_theme_preserves_pending_recovery_until_files_are_supported(
        self,
    ):
        record = {"schema": 1, "readOnly": True, "before": {}}
        for profile, original, _ in self.fixtures:
            fonts.before_path(profile).write_bytes(original)
            record["before"][profile["path"]] = fonts.digest(original)
        first, _, patched = self.fixtures[0]
        Path(first["path"]).write_bytes(patched)
        last, original, _ = self.fixtures[2]
        Path(last["path"]).write_bytes(b"unknown firmware")
        journal = self.state / "transaction.json"
        journal.write_text(json.dumps(record))
        self.readonly = False
        before = [Path(profile["path"]).read_bytes() for profile in self.profiles]

        result = fonts.update("restore")

        self.assertTrue(result["skipped"])
        self.assertTrue(result["recoveryRequired"])
        self.assertEqual(json.loads(journal.read_text()), record)
        self.assertEqual(
            [Path(profile["path"]).read_bytes() for profile in self.profiles], before
        )
        self.assertEqual(self.mount_changes, [])
        Path(last["path"]).write_bytes(original)
        fonts.update("restore")
        self.assert_original()
        self.assertFalse(journal.exists())

    def test_corrupt_backup_does_not_overwrite_a_theme(self):
        profile = self.profiles[0]
        (self.state / (Path(profile["path"]).name + ".original")).write_bytes(
            b"bad backup"
        )
        with self.assertRaisesRegex(ValueError, "Invalid original backup"):
            fonts.update("apply")
        self.assert_original()
        self.assertEqual(self.mount_changes, [])


if __name__ == "__main__":
    unittest.main()
