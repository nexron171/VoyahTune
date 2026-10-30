"""Exercise post-restore init transitions with a real admission marker."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "installer/common/start-restored-runtime.sh"


class RestoredRuntimeTest(unittest.TestCase):
    def run_runtime(self, blocked, stop_fails=False, start_fails=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "commands").touch()
            marker = root / "update.block"
            if blocked:
                marker.touch()
            fixture = '''
setprop() {
    echo "$1" >> "$TEST_ROOT/commands"
    if [ "$1" = ctl.stop ]; then
        [ -e "$TEST_ROOT/update.block" ] || return 9
        [ "$STOP_FAILS" = true ] || touch "$TEST_ROOT/stopped"
    else
        [ ! -e "$TEST_ROOT/update.block" ] || return 8
        [ "$START_FAILS" != true ] || return 7
        touch "$TEST_ROOT/running"
    fi
}
getprop() {
    if [ -e "$TEST_ROOT/stopped" ]; then echo stopped; else echo running; fi
}
sleep() { :; }
sync() { :; }
'''
            script = SCRIPT.read_text().replace("/data/local/bin/voyahtune-update.block", str(marker))
            result = subprocess.run(
                ["sh", "-c", fixture + script], capture_output=True, text=True,
                env=dict(os.environ, TEST_ROOT=directory,
                         STOP_FAILS=str(stop_fails).lower(), START_FAILS=str(start_fails).lower()),
                timeout=5,
            )
            return result.returncode, marker.exists(), (root / "running").exists(), (root / "commands").read_text().splitlines()

    def test_blocked_runtime_restarts_after_restoration(self):
        self.assertEqual(self.run_runtime(True), (0, False, True, ["ctl.stop", "ctl.start"]))

    def test_repeat_without_block_starts_service_without_stopping_it(self):
        self.assertEqual(self.run_runtime(False), (0, False, True, ["ctl.start"]))

    def test_failed_stop_keeps_admission_closed(self):
        code, marker, running, commands = self.run_runtime(True, stop_fails=True)
        self.assertNotEqual(code, 0)
        self.assertEqual((marker, running, commands), (True, False, ["ctl.stop"]))

    def test_failed_start_is_reported(self):
        code, marker, running, commands = self.run_runtime(True, start_fails=True)
        self.assertNotEqual(code, 0)
        self.assertEqual((marker, running, commands), (False, False, ["ctl.stop", "ctl.start"]))


if __name__ == "__main__":
    unittest.main()
