#!/usr/bin/env python3
"""Run the real loader's launch section with fake maintenance/hooks processes."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LoaderWorkerTest(unittest.TestCase):
    def test_pending_ui_update_does_not_hold_up_hooks(self):
        source = (ROOT / "Packaging/od/system/voyahtune.load.sh").read_text()
        section = source[source.index("# This worker"):]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / "voyahtune-ui-maintenance"
            helper.write_text('#!/bin/sh\n'
                              'echo $$ > "$TEST_ROOT/worker.pid"\n'
                              'touch "$TEST_ROOT/waiting"\n'
                              'while [ ! -e "$TEST_ROOT/committed" ]; do sleep 0.05; done\n'
                              'touch "$TEST_ROOT/complete"\n')
            helper.chmod(0o755)
            (root / "load.bin").write_text('touch "$TEST_ROOT/hooks-started"\n')
            script = root / "loader.sh"
            script.write_text('logi() { :; }\n' + section.replace(
                "/data/local/bin/", str(root) + "/").replace(
                "/data/local/tmp/", str(root) + "/").replace("/system/bin/sh", "/bin/sh"))
            env = dict(os.environ, TEST_ROOT=str(root))
            process = subprocess.Popen(["/bin/sh", str(script)], env=env, start_new_session=True)
            try:
                self.assertEqual(process.wait(timeout=3), 0)
                self.assertTrue((root / "hooks-started").exists())
                self.assertFalse((root / "complete").exists())
                (root / "committed").touch()
                deadline = time.monotonic() + 3
                while not (root / "complete").exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue((root / "complete").exists())
            finally:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass


if __name__ == "__main__":
    unittest.main()
