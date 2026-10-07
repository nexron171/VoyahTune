#!/usr/bin/env python3
"""Exercise the OD loader boundary after moving the OTA UI into RestoreMode."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LoaderOtaBoundaryTest(unittest.TestCase):
    def run_loader(self, blocked):
        source = (ROOT / "Packaging/od/system/voyahtune.load.sh").read_text()
        section = source[source.index("# A persistent block"):]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / "voyahtune-ui-maintenance"
            helper.write_text('#!/bin/sh\ntouch "$TEST_ROOT/obsolete-worker-started"\n')
            helper.chmod(0o755)
            (root / "load.bin").write_text('touch "$TEST_ROOT/hooks-started"\n')
            block = root / "voyahtune-update.block"
            if blocked:
                block.touch()
            script = root / "loader.sh"
            script.write_text('logi() { :; }\n' + section.replace(
                "/data/local/bin/", str(root) + "/").replace(
                "/data/local/tmp/", str(root) + "/").replace(
                "/system/bin/sh", "/bin/sh").replace("sleep 10", "sleep 0.02"))
            process = subprocess.Popen(["/bin/sh", str(script)],
                env=dict(os.environ, TEST_ROOT=str(root)), start_new_session=True)
            try:
                if blocked:
                    time.sleep(0.15)
                    self.assertIsNone(process.poll())
                    self.assertFalse((root / "hooks-started").exists())
                    block.unlink()
                self.assertEqual(process.wait(timeout=3), 0)
                self.assertTrue((root / "hooks-started").exists())
                self.assertFalse((root / "obsolete-worker-started").exists())
            finally:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass

    def test_starts_hooks_without_a_separate_ui_worker(self):
        self.run_loader(False)

    def test_persistent_update_block_stops_hooks_until_released(self):
        self.run_loader(True)


if __name__ == "__main__":
    unittest.main()
