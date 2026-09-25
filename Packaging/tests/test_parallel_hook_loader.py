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
        for pid in range(101, 107):
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
    *) gate=none ;;
esac
while [ "$gate" != none ] && [ ! -f "$root/release_$gate" ]; do sleep 0.05; done
printf 'end %s %s\n' "$script" "$pid" >> "$root/events"
""".replace("ROOT_PLACEHOLDER", shlex.quote(str(self.root))))
        self.injector.chmod(0o755)
        overrides = r'''
# Host-only platform shims. Keep all worker and injection functions from the loader intact.
FIXTURE=ROOT_PLACEHOLDER
FI="$FIXTURE/frida-inject"
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
        frida-inject)
            for f in "$FIXTURE"/proc/*/cmdline; do
                [ -f "$f" ] || continue
                p=${f%/cmdline}; p=${p##*/}
                kill -0 "$p" 2>/dev/null && echo "$p"
            done
            ;;
    esac
}
logi() { printf '%s\n' "$*" >> "$FIXTURE/log"; }
loge() { logi "$*"; }
log() { :; }
timeout() { shift 3; "$@"; }
settings() { echo en; }
apollo_runtime_flag_enabled() { return 0; }
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
        source = SOURCE.replace("/data/local/tmp", str(self.root))
        source = source.replace("/proc/", str(self.root / "proc") + "/")
        entry = 'if [ "${1:-}" = --worker ]; then'
        source = source.replace(entry, overrides + "\n" + entry)
        self.loader = self.root / "load.bin"
        self.loader.write_text(source)

    def start(self):
        log = open(self.root / f"stderr.{len(self.processes)}", "w")
        proc = subprocess.Popen(["sh", str(self.loader)], stdout=log, stderr=log,
                                start_new_session=True)
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
        self.temp.cleanup()

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
                                       text=True, timeout=5).strip()

    def test_all_core_hooks_start_while_vd_status_and_apps_are_blocked(self):
        self.start()
        expected = ("steeringwheelkeys.js", "multidisplay.js", "launcherdock.js",
                    "vd_bypass.js", "apollo_tech.js", "keyboard_lock_en.js")
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
        self.until(lambda: self.owner("vd") not in (None, old) and self.state("vd") == "failed",
                   "Replacement failed to preserve the one-shot reservation")
        self.assertEqual(1, self.events().count("start vd_bypass.js"))
        (self.root / "generation.104").write_text("2\n")
        self.until(lambda: self.state("vd") == "active", "New target identity was not injected")
        self.assertEqual(2, self.events().count("start vd_bypass.js"))

    def test_supervisor_restart_keeps_active_hooks_without_reinjecting(self):
        self.release("vd")
        self.release("steering")
        self.release("status")
        self.release("apps")
        first = self.start()
        lanes = ("steering", "multidisplay", "launcher", "vd", "apollo", "keyboard")
        self.until(lambda: all(self.state(lane) == "active" for lane in lanes), "Initial hooks failed")
        owners = {lane: self.owner(lane) for lane in lanes}
        first.kill()
        first.wait(timeout=5)
        second = self.start()
        self.until(lambda: all(self.owner(lane) not in (None, owners[lane]) for lane in lanes),
                   "Workers did not transfer to the replacement supervisor")
        self.until(lambda: all(self.state(lane) == "active" for lane in lanes), "Lost active state")
        for script in ("steeringwheelkeys.js", "multidisplay.js", "launcherdock.js", "vd_bypass.js",
                       "apollo_tech.js", "keyboard_lock_en.js"):
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
