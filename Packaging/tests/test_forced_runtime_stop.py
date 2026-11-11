#!/usr/bin/env python3
"""Exercise forced stopping against an isolated process registry."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "Packaging/installer/common/force-stop-runtime-device.sh"


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

    def process(self, pid, command, uid=0, state="S", tracer=0):
        directory = self.processes / str(pid)
        directory.mkdir()
        directory.joinpath("cmdline").write_bytes(command.encode() + b"\0")
        fields = [state] + ["0"] * 18 + [str(pid * 10)]
        directory.joinpath("stat").write_text(f"{pid} (runtime process) {' '.join(fields)}\n")
        directory.joinpath("status").write_text(f"Uid:\t{uid}\t{uid}\t{uid}\t{uid}\n")
        task = directory / "task" / str(pid)
        task.mkdir(parents=True)
        (task / "status").write_text(f"State:\t{state}\nTracerPid:\t{tracer}\n")

    def stop(
        self,
        stuck=False,
        denied=False,
        service="stopped",
        detach=False,
        clock_jump=False,
        final_clock_jump=False,
        elapsed_seconds=0,
    ):
        fixture = r'''
setprop() {
    if awk '$1 == "TracerPid:" && $2 != 0 { found = 1 } END { exit !found }' "$VOYAHTUNE_PROC_ROOT"/*/task/*/status 2>/dev/null; then
        echo 'init would kill a live tracer' >&2
        return 1
    fi
    printf '%s\n' "$*" >> "$TEST_ROOT/init"
    if [ "$TEST_FINAL_CLOCK_JUMP" = 1 ]; then printf "159.90 0.00\n" > "$VOYAHTUNE_UPTIME_SOURCE"; fi
}
getprop() { printf '%s\n' "$TEST_SERVICE"; }
sleep() {
    if [ "$TEST_CLOCK_JUMP" = 1 ] || { [ "$TEST_FINAL_CLOCK_JUMP" = 1 ] && [ -e "$TEST_ROOT/init" ]; }; then
        printf "160.00 0.00\n" > "$VOYAHTUNE_UPTIME_SOURCE"
    elif [ "$TEST_ELAPSED_SECONDS" -gt 0 ]; then
        printf '%s.00 0.00\n' "$((100 + TEST_ELAPSED_SECONDS))" > "$VOYAHTUNE_UPTIME_SOURCE"
    fi
}
kill() {
    printf '%s\n' "$*" >> "$TEST_ROOT/kills"
    [ "$TEST_DENIED" != 1 ] || return 1
    case "$1" in
        -STOP)
            for status in "$VOYAHTUNE_PROC_ROOT/$2"/task/*/status; do
                sed 's/^State:.*/State: T/' "$status" > "$status.next"
                mv "$status.next" "$status"
            done ;;
        -TERM) touch "$TEST_ROOT/cancel-$2" ;;
        -CONT)
            if [ "$TEST_DETACH" = 1 ] && [ -e "$TEST_ROOT/cancel-$2" ]; then
                for status in "$VOYAHTUNE_PROC_ROOT"/*/task/*/status; do
                    sed 's/^TracerPid:.*/TracerPid: 0/' "$status" > "$status.next"
                    mv "$status.next" "$status"
                done
            fi ;;
        -9) [ "$TEST_STUCK" = 1 ] || rm -rf "$VOYAHTUNE_PROC_ROOT/$2" ;;
        *) return 1 ;;
    esac
}
. "$1"
'''
        return subprocess.run(
            ["sh", "-c", fixture, "forced-stop", str(HELPER)],
            env=dict(
                os.environ,
                VOYAHTUNE_PROC_ROOT=str(self.processes),
                VOYAHTUNE_PACKAGES_LIST=str(self.packages),
                TEST_ROOT=str(self.directory),
                TEST_STUCK=str(int(stuck)),
                TEST_DENIED=str(int(denied)),
                TEST_SERVICE=service,
                TEST_DETACH=str(int(detach)),
                TEST_CLOCK_JUMP=str(int(clock_jump)),
                TEST_FINAL_CLOCK_JUMP=str(int(final_clock_jump)),
                TEST_ELAPSED_SECONDS=str(elapsed_seconds),
                VOYAHTUNE_UPTIME_SOURCE=str(self.uptime),
            ),
            capture_output=True,
            text=True,
            timeout=15,
        )

    def test_kills_owned_processes_and_preserves_updater_and_oem(self):
        owned = {
            101: ("/data/local/bin/voyahtune-new/voyahtune-loader", 0),
            102: ("/data/local/bin/voyahtune-new/voyahtune-inject", 0),
            103: ("/data/local/bin/voyahtune-new/voyahtune-inject-arm", 0),
            104: ("ru.big.town.anative", 1000),
            105: ("ru.big.town.restoremode", 10100),
            106: ("ru.big.town.restoremode:worker", 10100),
            107: ("big.town.runyn", 10101),
            108: ("/system/bin/sh\0/data/local/bin/load.bin", 0),
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
        }
        for pid, (command, uid) in (owned | preserved).items():
            self.process(pid, command, uid)
        result = self.stop()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({int(path.name) for path in self.processes.iterdir()}, set(preserved))
        self.assertEqual(self.directory.joinpath("init").read_text(), "ctl.stop voyahtune_load\n")
        self.assertEqual(
            {line for line in self.directory.joinpath("kills").read_text().splitlines() if line.startswith("-9 ")},
            {f"-9 {pid}" for pid in owned},
        )

    def test_active_tracer_detaches_before_init_stop_without_waiting_for_java(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-inject")
        self.process(201, "system_server", uid=1000, tracer=102)
        task = self.processes / "101/task/102"
        task.mkdir()
        (task / "status").write_text("State: S\nTracerPid: 0\n")
        result = self.stop(detach=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        signals = self.directory.joinpath("kills").read_text().splitlines()
        self.assertLess(signals.index("-TERM 101"), signals.index("-CONT 101"))
        self.assertLess(signals.index("-CONT 101"), signals.index("-9 101"))
        self.assertTrue((self.processes / "201").exists())
        self.assertIn("TracerPid: 0", (self.processes / "201/task/201/status").read_text())

    def test_unfinished_native_call_returns_error_without_killing_tracer_or_cgroup(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-inject")
        self.process(201, "system_server", uid=1000, tracer=101)
        result = self.stop()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not detach", result.stderr)
        self.assertNotIn("-9 101", self.directory.joinpath("kills").read_text())
        self.assertFalse((self.directory / "init").exists())

    def test_wall_clock_deadline_includes_work_between_retries(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-inject")
        result = self.stop(clock_jump=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Runtime stop exceeded 60 seconds", result.stderr)
        self.assertFalse((self.directory / "init").exists())

    def test_stop_can_complete_after_the_previous_five_second_limit(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
        result = self.stop(elapsed_seconds=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.processes / "101").exists())
        self.assertIn("-9 101", self.directory.joinpath("kills").read_text())

    def test_final_kill_retries_share_the_original_deadline(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-inject")
        result = self.stop(stuck=True, final_clock_jump=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Runtime stop exceeded 60 seconds", result.stderr)
        signals = self.directory.joinpath("kills").read_text().splitlines()
        self.assertEqual(signals.count("-9 101"), 1)
        self.assertTrue((self.directory / "init").exists())

    def test_incomplete_task_status_cannot_authorize_cgroup_kill(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-inject")
        self.process(201, "system_server", uid=1000)
        (self.processes / "201/task/201/status").write_text("State: S\n")
        result = self.stop()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Cannot inspect tracer", result.stderr)
        self.assertFalse((self.directory / "init").exists())

    def test_unreadable_known_helper_identity_fails_closed(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-inject")
        (self.processes / "101/status").write_text("")
        result = self.stop()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("identity unreadable", result.stderr)
        self.assertFalse((self.directory / "init").exists())

    def test_unknown_legacy_tracer_is_not_terminated(self):
        self.process(101, "/data/local/bin/loaderFrida")
        self.process(201, "system_server", uid=1000, tracer=101)
        result = self.stop()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cannot safely detach", result.stderr)
        self.assertNotIn("-TERM", self.directory.joinpath("kills").read_text())
        self.assertFalse((self.directory / "init").exists())

    def test_nonresponsive_or_unkillable_process_fails_without_waiting_for_ack(self):
        for denied in (False, True):
            with self.subTest(denied=denied):
                if not (self.processes / "101").exists():
                    self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader")
                result = self.stop(stuck=True, denied=denied)
                self.assertNotEqual(result.returncode, 0)
                self.assertTrue((self.processes / "101").exists())

    def test_zombie_no_longer_blocks_replacement(self):
        self.process(101, "/data/local/bin/voyahtune-new/voyahtune-loader", state="Z")
        self.assertEqual(self.stop().returncode, 0)
        self.assertFalse((self.directory / "kills").exists())

    def test_missing_components_are_already_stopped(self):
        self.assertEqual(self.stop().returncode, 0)
        self.assertFalse((self.directory / "kills").exists())

    def test_running_init_service_cannot_be_treated_as_stopped(self):
        self.assertNotEqual(self.stop(service="running").returncode, 0)

    def test_missing_package_registry_does_not_skip_app_termination(self):
        self.packages.unlink()
        self.assertNotEqual(self.stop().returncode, 0)


if __name__ == "__main__":
    unittest.main()
