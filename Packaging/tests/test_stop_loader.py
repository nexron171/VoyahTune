#!/usr/bin/env python3
"""Exercise loader shutdown against a fake /proc; never signal host processes."""
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'Packaging/od/installer/common/stop-loader-device.sh'


class StopLoaderTests(unittest.TestCase):
    def run_helper(self, argv=None, *, pkill_status=1, lock_pid='73142',
                   changing_identity=False, stubborn=False, lock_kind='file'):
        with tempfile.TemporaryDirectory(prefix='stop-loader-test-') as directory:
            base = Path(directory)
            proc = base / 'proc'
            locks = base / 'locks'
            proc.mkdir()
            locks.mkdir()
            pid = '73142'
            if argv is not None:
                target = proc / pid
                target.mkdir()
                # State is field 3; starttime is field 22 in Linux /proc/PID/stat.
                stat = pid + ' (loader name) S ' + '0 ' * 18 + '12345 0\n'
                (target / 'stat').write_text(stat)
                (target / 'cmdline').write_bytes(b'\0'.join(a.encode() for a in argv) + b'\0')
                lock = locks / 'voyahtune_load.v2.lock'
                if lock_kind == 'directory':
                    lock.mkdir()
                    (lock / 'pid').write_text(lock_pid)
                elif lock_kind == 'symlink':
                    lock.symlink_to(lock_pid)
                else:
                    lock.write_text(lock_pid)
            script = HELPER.read_text().replace('/proc/', str(proc) + '/')
            script = script.replace('/data/local/tmp/', str(locks) + '/')
            # Shell functions override every signalling command in the helper.
            prelude = f'''
pkill() {{ printf '%s\\n' "$*" >> {shlex.quote(str(base / 'pkill'))}; return {pkill_status}; }}
kill() {{ printf '%s\\n' "$*" >> {shlex.quote(str(base / 'kill'))};
    {'return 0' if stubborn else 'rm -rf ' + shlex.quote(str(proc)) + '/"$1"'};
}}
sleep() {{ :; }}
'''
            if changing_identity:
                stat_path = shlex.quote(str(proc / pid / 'stat'))
                seen_path = shlex.quote(str(base / 'stat-seen'))
                prelude += f'''
cat() {{
    if [ "$1" = {stat_path} ]; then
        if [ -f {seen_path} ]; then command cat "$1" | sed 's/12345/54321/'; return; fi
        touch {seen_path}
    fi
    command cat "$@"
}}
'''
            result = subprocess.run(['sh'], input=prelude + script, text=True,
                                    capture_output=True, timeout=5)
            calls = (base / 'kill').read_text().splitlines() if (base / 'kill').exists() else []
            self.assertEqual((base / 'pkill').read_text().splitlines(), ['-x loaderFrida'])
            return result.returncode, calls

    def test_profile_helpers_are_identical(self):
        self.assertEqual(HELPER.read_bytes(),
                         (ROOT / 'Packaging/pi/installer/common/stop-loader-device.sh').read_bytes())

    def test_only_supported_pkill_statuses_pass(self):
        for status in (0, 1, 2, 127):
            with self.subTest(status=status):
                actual, calls = self.run_helper(pkill_status=status)
                self.assertEqual(actual, 0 if status <= 1 else status)
                self.assertEqual(calls, [])

    def test_owned_supervisor_with_each_lock_format_stops(self):
        for lock_kind in ('file', 'directory', 'symlink'):
            with self.subTest(lock_kind=lock_kind):
                self.assertEqual(self.run_helper(['/system/bin/sh', '/data/local/bin/load.bin'],
                                                 lock_kind=lock_kind), (0, ['73142']))
        self.assertEqual(self.run_helper(['/data/local/bin/load.bin']), (0, ['73142']))

    def test_shell_command_substrings_and_extra_arguments_are_not_targets(self):
        for argv in (['sh', '-c', 'echo /data/local/bin/load.bin'],
                     ['/system/bin/sh', '/data/local/bin/load.bin', 'extra'],
                     ['/other/load.bin'], ['/data/local/bin/load.bin.old']):
            with self.subTest(argv=argv):
                self.assertEqual(self.run_helper(argv), (0, []))

    def test_malformed_lock_pid_is_ignored(self):
        self.assertEqual(self.run_helper(['/data/local/bin/load.bin'], lock_pid='73142; echo bad'), (0, []))

    def test_reused_pid_is_not_signalled(self):
        self.assertEqual(self.run_helper(['/data/local/bin/load.bin'], changing_identity=True), (0, []))

    def test_supervisor_that_remains_alive_fails_with_bounded_wait(self):
        self.assertEqual(self.run_helper(['/data/local/bin/load.bin'], stubborn=True), (1, ['73142']))


if __name__ == '__main__':
    unittest.main()
