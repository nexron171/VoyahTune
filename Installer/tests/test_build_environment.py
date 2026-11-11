#!/usr/bin/env python3
"""Exercise desktop environment checks without starting a VM or building artifacts."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
WINDOWS_TARGETS = [
    "x86_64-pc-windows-msvc",
    "i686-pc-windows-msvc",
    "aarch64-pc-windows-msvc",
]

TOOL_STUB = r"""
import json
import os
import sys
import subprocess
from pathlib import Path

root = Path(os.environ["VTI_TEST_ROOT"])
state_path = root / "state.json"
state = json.loads(state_path.read_text())
tool = Path(sys.argv[0]).name
arguments = sys.argv[1:]
with (root / "commands.jsonl").open("a") as commands:
    commands.write(json.dumps([tool, *arguments]) + "\n")

def finish(code=0):
    state_path.write_text(json.dumps(state))
    sys.exit(code)

if tool == "uname":
    print("Darwin" if arguments == ["-s"] else state.get("architecture", "arm64"))
    finish()

if tool == "colima":
    if not Path(os.environ["COLIMA_HOME"]).is_dir():
        print("COLIMA_HOME is missing; Colima would use the default directory", file=sys.stderr)
        finish(1)
    if arguments[0] == "start":
        state["docker_running"] = not state.get("docker_unavailable_after_start", False)
    elif arguments[0] == "stop":
        state["docker_running"] = False
    else:
        raise AssertionError(arguments)
    finish()

if tool == "docker":
    expected_socket = f"unix://{root}/Releases/cache/colima/v/docker.sock"
    assert arguments[:2] == ["--host", expected_socket], arguments
    arguments = arguments[2:]
    if not state["docker_running"]:
        print("Cannot connect to the Docker daemon", file=sys.stderr)
        finish(1)
    if arguments == ["info"]:
        print("Docker ready")
        finish()
    if arguments[0] == "container":
        arguments = arguments[1:]
    if arguments[0] == "inspect":
        if not state["container_present"]:
            print("Error: No such container: vti-windows", file=sys.stderr)
            finish(1)
        if "-f" in arguments:
            print(str(state["container_running"]).lower())
        else:
            print(json.dumps([{
                "Name": "/vti-windows",
                "Mounts": [{
                    "Destination": "/work",
                    "Type": "bind",
                    "RW": True,
                    "Source": (str(root / "Releases/build/hosts/linux-amd64")
                               if arguments[-1] == "vti-linux-amd64" else state["mount_directory"]),
                }],
            }]))
        finish()
    if arguments[0] in ("start", "stop"):
        state["container_running"] = arguments[0] == "start"
        finish()
    if arguments[:3] == ["exec", "vti-linux-amd64", "sh"]:
        finish()
    if arguments[:5] == ["exec", "vti-windows", "rustup", "target", "add"]:
        finish()
    if arguments[:3] == ["exec", "vti-windows", "sh"]:
        print("/opt/cargo/bin/cargo-xwin\n/usr/bin/7z")
        finish()
    if arguments[:5] == ["exec", "vti-windows", "rustup", "target", "list"]:
        print("\n".join(state["targets"]))
        finish()

if tool == "xcrun" and arguments == ["--find", "clang"]:
    print("/usr/bin/clang")
    finish()

if tool == "rustup" and arguments[:2] == ["target", "list"]:
    print("aarch64-apple-darwin\nx86_64-apple-darwin")
    finish()

if state.get("snapshot_test") and tool == "rsync":
    finish(subprocess.run(["/usr/bin/rsync", *arguments]).returncode)

if state.get("snapshot_test") and tool == "npm":
    finish(73)

