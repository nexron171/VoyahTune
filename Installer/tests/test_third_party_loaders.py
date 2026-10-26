#!/usr/bin/env python3
"""Run OD loader cleanup against a temporary filesystem and stub Android commands."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "Packaging/installer/common/disable-third-party-loaders.sh"


class ThirdPartyLoaderTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        script = re.sub(
            r"/(system_ext|system|vendor|odm|product|data|sdcard|storage|proc)(?=/)",
            lambda match: str(self.root) + match.group(),
            SCRIPT.read_text(),
        )
        self.script = r"""
pm() {
    printf 'pm %s\n' "$*" >> "$TEST_ROOT/commands"
    case "$1" in
        list) printf 'package:android\npackage:ru.kachalin.voyahtweaks\npackage:com.cunba.lantu.otan.motorstore\n' ;;
    esac
}
am() { printf 'am %s\n' "$*" >> "$TEST_ROOT/commands"; }
kill() { printf 'kill %s\n' "$*" >> "$TEST_ROOT/commands"; }
setprop() { printf 'setprop %s\n' "$*" >> "$TEST_ROOT/commands"; }
restorecon() { :; }
mount() { :; }
sync() { :; }
""" + script

    def write(self, path, content):
        destination = self.root / path.lstrip("/")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content)
        return destination

    def run_cleanup(self):
        result = subprocess.run(
            ["/bin/sh", "-c", self.script],
            env={**os.environ, "TEST_ROOT": str(self.root)},
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_known_paths_are_disabled_without_loader_signatures(self):
        loader = self.write("/data/local/tmp/load.bin", "unknown old loader bytes")
        injector = self.write("/data/local/bin/frida-inject2", "injector")
        owned = self.write("/data/local/bin/load.bin", "VoyahTune")
        self.write("/data/media/0/Download/cunba/patch/load.bin", "watchdog")
        self.run_cleanup()
        self.assertFalse(loader.exists())
        self.assertEqual(
            Path(str(loader) + ".voyahtune-disabled").read_text(),
            "unknown old loader bytes",
        )
        self.assertFalse(injector.exists())
        self.assertFalse((self.root / "data/media/0/Download/cunba").exists())
        self.assertEqual(owned.read_text(), "VoyahTune")
        commands = (self.root / "commands").read_text()
        self.assertIn("pm disable-user --user 0 ru.kachalin.voyahtweaks", commands)
        self.assertIn(
            "pm disable-user --user 0 com.cunba.lantu.otan.motorstore", commands
        )
        self.run_cleanup()
        self.assertEqual(owned.read_text(), "VoyahTune")

    def test_startup_cleanup_preserves_unrelated_init_services(self):
        startup = self.write(
            "/system/etc/init/vendor.rc",
            "service cunba /system/bin/frida-inject2\n    class main\n"
            "service oem /system/bin/oem\n    class main\n",
        )
        boot_script = self.write(
            "/data/adb/service.d/launch.sh", "#!/system/bin/sh\nstart_voyahtweaks\n"
        )
        dedicated = self.write(
            "/vendor/etc/init/cunba.rc",
            "service helper /vendor/bin/helper\n    class main\n",
        )
        self.write("/proc/1234/cmdline", "/system/bin/frida-inject2\0--foreground\0")
        self.run_cleanup()
        self.assertNotIn("cunba", startup.read_text())
        self.assertFalse(dedicated.exists())
        self.assertIn(
            "service oem /system/bin/oem\n    class main", startup.read_text()
        )
        self.assertEqual(boot_script.read_text(), f"#!{self.root}/system/bin/sh\n:\n")
        self.assertEqual(list(boot_script.parent.iterdir()), [boot_script])
        self.assertIn("kill 1234", (self.root / "commands").read_text())
        self.run_cleanup()
        saved = list(
            (self.root / "data/local/voyahtune-disabled-loaders").rglob("launch.sh")
        )
        self.assertEqual(len(saved), 1)
        self.assertIn("start_voyahtweaks", saved[0].read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
