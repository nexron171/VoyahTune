"""Exercise structural KZB updates, exact removal and interrupted transactions."""

from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from navigation_font_fixtures import build_theme, FONT_DATA

SCRIPT = Path(__file__).parents[1] / "install/navigation_fonts.py"
SPEC = importlib.util.spec_from_file_location("navigation_fonts", SCRIPT)
fonts = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fonts)


class NavigationFontTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.paths = [
            self.root / (name + ".kzb")
            for name in ("gauge_normal", "gauge_classic", "gauge_elegant")
        ]
        self.originals = [
            build_theme(path.stem, extra_properties=index, padding=index * 33)
            for index, path in enumerate(self.paths)
        ]
        self.patched = [
            build_theme(
                path.stem, patched=True, extra_properties=index, padding=index * 33
            )
            for index, path in enumerate(self.paths)
        ]
        for path, data in zip(self.paths, self.originals):
            path.write_bytes(data)
            path.chmod(0o755)
        self.state = self.root / "state"
        self.state.mkdir()
        self.readonly = True
        self.mount_changes = []

        def remount(readonly):
            self.readonly = readonly
            self.mount_changes.append(readonly)

        for replacement in (
            patch.object(fonts, "THEME_PATHS", self.paths),
            patch.object(fonts, "STATE_DIRECTORY", self.state),
            patch.object(
                fonts,
                "SUPPORTED_FONT_DIGESTS",
                {name: fonts.digest(data) for name, data in FONT_DATA.items()},
            ),
            patch.object(fonts, "read_only", lambda: self.readonly),
            patch.object(fonts, "remount", remount),
        ):
            replacement.start()
            self.addCleanup(replacement.stop)

    def assert_original(self):
        for path, original in zip(self.paths, self.originals):
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(path.stat().st_mode & 0o777, 0o755)
        self.assertTrue(self.readonly)

    def journal(self, schema=2):
        before = {}
        after = {}
        for path, original, patched in zip(self.paths, self.originals, self.patched):
            fonts.before_path(path).write_bytes(original)
            before[str(path)] = fonts.digest(original)
            after[str(path)] = fonts.digest(patched)
        record = {"schema": schema, "readOnly": True, "before": before}
        if schema == 2:
            record["after"] = after
        (self.state / "transaction.json").write_text(json.dumps(record))
        return record

    def test_structural_search_handles_relocated_fields_and_preserves_other_resources(
        self,
    ):
        offsets = []
        for extra, padding in ((0, 0), (3, 79), (6, 2048)):
            source = build_theme(
                "gauge_normal", extra_properties=extra, padding=padding
            )
            expected = build_theme(
                "gauge_normal", patched=True, extra_properties=extra, padding=padding
            )
            result = fonts.transform(source)
            self.assertEqual(result, expected)
            self.assertEqual(len(result), len(source))
            self.assertEqual(fonts.transform(result), result)
            self.assertEqual(fonts.transform(result, restore=True), source)
            layout = fonts.inspect_layout(source)
            offsets.append(layout["fields"][0]["start"])
            for start, end in layout["numeric"]:
                self.assertEqual(source[start:end], result[start:end])
        self.assertEqual(len(set(offsets)), 3)

    def test_unsupported_or_partial_theme_skips_without_writes(self):
        for index, (path, original, patched) in enumerate(
            zip(self.paths, self.originals, self.patched)
        ):
            layout = fonts.inspect_layout(original)
            field = layout["fields"][0]
            partial = bytearray(original)
            partial[field["start"] : field["end"]] = patched[
                field["start"] : field["end"]
            ]
            for data in (
                original[:-1],
                original + b"extra",
                b"!" + original[1:],
                bytes(partial),
            ):
                for action in ("inspect", "apply", "restore"):
                    with self.subTest(theme=index, action=action, size=len(data)):
                        for current, initial in zip(self.paths, self.originals):
                            current.write_bytes(initial)
                        path.write_bytes(data)
                        before = [current.read_bytes() for current in self.paths]
                        result = (
                            fonts.inspect()
                            if action == "inspect"
                            else fonts.update(action)
                        )
                        self.assertTrue(result["skipped"])
                        self.assertEqual(result["reason"], "unsupported_theme")
                        self.assertFalse(result["themes"][index]["supported"])
                        self.assertEqual(
                            [current.read_bytes() for current in self.paths], before
                        )
                        self.assertEqual(self.mount_changes, [])
                        self.assertEqual(list(self.state.iterdir()), [])

    def test_main_emits_successful_skip_for_both_models_and_all_actions(self):
        self.paths[1].write_bytes(b"unknown firmware")
        for model in ("H97X", "H97Y"):
            for action in ("inspect", "apply", "restore"):
                output = io.StringIO()
                with (
                    patch.object(
                        fonts.sys, "argv", [str(SCRIPT), action, "--model", model]
                    ),
                    patch.object(
                        fonts.os, "uname", return_value=SimpleNamespace(sysname="QNX")
                    ),
                    patch.object(fonts.os, "geteuid", return_value=0),
                    redirect_stdout(output),
                ):
                    fonts.main()
                result = json.loads(output.getvalue().removeprefix(fonts.RESULT_PREFIX))
                self.assertTrue(result["ok"])
                self.assertTrue(result["skipped"])
                self.assertEqual((result["action"], result["model"]), (action, model))

    def test_apply_repeat_and_exact_removal(self):
        fonts.update("apply")
        self.assertEqual([path.read_bytes() for path in self.paths], self.patched)
        backups = {
            path.name: path.read_bytes() for path in self.state.glob("*.original")
        }
        changes = self.mount_changes[:]
        fonts.update("apply")
        self.assertEqual(self.mount_changes, changes)
        self.assertEqual(
            {path.name: path.read_bytes() for path in self.state.glob("*.original")},
            backups,
        )
        fonts.update("restore")
        self.assert_original()
        self.assertFalse((self.state / "transaction.json").exists())
        fonts.update("restore")
        self.assert_original()

    def test_oem_revision_gets_its_own_backup_and_is_restored_exactly(self):
        fonts.update("apply")
        previous = fonts.backup_path(self.paths[0], fonts.digest(self.originals[0]))
        updated = build_theme(self.paths[0].stem, extra_properties=4, padding=517)
        self.paths[0].write_bytes(updated)
        self.originals[0] = updated
        fonts.update("apply")
        current = fonts.backup_path(self.paths[0], fonts.digest(updated))
        self.assertNotEqual(previous, current)
        self.assertTrue(previous.exists())
        self.assertEqual(current.read_bytes(), updated)
        fonts.update("restore")
        self.assert_original()

    def test_unowned_roboto_themes_are_not_reverted(self):
        for path, data in zip(self.paths, self.patched):
            path.write_bytes(data)
        fonts.update("apply")
        fonts.update("restore")
        self.assertEqual([path.read_bytes() for path in self.paths], self.patched)
        self.assertEqual(self.mount_changes, [])
        self.assertEqual(list(self.state.iterdir()), [])

    def test_legacy_owned_patch_is_reversible_without_new_receipt(self):
        known = {
            fonts.digest(patched): fonts.digest(original)
            for patched, original in zip(self.patched, self.originals)
        }
        for path, original, patched in zip(self.paths, self.originals, self.patched):
            path.write_bytes(patched)
            (self.state / (path.name + ".original")).write_bytes(original)
        with patch.object(fonts, "LEGACY_PATCHES", known):
            fonts.update("restore")
        self.assert_original()

    def test_failure_on_third_rename_rolls_back_first_two(self):
        original_replace = fonts.os.replace
        failed = False

        def replace(source, destination):
            nonlocal failed
            if str(destination) == str(self.paths[2]) and not failed:
                failed = True
                raise OSError("injected rename failure")
            return original_replace(source, destination)

        with patch.object(fonts.os, "replace", replace):
            with self.assertRaisesRegex(OSError, "injected"):
                fonts.update("apply")
        self.assert_original()
        self.assertFalse((self.state / "transaction.json").exists())

    def test_both_journal_versions_recover_before_update(self):
        for schema in (1, 2):
            with self.subTest(schema=schema):
                self.journal(schema)
                self.paths[0].write_bytes(self.patched[0])
                self.readonly = False
                fonts.update("restore")
                self.assert_original()
                self.assertFalse((self.state / "transaction.json").exists())

    def test_unknown_theme_preserves_pending_recovery(self):
        record = self.journal()
        self.paths[0].write_bytes(self.patched[0])
        self.paths[2].write_bytes(b"unknown firmware")
        before = [path.read_bytes() for path in self.paths]
        result = fonts.update("restore")
        self.assertTrue(result["skipped"])
        self.assertTrue(result["recoveryRequired"])
        self.assertEqual([path.read_bytes() for path in self.paths], before)
        self.assertEqual(
            json.loads((self.state / "transaction.json").read_text()), record
        )
        self.assertEqual(self.mount_changes, [])

    def test_corrupt_original_backup_is_not_used_for_removal(self):
        fonts.update("apply")
        backup = fonts.backup_path(self.paths[0], fonts.digest(self.originals[0]))
        backup.write_bytes(b"bad backup")
        with self.assertRaisesRegex(ValueError, "Invalid original backup"):
            fonts.update("restore")
        self.assertEqual([path.read_bytes() for path in self.paths], self.patched)
        self.assertTrue(self.readonly)

    def test_corrupt_transaction_backup_is_not_restored(self):
        self.journal()
        self.paths[0].write_bytes(self.patched[0])
        fonts.before_path(self.paths[0]).write_bytes(b"bad")
        with self.assertRaisesRegex(ValueError, "Invalid transaction backup"):
            fonts.update("restore")
        self.assertEqual(self.paths[0].read_bytes(), self.patched[0])
        self.assertEqual(self.mount_changes, [])

    def test_missing_theme_remains_an_io_error(self):
        self.paths[2].unlink()
        with self.assertRaises(FileNotFoundError):
            fonts.update("apply")
        self.assertEqual(self.mount_changes, [])

    def test_invalid_font_records_or_resources_are_skipped(self):
        original = self.originals[0]
        layout = fonts.inspect_layout(original)
        first = layout["fields"][0]
        invalid_length = bytearray(original)
        invalid_length[first["start"] : first["start"] + 2] = b"\xff\xff"
        invalid_url = bytearray(original)
        invalid_url[first["start"] + 2] = ord("x")
        wrong_bindings = original.replace(b"TBT.road", b"TBT.unit", 1)
        missing_font = original.replace(b"Roboto-Regular.ttf", b"Xoboto-Regular.ttf", 1)
        wrong_type = bytearray(original)
        wrong_type[first["start"] - 4 : first["start"]] = b"\x11\0\0\0"
        for data in (
            bytes(invalid_length),
            bytes(invalid_url),
            wrong_bindings,
            missing_font,
            bytes(wrong_type),
        ):
            with self.subTest(source=fonts.digest(data)):
                self.paths[0].write_bytes(data)
                result = fonts.update("apply")
                self.assertTrue(result["skipped"])
                self.assertEqual(self.paths[0].read_bytes(), data)
                self.assertEqual(self.mount_changes, [])
                self.assertEqual(list(self.state.iterdir()), [])

    def test_concurrent_oem_change_is_not_overwritten_by_rollback(self):
        write = fonts.write_file
        replacement = build_theme(self.paths[0].stem, extra_properties=2, padding=401)
        changed = False

        def write_and_change(path, data, metadata=None):
            nonlocal changed
            write(path, data, metadata)
            if path == fonts.stage_path(self.paths[0]) and not changed:
                changed = True
                self.paths[0].write_bytes(replacement)

        with patch.object(fonts, "write_file", write_and_change):
            with self.assertRaises(fonts.UnsupportedThemeError):
                fonts.update("apply")
        self.assertEqual(self.paths[0].read_bytes(), replacement)
        self.assertTrue((self.state / "transaction.json").exists())
        self.assertTrue(self.readonly)
        result = fonts.update("apply")
        self.assertTrue(result["skipped"])
        self.assertEqual(self.paths[0].read_bytes(), replacement)


if __name__ == "__main__":
    unittest.main()
