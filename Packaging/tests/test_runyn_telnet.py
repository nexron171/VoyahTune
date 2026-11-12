import os
from pathlib import Path
import subprocess
import tempfile
import unittest


STOP_SCRIPT = Path(__file__).resolve().parents[1] / "installer/common/stop-runyn-telnet.sh"


class RunynTelnetStopTest(unittest.TestCase):
    def run_stop(self, state, mode="stop"):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "state").write_text(state)
            (root / "mode").write_text(mode)
            commands = {
                "getprop": 'test "$1" = init.svc.voyahtune_runyn_telnet || exit 2\ncat "$TEST_ROOT/state"',
                "setprop": '''test "$1" = ctl.stop || exit 2
                    test "$2" = voyahtune_runyn_telnet || exit 2
                    printf '%s\\n' "$*" >> "$TEST_ROOT/calls"
                    case "$(cat "$TEST_ROOT/mode")" in
                        stop) echo stopped > "$TEST_ROOT/state" ;;
                        fail) exit 1 ;;
                    esac''',
                "sleep": "exit 0",
            }
            for name, body in commands.items():
                executable = root / name
                executable.write_text("#!/bin/sh\n" + body + "\n")
                executable.chmod(0o755)
            environment = dict(os.environ, TEST_ROOT=str(root))
            environment["PATH"] = str(root) + os.pathsep + environment["PATH"]
            result = subprocess.run(
                ["sh", str(STOP_SCRIPT)], env=environment, capture_output=True, text=True
            )
            calls = root / "calls"
            return result, calls.read_text() if calls.exists() else ""

    def test_missing_or_stopped_service_needs_no_stop_command(self):
        for state in ["", "stopped"]:
            with self.subTest(state=state):
                result, calls = self.run_stop(state)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, "")

    def test_running_or_restarting_service_is_stopped(self):
        for state in ["running", "restarting"]:
            with self.subTest(state=state):
                result, calls = self.run_stop(state)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, "ctl.stop voyahtune_runyn_telnet\n")

    def test_stop_failure_aborts_maintenance(self):
        result, _ = self.run_stop("running", "fail")
        self.assertNotEqual(result.returncode, 0)

    def test_unresponsive_service_aborts_maintenance(self):
        result, _ = self.run_stop("running", "keep-running")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not stop", result.stderr)


if __name__ == "__main__":
    unittest.main()
