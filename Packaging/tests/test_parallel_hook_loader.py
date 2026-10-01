#!/usr/bin/env python3
"""Run the real shell supervisor/workers against fake Android processes and a gated injector.

No ADB, Frida, Android, or network required. /proc identity/Binder endpoints are host fixtures;
the production scheduling, lane locks, snapshots, injection reservations and retries run unchanged.
"""
import os
from pathlib import Path
import shlex
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "Packaging/system/load.bin").read_text()


class ParallelLoaderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="voyahtune-loader-")
        self.root = Path(self.temp.name)
        self.processes = []
        settings=self.root/"settings"
        settings.write_text("#!/bin/sh\nexit 1\n")
        settings.chmod(0o755)
        self.env={**os.environ,"PATH":str(self.root)+os.pathsep+os.environ["PATH"]}
        for pid in range(101, 110):
            (self.root / f"generation.{pid}").write_text("1\n")
        (self.root / "proc").mkdir()
        (self.root / "proc/uptime").write_text("100.00 0\n")
        (self.root / "events").touch()
        self.injector = self.root / "frida-inject"
        self.injector.write_text("""#!/bin/sh
set -eu
root=ROOT_PLACEHOLDER
pid=$2
script=${4##*/}
mkdir -p "$root/proc/$$"
printf 'frida-inject -p %s -s %s ' "$pid" "$4" > "$root/proc/$$/cmdline"
trap 'rm -rf "$root/proc/$$"' 0
printf 'start %s %s\n' "$script" "$pid" >> "$root/events"
case "$script" in
    steeringwheelkeys.js)
        if [ ! -f "$root/missing_ready" ]; then echo '[swk] keymanager hooks installed: test'; fi
        gate=steering ;;
    vd_bypass.js) gate=vd ;;
    multidisplay.js) echo '[multidisplay] hook ready v2 test'; gate=none ;;
    apollo_tech.js) echo '[apollo] hook ready'; gate=none ;;
    voyahtune_acc_restore.js)
        while [ -f "$root/hold_acc_ready" ]; do sleep 0.05; done
        if [ ! -f "$root/missing_acc_ready" ]; then echo '[acc-restore] hook ready v2'; fi
        if [ -f "$root/block_acc" ]; then gate=acc; else gate=none; fi ;;
    app_client.js)
        echo '[app-client] hook ready v1'
        if [ ! -f "$root/missing_rds_ready" ]; then echo '[rds-restore] hook ready v1'; fi
        gate=none ;;
    voyahtune_drive_reset.js)
        if [ ! -f "$root/missing_drive_ready" ]; then echo '[drive-reset] hook ready v2'; fi
        gate=none ;;
    *) gate=none ;;
esac
while [ "$gate" != none ] && [ ! -f "$root/release_$gate" ]; do sleep 0.05; done
if [ "$script" = vd_bypass.js ] && [ ! -f "$root/missing_vd_ready" ]; then
    mkdir -p "$root/runtime/vd_hooks"
    printf 'v2:test:%s:%s|3.22.0-v1|active|geometry\n' "$pid" "$(cat "$root/generation.$pid")" > "$root/runtime/vd_hooks/status.v1"
fi
printf 'end %s %s\n' "$script" "$pid" >> "$root/events"
""".replace("ROOT_PLACEHOLDER", shlex.quote(str(self.root))))
        self.injector.chmod(0o755)
        overrides = r'''
# Host-only platform shims. Keep all worker and injection functions from the loader intact.
FIXTURE=ROOT_PLACEHOLDER
FI="$FIXTURE/frida-inject"
ACC_POLL_SECONDS=0.02
WATCHDOG_CYCLE_SECONDS=0.05
OPTIONAL_CYCLE_SECONDS=0.05
worker_token() {
    kill -0 "$1" 2>/dev/null || return 1
    token_start=$(ps -o lstart= -p "$1" | tr -d ' \n')
    [ -n "$token_start" ] || return 1
    printf '%s:%s:host\n' "$1" "$token_start"
}
loader_pid_is_live() { kill -0 "$1" 2>/dev/null; }
process_identity() {
    [ -r "$FIXTURE/generation.$1" ] || return 1
    printf 'v2:test:%s:%s\n' "$1" "$(cat "$FIXTURE/generation.$1")"
}
pidof() {
    case "$1" in
        com.qinggan.keymanager.service) [ -e "$FIXTURE/missing_steering" ] || echo 101 ;;
        com.qinggan.systemservice) echo 102 ;;
        com.qinggan.app.launcher) echo 103 ;;
        system_server) echo 104 ;;
        com.qinggan.app.vehiclesetting) echo 105 ;;
        com.qinggan.app.qgime) echo 106 ;;
        com.qinggan.app.vehicle) [ ! -e "$FIXTURE/enable_vehicle" ] || echo 108 ;;
        com.pateo.rdsapp) [ ! -e "$FIXTURE/enable_rds" ] || echo 109 ;;
        com.qinggan.canbus.service) [ -e "$FIXTURE/missing_acc" ] || echo 107 ;;
        frida-inject)
            for f in "$FIXTURE"/proc/*/cmdline; do
                [ -f "$f" ] || continue
                p=${f%/cmdline}; p=${p##*/}
                kill -0 "$p" 2>/dev/null && echo "$p"
            done
            ;;
    esac
}
drive_agent_attempt_finished() {
    fixture_drive_agent_attempt_finished "$@"
    printf '%s\n' "$1" >> "$FIXTURE/finished_attempts"
}
prepare_vd_status() { mkdir -p "$FIXTURE/runtime/vd_hooks"; }
prepare_drive_health() { mkdir -p "${1%/*}"; [ -e "$1" ] || : > "$1"; }
logi() { printf '%s\n' "$*" >> "$FIXTURE/log"; }
loge() { logi "$*"; }
log() { :; }
timeout() { shift 3; "$@"; }
settings() { echo en; }
apollo_runtime_flag_enabled() { [ ! -f "$FIXTURE/disable_apollo" ]; }
getprop() {
    while [ ! -f "$FIXTURE/release_status" ]; do sleep 0.05; done
    echo 0
}
discover_app_client() {
    echo 'apps discovery' >> "$FIXTURE/events"
    while [ ! -f "$FIXTURE/release_apps" ]; do sleep 0.05; done
}
if [ "${1:-}" = --probe ]; then eval "$2"; exit; fi
'''.replace("ROOT_PLACEHOLDER", shlex.quote(str(self.root)))
        source = SOURCE.replace("/data/local/tmp", str(self.root)).replace("/data/local/open_voyah", str(self.root / "runtime"))
        source = source.replace("drive_agent_attempt_finished() {", "fixture_drive_agent_attempt_finished() {")
        source = source.replace("discover_app_client() {", "fixture_discover_app_client() {")
        source = source.replace("/proc/", str(self.root / "proc") + "/")
        entry = 'if [ "${1:-}" = --worker ]; then'
        source = source.replace(entry, overrides + "\n" + entry)
        self.loader = self.root / "load.bin"
        self.loader.write_text(source)

    def rds_probe(self, uid=10109, executable="/system/bin/app_process64", enabled=True):
        if enabled:
            (self.root / "enable_rds").touch()
        (self.root / "proc/sys/kernel/random").mkdir(parents=True, exist_ok=True)
        (self.root / "proc/sys/kernel/random/boot_id").write_text("test-boot\n")
        (self.root / "proc/109").mkdir(exist_ok=True)
        (self.root / "proc/109/status").write_text(f"Uid: {uid} {uid} {uid} {uid}\n")
        exe = self.root / "proc/109/exe"
        if not exe.is_symlink():
            exe.symlink_to(executable)
        (self.root / "app_client.js").touch()
        subprocess.run(["sh", str(self.loader), "--probe",
                        'APP_CLIENT="$FIXTURE/app_client.js"; settings() { echo null; }; '
                        'fixture_discover_app_client; wait'],
                       check=True, timeout=10, env=self.env, capture_output=True)

    def test_rds_discovered_without_fullscreen_or_dpi_selection(self):
        self.rds_probe()
        marker = self.root / "voyahtune_app_client.com.pateo.rdsapp.pid"
        self.assertEqual(marker.read_text().strip(), "v2:test:109:1")
        self.rds_probe()
        self.assertEqual((self.root / "events").read_text().count("start app_client.js 109"), 1)

    def test_rds_requires_specific_ready_marker(self):
        (self.root / "missing_rds_ready").touch()
        self.rds_probe()
        self.assertFalse((self.root / "voyahtune_app_client.com.pateo.rdsapp.pid").exists())
        self.assertTrue((self.root / "voyahtune_app_client.com.pateo.rdsapp.attempt").exists())

    def test_rds_does_not_attach_without_a_running_process(self):
        self.rds_probe(enabled=False)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_rds_rejects_secondary_android_user(self):
        self.rds_probe(uid=110109)
        self.assertEqual((self.root / "events").read_text(), "")

    def test_rds_rejects_unsupported_32_bit_process(self):
        self.rds_probe(executable="/system/bin/app_process32")
        self.assertEqual((self.root / "events").read_text(), "")
        self.assertTrue((self.root / "voyahtune_app_client.com.pateo.rdsapp.attempt").exists())

    def test_rds_rapid_restart_circuit_breaker(self):
        for generation in range(1, 4):
            (self.root / "generation.109").write_text(f"{generation}\n")
            self.rds_probe()
        self.assertEqual((self.root / "events").read_text().count("start app_client.js 109"), 2)
        self.assertEqual((self.root / "voyahtune_app_client.com.pateo.rdsapp.blocked").read_text().strip(), "test-boot")

    def start(self):
        log = open(self.root / f"stderr.{len(self.processes)}", "w")
        proc = subprocess.Popen(["sh", str(self.loader)], stdout=log, stderr=log,
                                start_new_session=True,env=self.env)
        log.close()
        self.processes.append(proc)
        return proc

    def tearDown(self):
        # Only the process groups created by this fixture; no device or unrelated host process.
        for proc in self.processes:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            proc.wait(timeout=5)
        # A just-killed grandchild can finish its EXIT cleanup after the direct shell exited.
        for attempt in range(5):
            try:
                self.temp.cleanup()
                break
            except OSError as error:
                if error.errno != 66 or attempt == 4:
                    raise
                time.sleep(0.05)

    def until(self, predicate, message, seconds=12):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.03)
        logs = (self.root / "log").read_text() if (self.root / "log").exists() else ""
        self.fail(message + "\n" + logs + "\n" + self.events())

    def events(self):
        return (self.root / "events").read_text()

    def state(self, lane):
        try:
            return (self.root / f"voyahtune_worker.{lane}.state").read_text().split("|")[1]
        except (FileNotFoundError, IndexError):
            return None

    def owner(self, lane):
        try:
            return int(os.readlink(self.root / f"voyahtune_worker.{lane}.lock").split(":")[0])
        except FileNotFoundError:
            return None

    def release(self, lane):
        (self.root / f"release_{lane}").touch()

    def probe(self, code):
        return subprocess.check_output(["sh", str(self.loader), "--probe", code],
                                       text=True, timeout=5,env=self.env).strip()

    def test_acc_has_priority_until_ready_but_not_until_wrapper_exit(self):
        (self.root / "hold_acc_ready").touch()
        (self.root / "block_acc").touch()
        self.start()
        self.until(lambda: "start voyahtune_acc_restore.js" in self.events(), "ACC not started")
        time.sleep(0.2)
        self.assertEqual(["start voyahtune_acc_restore.js 107"], self.events().splitlines())
        (self.root / "hold_acc_ready").unlink()
        self.until(lambda: "start vd_bypass.js" in self.events(), "ACC ready did not release workers")
        self.assertEqual("active", self.state("acc"))
        self.assertNotIn("end voyahtune_acc_restore.js", self.events())

    def test_late_canbus_still_attaches_first(self):
        (self.root / "missing_acc").touch()
        self.start()
        self.until(lambda: self.state("acc") == "waiting", "No ACC discovery")
        self.assertEqual("", self.events())
        (self.root / "missing_acc").unlink()
        self.until(lambda: "start vd_bypass.js" in self.events(), "Late CanBus blocked workers")
        self.assertEqual("start voyahtune_acc_restore.js 107", self.events().splitlines()[0])

    def test_absent_canbus_releases_priority_at_deadline_and_keeps_discovering(self):
        (self.root / "missing_acc").touch()
        self.start()
        self.until(lambda: self.state("acc") == "waiting", "No ACC discovery")
        self.assertEqual("", self.events())
        (self.root / "proc/uptime").write_text("146.00 0\n")
        self.until(lambda: "start vd_bypass.js" in self.events(), "Priority deadline blocked workers")
        (self.root / "missing_acc").unlink()
        self.until(lambda: self.state("acc") == "active", "CanBus discovery stopped after deadline")
        self.assertNotIn("end vd_bypass.js", self.events())

    def test_failed_acc_retries_after_backoff_in_same_process(self):
        (self.root / "missing_acc_ready").touch()
        self.start()
        self.until(lambda: "start vd_bypass.js" in self.events(), "Failed ACC blocked workers")
        self.assertEqual("failed", self.state("acc"))
        self.assertFalse((self.root / "voyahtune_acc_restore.pid").exists())
        self.assertEqual(1, self.events().count("start voyahtune_acc_restore.js"))
        (self.root / "missing_acc_ready").unlink()
        time.sleep(0.2)
        self.assertEqual(1, self.events().count("start voyahtune_acc_restore.js"))
        (self.root / "proc/uptime").write_text("102.00 0\n")
        self.until(lambda: self.state("acc") == "active", "Same-process retry did not recover hook")
        self.assertEqual(2, self.events().count("start voyahtune_acc_restore.js"))

    def test_expired_acc_pulse_reinstalls_without_process_restart(self):
        self.start()
        self.until(lambda: self.state("acc") == "active", "ACC not ready")
        self.until(lambda: (self.root / "finished_attempts").exists() and "voyahtune_acc_restore.attempt" in (self.root / "finished_attempts").read_text(), "Attempt not finished")
        (self.root / "proc/uptime").write_text("116.00 0\n")
        self.until(lambda: self.events().count("start voyahtune_acc_restore.js") == 2, "Expired pulse not repaired")

    def test_rapid_canbus_restarts_stop_injection(self):
        self.start()
        self.until(lambda: self.state("acc") == "active", "ACC not ready")
        for generation in [2, 3]:
            (self.root / "generation.107").write_text(str(generation) + "\n")
            self.until(lambda: self.events().count("start voyahtune_acc_restore.js") == generation, "Restart not injected")
            self.until(lambda: self.state("acc") == "active", "Restart not ready")
        (self.root / "generation.107").write_text("4\n")
        self.until(lambda: self.state("acc") == "failed", "Rapid restarts not blocked")
        self.assertEqual(3, self.events().count("start voyahtune_acc_restore.js"))

    def test_fresh_acc_pulse_keeps_existing_agent(self):
        self.start()
        self.until(lambda: self.state("acc") == "active", "ACC not ready")
        (self.root / "runtime/drive_hooks/voyahtune_acc_restore.health").write_text("107|119|v2\n")
        (self.root / "proc/uptime").write_text("120.00 0\n")
        time.sleep(0.3)
        self.assertEqual(1, self.events().count("start voyahtune_acc_restore.js"))
        self.assertEqual("active", self.state("acc"))

    def test_all_core_hooks_start_while_vd_status_and_apps_are_blocked(self):
        self.start()
        expected = ("voyahtune_acc_restore.js", "steeringwheelkeys.js", "multidisplay.js", "launcherdock.js",
                    "vd_bypass.js", "apollo_tech.js", "voyahtune_drive_reset.js", "keyboard_lock_en.js")
        self.until(lambda: all(f"start {name}" in self.events() for name in expected),
                   "A blocked lane prevented another core injection")
        self.until(lambda: self.state("steering") == "active", "Missing early steering readiness")
        self.assertNotIn("end vd_bypass.js", self.events())
        self.assertNotIn("end steeringwheelkeys.js", self.events())
        self.assertEqual("injecting", self.state("vd"))
        self.release("status")
        self.until(lambda: (self.root / "voyahtune-hook-status.v1").exists(), "No diagnostics")
        self.until(lambda: "steering-wheel=active:101" in
                   (self.root / "voyahtune-hook-status.v1").read_text(), "Stale diagnostics")
        status = (self.root / "voyahtune-hook-status.v1").read_text()
        self.assertIn("steering-wheel=active:101", status)
        self.assertIn("vd-bypass=injecting:104", status)
        self.assertIn(f"pid={self.processes[0].pid};", status)

    def test_late_keymanager_is_discovered_while_other_lanes_are_blocked(self):
        (self.root / "missing_steering").touch()
        self.start()
        self.until(lambda: "start vd_bypass.js" in self.events(), "VD not started")
        self.assertNotIn("start steeringwheelkeys.js", self.events())
        (self.root / "missing_steering").unlink()
        self.until(lambda: self.state("steering") == "active", "Late keymanager was blocked")
        self.assertNotIn("end vd_bypass.js", self.events())

    def test_drive_reset_is_independent_of_apollo_and_rearms_for_new_process(self):
        (self.root / "disable_apollo").touch()
        self.start()
        marker = self.root / "voyahtune_drive_reset.pid"
        self.until(lambda: marker.exists(), "Drive reset hook did not start with Apollo disabled")
        self.assertNotIn("start apollo_tech.js", self.events())
        self.assertEqual(1, self.events().count("start voyahtune_drive_reset.js"))
        (self.root / "generation.105").write_text("2\n")
        self.until(lambda: marker.read_text().strip().endswith(":2"), "New VehicleSettings missed hook")
        self.assertEqual(2, self.events().count("start voyahtune_drive_reset.js"))

    def test_vehicle_hook_has_independent_lifecycle_and_health(self):
        (self.root / "enable_vehicle").touch()
        (self.root / "disable_apollo").touch()
        self.start()
        vehicle = self.root / "runtime/drive_hooks/vehicle.pid"
        settings = self.root / "voyahtune_drive_reset.pid"
        self.until(lambda: vehicle.exists() and settings.exists(), "Both drive hooks must start")
        self.assertEqual(1, self.events().count("start voyahtune_drive_reset.js 108"))
        self.assertEqual(1, self.events().count("start voyahtune_drive_reset.js 105"))
        health = self.root / "runtime/drive_hooks"
        (health / "vehicle.health").write_text("108|120|v2\n")
        (health / "voyahtune_drive_reset.health").write_text("105|120|v2\n")
        (self.root / "proc/uptime").write_text("120.00 0\n")
        time.sleep(0.5)
        self.assertEqual(2, self.events().count("start voyahtune_drive_reset.js"))
        (self.root / "generation.108").write_text("2\n")
        self.until(lambda: vehicle.read_text().strip().endswith(":2"), "New VehicleAir missed hook")
        self.assertTrue(settings.read_text().strip().endswith(":1"))
        self.assertEqual(2, self.events().count("start voyahtune_drive_reset.js 108"))
        self.assertEqual(1, self.events().count("start voyahtune_drive_reset.js 105"))

    def test_failed_drive_reset_attach_does_not_loop_or_block_apollo(self):
        (self.root / "missing_drive_ready").touch()
        self.start()
        self.until(lambda: self.state("apollo") == "active", "Drive hook failure blocked Apollo")
        self.assertFalse((self.root / "voyahtune_drive_reset.pid").exists())
        self.assertEqual(1, self.events().count("start voyahtune_drive_reset.js"))
        self.assertLess(self.events().index("end voyahtune_drive_reset.js"),
                        self.events().index("start apollo_tech.js"))

    def test_dead_worker_waits_for_orphan_injector_and_only_new_identity_retries(self):
        self.start()
        self.until(lambda: "start vd_bypass.js" in self.events(), "VD not started")
        old = self.owner("vd")
        os.kill(old, signal.SIGKILL)
        # The orphan injector still holds its gate. Even a replacement lane must not attach again.
        time.sleep(0.4)
        self.assertEqual(1, self.events().count("start vd_bypass.js"))
        self.assertEqual(old, self.owner("vd"))
        self.release("vd")
        self.until(lambda: self.owner("vd") not in (None, old) and self.state("vd") == "active",
                   "Replacement failed to recover confirmed agent readiness")
        self.assertEqual(1, self.events().count("start vd_bypass.js"))
        (self.root / "generation.104").write_text("2\n")
        self.until(lambda: self.events().count("start vd_bypass.js") == 2,
                   "New target identity was not injected")
        self.until(lambda: self.state("vd") == "active", "New target readiness was not confirmed")
        self.assertEqual(2, self.events().count("start vd_bypass.js"))

    def test_supervisor_restart_keeps_active_hooks_without_reinjecting(self):
        self.release("vd")
        self.release("steering")
        self.release("status")
        self.release("apps")
        first = self.start()
        lanes = ("acc", "steering", "multidisplay", "launcher", "vd", "apollo", "keyboard")
        self.until(lambda: all(self.state(lane) == "active" for lane in lanes), "Initial hooks failed")
        owners = {lane: self.owner(lane) for lane in lanes}
        first.kill()
        first.wait(timeout=5)
        second = self.start()
        self.until(lambda: all(self.owner(lane) not in (None, owners[lane]) for lane in lanes),
                   "Workers did not transfer to the replacement supervisor")
        self.until(lambda: all(self.state(lane) == "active" for lane in lanes), "Lost active state")
        for script in ("voyahtune_acc_restore.js", "steeringwheelkeys.js", "multidisplay.js", "launcherdock.js", "vd_bypass.js",
                       "apollo_tech.js", "voyahtune_drive_reset.js", "keyboard_lock_en.js"):
            self.assertEqual(1, self.events().count(f"start {script}"), script)
        self.until(lambda: f"pid={second.pid};" in (self.root / "voyahtune-hook-status.v1").read_text(),
                   "Status still identifies the old supervisor")

    def test_exit_zero_without_steering_ready_is_failure(self):
        (self.root / "missing_ready").touch()
        self.release("steering")
        self.start()
        self.until(lambda: self.state("steering") == "failed", "Exit 0 falsely became ready")
        self.assertFalse((self.root / "voyahtune_swk_km.pid").exists())
        self.assertEqual(1, self.events().count("start steeringwheelkeys.js"))

    def test_real_worker_token_handles_pid_reuse_and_spaces_in_process_name(self):
        # Exercise the production /proc parser too; integration uses a ps shim on macOS.
        start = SOURCE.index("worker_token() {")
        end = SOURCE.index("\nworker_token_is_live()", start)
        token_function = SOURCE[start:end].replace("/proc/", str(self.root / "proc") + "/")
        proc = self.root / "proc/111"
        proc.mkdir()
        boot = self.root / "proc/sys/kernel/random/boot_id"
        boot.parent.mkdir(parents=True)
        boot.write_text("boot-a\n")
        stat_prefix = "111 (shell with ) spaces) S " + " ".join(["0"] * 18) + " "
        (proc / "stat").write_text(stat_prefix + "700 0 0\n")
        code = token_function + "\nkill() { return 0; }\nworker_token 111"
        self.assertEqual("111:700:boot-a", self.probe(code))
        (proc / "stat").write_text(stat_prefix + "800 0 0\n")
        self.assertEqual("111:800:boot-a", self.probe(code))
        boot.write_text("boot-b\n")
        self.assertEqual("111:800:boot-b", self.probe(code))
        self.assertEqual("stale", self.probe(token_function +
                         "\nkill() { return 0; }\n"
                         "worker_token_is_live 111:700:boot-a || echo stale"))

    def test_snapshot_rejects_reused_target_pid(self):
        self.start()
        self.until(lambda: self.state("launcher") == "active", "Launcher did not become ready")
        worker = self.owner("launcher")
        os.kill(worker, signal.SIGSTOP)
        try:
            (self.root / "generation.103").write_text("2\n")
            result = self.probe('read_worker_state launcher com.qinggan.app.launcher; '
                                'echo "$RS_STATE:$RS_PID"')
            self.assertEqual("waiting:0", result)
        finally:
            os.kill(worker, signal.SIGCONT)

    def test_vd_exit_zero_without_ready_is_failed_and_latched(self):
        (self.root / "missing_vd_ready").touch()
        self.release("vd")
        self.start()
        self.until(lambda: self.state("vd") == "failed", "exit 0 without readiness was accepted")
        self.assertFalse((self.root / "voyahtune_vd.pid").exists())
        time.sleep(0.2)
        self.assertEqual(self.events().count("start vd_bypass.js"), 1)

    def test_vd_status_requires_current_generation_and_agent_version(self):
        (self.root / "missing_vd_ready").touch()
        self.release("vd")
        self.start()
        self.until(lambda: self.state("vd") == "failed", "VD attempt did not finish")
        status = self.root / "runtime/vd_hooks/status.v1"
        for value in ["v2:test:104:0|3.22.0-v1|active|geometry",
                      "v2:test:104:1|old|active|geometry"]:
            status.write_text(value)
            time.sleep(0.15)
            self.assertEqual(self.state("vd"), "failed")
        status.write_text("v2:test:104:1|3.22.0-v1|active|geometry")
        self.until(lambda: self.state("vd") == "active", "late readiness was lost")
        status.write_text("v2:test:104:1|3.22.0-v1|failed|partial.attach")
        self.until(lambda: self.state("vd") == "failed", "later partial failure was hidden")
        self.assertEqual(self.events().count("start vd_bypass.js"), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
