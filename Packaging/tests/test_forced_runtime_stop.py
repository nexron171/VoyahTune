#!/usr/bin/env python3
"""Exercise runtime termination with an isolated process registry and clock."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "Packaging/installer/common/force-stop-runtime-device.sh"

COMMAND_FIXTURE = r'''
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(os.environ["TEST_ROOT"])
processes = root / "proc"
command, *arguments = sys.argv[1:]
configuration = json.loads((root / "configuration.json").read_text())
with (root / "commands.jsonl").open("a") as log:
    log.write(json.dumps([command, *arguments]) + "\n")

if command in configuration.get("failed_commands", []):
    print(f"{command}: denied", file=sys.stderr)
    sys.exit(1)

if command == "ps":
    columns = arguments[arguments.index("-o") + 1].split(",")
    print(" ".join(columns))
    for directory in sorted(processes.iterdir()):
        fields = (directory / "status").read_text().split()
        uid = fields[fields.index("Uid:") + 1]
        command_line = (directory / "cmdline").read_bytes().rstrip(b"\0").split(b"\0")
        if columns[-1] == "ARGS":
            command_line[0] = command_line[0].rsplit(b"/", 1)[-1]
        print(directory.name, uid, b" ".join(command_line).decode())
    for process_id in configuration.pop("disappear_after_snapshot", []):
        shutil.rmtree(processes / str(process_id))
    changed_command = configuration.pop("change_command_after_snapshot", None)
    if changed_command:
        (processes / "101/cmdline").write_bytes(changed_command.encode() + b"\0")
    (root / "configuration.json").write_text(json.dumps(configuration))
elif command == "setprop":
    if not configuration.get("service_stuck", False):
        configuration["service"] = "stopped"
        (root / "configuration.json").write_text(json.dumps(configuration))
elif command == "getprop":
    print(configuration.get("service", "stopped"))
elif command == "kill":
    if arguments[0] != "-9":
        print("unexpected graceful-stop signal", file=sys.stderr)
        sys.exit(1)
    for process_id in arguments[1:]:
        directory = processes / process_id
        if not directory.exists():
            sys.exit(1)
        if configuration.get("process_stuck", False):
            continue
        replacement = configuration.pop("replacement", None)
        if replacement:
            fields = (directory / "stat").read_text().split()
            fields[-1] = str(int(fields[-1]) + 1)
            (directory / "stat").write_text(" ".join(fields) + "\n")
            (directory / "cmdline").write_bytes(replacement.encode() + b"\0")
            (root / "configuration.json").write_text(json.dumps(configuration))
        else:
            child = configuration.pop("spawn_on_kill", None)
            if child:
                child_directory = processes / str(child)
                shutil.copytree(directory, child_directory)
                (root / "configuration.json").write_text(json.dumps(configuration))
            shutil.rmtree(directory)
elif command == "sleep":
    ticks = round(float((root / "uptime").read_text().split()[0]) * 100)
    ticks += configuration.get("elapsed_ticks", round(float(arguments[0]) * 100))
    (root / "uptime").write_text(f"{ticks // 100}.{ticks % 100:02d} 0.00\n")
elif command == "tr":
    sys.exit(subprocess.run([os.environ["REAL_TR"], *arguments]).returncode)
else:
    raise AssertionError(command)
'''


class ForcedRuntimeStopTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="forced-runtime-stop-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.processes = self.directory / "proc"
        self.processes.mkdir()
        self.uptime = self.directory / "uptime"
        self.uptime.write_text("100.00 0.00\n")
        self.packages = self.directory / "packages.list"
        self.packages.write_text(
            "ru.big.town.anative 1000 0\n"
            "ru.big.town.restoremode 10100 0\n"
            "big.town.runyn 10101 0\n"
            "ru.big.town.updater 10102 0\n"
        )
        self.command_fixture = self.directory / "commands.py"
        self.command_fixture.write_text(COMMAND_FIXTURE)

    def process(self, process_id, command, uid=0, state="S", tracer=0):
        directory = self.processes / str(process_id)
        directory.mkdir()
        directory.joinpath("cmdline").write_bytes(command.encode() + b"\0")
        fields = [state] + ["0"] * 18 + [str(process_id * 10)]
        directory.joinpath("stat").write_text(
            f"{process_id} (runtime process) {' '.join(fields)}\n"
        )
        directory.joinpath("status").write_text(f"Uid:\t{uid}\t{uid}\t{uid}\t{uid}\n")
        task = directory / "task" / str(process_id)
        task.mkdir(parents=True)
        (task / "status").write_text(f"State:\t{state}\nTracerPid:\t{tracer}\n")

    def stop(self, **configuration):
        (self.directory / "configuration.json").write_text(json.dumps(configuration))
        fixture = r'''
ps() { "$TEST_PYTHON" "$TEST_COMMAND" ps "$@"; }
setprop() { "$TEST_PYTHON" "$TEST_COMMAND" setprop "$@"; }
getprop() { "$TEST_PYTHON" "$TEST_COMMAND" getprop "$@"; }
kill() { "$TEST_PYTHON" "$TEST_COMMAND" kill "$@"; }
sleep() { "$TEST_PYTHON" "$TEST_COMMAND" sleep "$@"; }
tr() { "$TEST_PYTHON" "$TEST_COMMAND" tr "$@"; }
. "$1"
'''
        return subprocess.run(
            ["sh", "-c", fixture, "forced-stop", str(HELPER)],
            env=dict(
                os.environ,
                VOYAHTUNE_PROC_ROOT=str(self.processes),
                VOYAHTUNE_PACKAGES_LIST=str(self.packages),
                VOYAHTUNE_UPTIME_SOURCE=str(self.uptime),
                TEST_ROOT=str(self.directory),
                TEST_PYTHON=sys.executable,
                TEST_COMMAND=str(self.command_fixture),
                REAL_TR=shutil.which("tr"),
            ),
            capture_output=True,
            text=True,
            timeout=15,
        )

    def commands(self, name):
        path = self.directory / "commands.jsonl"
        commands = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        return [command[1:] for command in commands if command[0] == name]

    def test_kills_owned_processes_and_preserves_updater_and_oem_without_sleeping(self):
        owned = {
            101: ("/data/local/bin/voyahtune-new/voyahtune-loader", 0),
            102: ("/data/local/bin/voyahtune-new/voyahtune-inject", 0),
            103: ("/data/local/bin/voyahtune-new/voyahtune-inject-arm", 0),
            104: ("ru.big.town.anative", 1000),
            105: ("ru.big.town.restoremode", 10100),
            106: ("ru.big.town.restoremode:worker", 10100),
            107: ("big.town.runyn", 10101),
            108: ("/system/bin/sh\0/data/local/bin/load.bin", 0),
            109: ("/data/local/bin/loaderFrida", 0),
            110: ("/data/local/bin/frida-inject\0-s\0/data/local/tmp/steeringwheelkeys.js", 0),
        }
        preserved = {
            201: ("ru.big.town.updater", 10102),
            202: ("/data/local/bin/voyahtune-updater", 0),
            203: ("system_server", 1000),
            204: ("com.qinggan.canbus.service", 1000),
            205: ("ru.big.town.restoremode.other", 10100),
            206: ("ru.big.town.anative", 10300),
            207: ("/other/voyahtune-inject", 0),
            208: ("/system/bin/sh\0-c\0kill /data/local/bin/load.bin", 0),
            209: ("/other/program\0/data/local/bin/load.bin", 0),
            210: ("/data/local/bin/frida-inject\0-s\0/another-script.js", 0),
        }
        for process_id, (command, uid) in (owned | preserved).items():
            self.process(process_id, command, uid)
        result = self.stop(service="running")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({int(path.name) for path in self.processes.iterdir()}, set(preserved))
        self.assertEqual(self.commands("setprop"), [["ctl.stop", "voyahtune_load"]])
        self.assertEqual({int(command[1]) for command in self.commands("kill")}, set(owned))
        self.assertTrue(all(command[0] == "-9" for command in self.commands("kill")))
        self.assertEqual(self.commands("sleep"), [])

    def test_active_native_and_legacy_tracers_do_not_wait_for_detach(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-inject")
        self.process(102, "/data/local/bin/loaderFrida")
        self.process(201, "system_server", uid=1000, tracer=101)
        self.process(202, "com.qinggan.app.launcher", uid=1000, tracer=102)
        result = self.stop()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({int(path.name) for path in self.processes.iterdir()}, {201, 202})
        self.assertEqual(self.commands("sleep"), [])

    def test_unrelated_processes_do_not_spawn_per_process_commands(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
        for process_id in range(1000, 1400):
            self.process(process_id, "unrelated.process", uid=1000)
            (self.processes / str(process_id) / "task" / str(process_id) / "status").unlink()
        result = self.stop()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(list(self.processes.iterdir())), 400)
        self.assertLessEqual(len(self.commands("tr")), 4)
        self.assertLessEqual(len(self.commands("ps")), 3)
        self.assertEqual(self.commands("sleep"), [])

    def test_stuck_process_has_short_deadline_and_pid_in_error(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-inject")
        result = self.stop(process_stuck=True, elapsed_ticks=500)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("5 seconds", result.stderr)
        self.assertIn("101", result.stderr)
        self.assertEqual(self.commands("kill"), [["-9", "101"]])
        self.assertLessEqual(len(self.commands("ps")), 2)

    def test_kill_failure_is_reported_without_waiting(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
        result = self.stop(failed_commands=["kill"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("101", result.stderr)
        self.assertEqual(self.commands("sleep"), [])

    def test_process_disappearing_after_snapshot_is_already_stopped(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
        result = self.stop(disappear_after_snapshot=[101])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.commands("kill"), [])

    def test_recycled_pid_does_not_hold_shutdown_or_receive_another_signal(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
        result = self.stop(replacement="system_server")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.processes / "101").exists())
        self.assertEqual(self.commands("kill"), [["-9", "101"]])
        self.assertEqual(self.commands("sleep"), [])

    def test_process_exec_after_snapshot_does_not_kill_its_new_command(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
        result = self.stop(change_command_after_snapshot="unrelated.program")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.processes / "101").exists())
        self.assertEqual(self.commands("kill"), [])

    def test_child_created_during_shutdown_is_also_terminated_without_sleeping(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
        result = self.stop(spawn_on_kill=102)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(list(self.processes.iterdir()), [])
        self.assertEqual(self.commands("kill"), [["-9", "101"], ["-9", "102"]])
        self.assertEqual(self.commands("sleep"), [])

    def test_unreadable_candidate_identity_is_reported_before_signalling(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
        (self.processes / "101/stat").write_text("")
        result = self.stop()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("101", result.stderr)
        self.assertEqual(self.commands("kill"), [])

    def test_zombie_no_longer_blocks_replacement(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader", state="Z")
        result = self.stop()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.commands("kill"), [])

    def test_missing_components_are_already_stopped(self):
        result = self.stop(service="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.commands("kill"), [])
        self.assertEqual(self.commands("setprop"), [])
        self.assertEqual(self.commands("sleep"), [])

    def test_service_that_does_not_stop_reports_its_state(self):
        result = self.stop(service="running", service_stuck=True, elapsed_ticks=500)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("init=running", result.stderr)
        self.assertIn("5 seconds", result.stderr)

    def test_command_failures_cannot_be_treated_as_an_empty_process_list(self):
        for command in ("ps", "getprop", "setprop"):
            with self.subTest(command=command):
                result = self.stop(service="running", failed_commands=[command])
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(command, result.stderr)

    def test_missing_package_registry_does_not_skip_app_termination(self):
        self.packages.unlink()
        result = self.stop()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Package registry", result.stderr)
        self.assertEqual(self.commands("kill"), [])


if __name__ == "__main__":
    unittest.main()