raise AssertionError(f"Unexpected tool execution: {tool} {arguments}")
"""


class BuildEnvironmentTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="voyahtune-build-environment-")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.script = self.root / "Installer/scripts/build-all-macos.sh"
        self.script.parent.mkdir(parents=True)
        shutil.copy2(REPOSITORY / "Installer/scripts/build-all-macos.sh", self.script)
        shutil.copy2(
            REPOSITORY / "Installer/rust-toolchain.toml",
            self.root / "Installer/rust-toolchain.toml",
        )
        self.colima_home = self.root / "Releases/cache/colima"
        self.state = {
            "docker_running": True,
            "container_present": True,
            "container_running": True,
            "mount_directory": str(self.root / "Releases/build/hosts/windows"),
            "targets": WINDOWS_TARGETS,
        }
        executables = self.root / "bin"
        executables.mkdir()
        stub = executables / "tool.py"
        stub.write_text(f"#!{sys.executable}\n" + textwrap.dedent(TOOL_STUB))
        stub.chmod(0o755)
        for tool in ("docker", "colima", "uname", "node", "npm", "rsync", "xcrun", "lipo", "rustup"):
            (executables / tool).symlink_to(stub)
        (executables / "python3").symlink_to(sys.executable)
        self.environment = {
            **os.environ,
            "PATH": f"{executables}:/usr/bin:/bin",
            "VTI_TEST_ROOT": str(self.root),
        }

    def prepare_profile(self):
        profile = self.colima_home / "v/colima.yaml"
        profile.parent.mkdir(parents=True)
        profile.write_text("runtime: docker\n")

    def run_check(self, *selection):
        state_path = self.root / "state.json"
        state_path.write_text(json.dumps(self.state))
        result = subprocess.run(
            ["/bin/bash", str(self.script), "--check", *(selection or ("--windows",))],
            cwd=self.root,
            env=self.environment,
            capture_output=True,
            text=True,
            timeout=15,
        )
        self.state = json.loads(state_path.read_text())
        self.commands = [
            json.loads(line)
            for line in (self.root / "commands.jsonl").read_text().splitlines()
        ]
        self.assertFalse((self.root / "Releases/build/.installer-all.lock").exists())
        return result

    def container_commands(self):
        return [command[3:] for command in self.commands if command[0] == "docker"]

    def colima_commands(self):
        return [command[1:] for command in self.commands if command[0] == "colima"]

    def test_build_snapshots_receive_fresh_versions_for_both_containers(self):
        self.state.update(snapshot_test=True, architecture="x86_64")
        (self.root / "Installer/desktop").mkdir()
        (self.root / "Packaging").mkdir()
        (self.root / "Installer/Cargo.toml").write_text(
            '[workspace.package]\nversion="1.11.0"\n'
        )
        for host in ("windows", "linux-amd64"):
            directory = self.root / "Releases/build/hosts" / host
            directory.mkdir(parents=True)
            (directory / "versions.json").write_text('{"installer":"0.0.1"}')
        for version in ("1.11.0", "1.11.1"):
            expected = {"installer": version, "voyahtune": "4.7.1"}
            (self.root / "versions.json").write_text(json.dumps(expected))
            (self.root / "state.json").write_text(json.dumps(self.state))
            result = subprocess.run(
                ["/bin/bash", str(self.script), "--windows", "--linux"],
                cwd=self.root, env=self.environment, capture_output=True, text=True, timeout=15,
            )
            # Stop at the first build command after copying real source snapshots.
            self.assertEqual(result.returncode, 73, result.stderr)
            for host in ("windows", "linux-amd64"):
                actual = self.root / "Releases/build/hosts" / host / "versions.json"
                self.assertEqual(json.loads(actual.read_text()), expected)

    def test_missing_profile_does_not_start_a_default_vm(self):
        self.state["docker_running"] = False
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.colima_home.is_dir())
        self.assertIn("Missing prepared Colima profile v", result.stderr)
        self.assertIn("Installer/BUILDING.md", result.stderr)
        self.assertEqual(self.colima_commands(), [])
        self.assertEqual(self.container_commands(), [["info"]])

    def test_running_environment_stays_running(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Environment checks passed", result.stdout)
        self.assertTrue(self.colima_home.is_dir())
        self.assertTrue(self.state["docker_running"])
        self.assertTrue(self.state["container_running"])
        self.assertEqual(self.colima_commands(), [])
        self.assertNotIn(["stop", "vti-windows"], self.container_commands())

    def test_stopped_environment_is_started_and_cleaned_up(self):
        self.prepare_profile()
        self.state["docker_running"] = False
        self.state["container_running"] = False
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            self.colima_commands(),
            [["start", "v", "--activate=false"], ["stop", "v"]],
        )
        self.assertIn(["start", "vti-windows"], self.container_commands())
        self.assertIn(["stop", "vti-windows"], self.container_commands())
        self.assertFalse(self.state["docker_running"])
        self.assertFalse(self.state["container_running"])

    def test_unavailable_docker_reports_connection_failure_before_inspect(self):
        self.prepare_profile()
        self.state["docker_running"] = False
        self.state["docker_unavailable_after_start"] = True
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Cannot connect to the Docker daemon", result.stderr)
        self.assertIn("Docker is unavailable", result.stderr)
        self.assertEqual(self.container_commands(), [["info"], ["info"]])
        self.assertEqual(self.colima_commands()[-1], ["stop", "v"])

    def test_missing_container_preserves_docker_error(self):
        self.state["container_present"] = False
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No such container: vti-windows", result.stderr)
        self.assertIn("Installer/BUILDING.md", result.stderr)
        log = next((self.root / "Releases/cache").glob("installer-all-*/vti-windows-inspect.log"))
        self.assertIn("No such container: vti-windows", log.read_text())
        self.assertTrue(self.state["docker_running"])

    def test_wrong_checkout_mount_is_rejected_before_starting_container(self):
        self.state["container_running"] = False
        self.state["mount_directory"] = str(self.root / "another-checkout")
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Wrong /work mount", result.stderr)
        self.assertNotIn(["start", "vti-windows"], self.container_commands())

    def test_missing_windows_target_is_reported_without_installing_it(self):
        self.state["targets"] = WINDOWS_TARGETS[:1]
        result = self.run_check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"Missing Windows Rust target {WINDOWS_TARGETS[1]}", result.stderr)

    def test_single_architecture_only_requires_its_target(self):
        self.state["targets"] = WINDOWS_TARGETS[:1]
        result = self.run_check("--windows-arch", "x64")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_macos_check_does_not_touch_colima(self):
        result = self.run_check("--mac")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.colima_commands(), [])
        self.assertEqual(self.container_commands(), [])
        self.assertFalse(self.colima_home.exists())


if __name__ == "__main__":
    unittest.main()
