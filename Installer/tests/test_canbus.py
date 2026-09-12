#!/usr/bin/env python3
"""CAN permission consent/removal through the real CLI, without APK builds or a car."""
import hashlib
import json
import shutil
import subprocess
import time
import unittest

import integration as fixture

PACKAGE = 'com.voyah.hl.service'


class CanbusTests(unittest.TestCase):
    def setUp(self):
        self.f = fixture.InstallerTests()
        self.f.setUp()
        self.addCleanup(self.f.tearDown)
        payload = self.f.bundle / 'payload'
        payload.unlink()
        payload.mkdir()
        artifacts = []
        # These tests exercise command sequencing, not APK integrity (covered separately).
        sources = {
            'dns.apk': fixture.ROOT / 'Packaging/vendor-overlay/framework-res__config_ethernet_interfaces_yandexdns.apk',
            'whitelist.xml': fixture.ROOT / 'Packaging/system/privapp-permissions-ru.big.town.anative.xml',
        }
        for name, variant in [('dns.apk', None), ('whitelist.xml', None), ('dns-helper.sh', None),
                              ('native.apk', 'light'), ('restore_mode.apk', 'light')]:
            target = payload / name
            if name in sources:
                shutil.copyfile(sources[name], target)
            else:
                target.write_text('test fixture\n')
            artifacts.append(dict(name=name, path=name, variant=variant,
                                  size=target.stat().st_size,
                                  sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
        (payload / 'manifest.json').write_text(json.dumps(dict(
            schema=1, product='VoyahTune', releaseVersion='0.0.0', buildRevision='fixture', artifacts=artifacts)))
        self.directory = self.f.device / 'system/priv-app/VoyahHlCTRL'
        self.directory.mkdir()
        (self.directory / 'service.apk').write_bytes(b'original system APK')
        self.cache = self.f.device / 'data/system/package_cache'
        self.cache.mkdir(parents=True)
        (self.cache / 'stale').write_text('registered permission owner')
        self.f.state['canbusOwner'] = PACKAGE
        self.f.state['packages'][PACKAGE] = '/system/priv-app/VoyahHlCTRL/service.apk'
        self.f.write_state()

    def args(self, *extra):
        return [str(fixture.CLI), '--bundle', str(self.f.bundle), 'apply', '--device', 'CAR-001',
                '--action', 'light', '--token', 'fixture', '--yes', '--logs', str(self.f.base / 'logs'), *extra]

    def run_cli(self, *extra):
        return subprocess.run(self.args(*extra), env=self.f.env, text=True, capture_output=True, timeout=60)

    def interactive(self, response):
        process = subprocess.Popen(self.args('--interactive'), env=self.f.env,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True, bufsize=1)
        self.addCleanup(lambda: process.poll() is None and process.kill())
        events = []
        # A thread reader avoids buffering hazards while enforcing a deadline on a stuck prompt.
        import queue
        import threading
        lines = queue.Queue()
        def read():
            for line in process.stdout:
                lines.put(line)
            lines.put(None)
        threading.Thread(target=read, daemon=True).start()
        deadline = time.monotonic() + 60
        while True:
            line = lines.get(timeout=max(0.1, deadline - time.monotonic()))
            if line is None:
                break
            event = json.loads(line)
            events.append(event)
            if event['type'] == 'canbus-conflict':
                self.assertTrue(self.directory.exists())
                calls = self.f.calls()
                self.assertFalse(any(c['args'][0] in ['remount', 'reboot'] for c in calls))
                self.assertFalse(any('pm uninstall' in (c['script'] or '') for c in calls))
                if response is None:
                    process.stdin.close()
                else:
                    process.stdin.write(json.dumps(response) + '\n')
                    process.stdin.flush()
        code = process.wait(timeout=5)
        process.stdout.close()
        process.stderr.close()
        if not process.stdin.closed:
            process.stdin.close()
        return code, events

    def test_yes_alone_requests_action_without_removal_or_error(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 3, result.stdout)
        events = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertIn('canbus-conflict', [e['type'] for e in events])
        self.assertIn('operation-paused', [e['type'] for e in events])
        self.assertNotIn('operation-failed', [e['type'] for e in events])
        self.assertNotIn('error', [e['type'] for e in events])
        self.assertTrue(self.directory.exists())
        self.assertTrue((self.cache / 'stale').exists())

    def test_interactive_accept_backs_up_removes_reboots_and_continues(self):
        code, events = self.interactive({'confirmRemoveVoyahHlService': True})
        self.assertEqual(code, 0, events[-5:])
        self.assertFalse(self.directory.exists())
        self.assertFalse((self.cache / 'stale').exists())
        backups = list((self.f.base / 'logs').rglob('VoyahHlCTRL/service.apk'))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), b'original system APK')
        self.assertEqual(self.f.read_state()['reboots'], 2)
        types = [e['type'] for e in events]
        self.assertLess(types.index('canbus-backup'), types.index('canbus-removal-reboot'))
        self.assertLess(types.index('canbus-conflict-resolved'), types.index('operation-completed'))
        calls = self.f.calls()
        uninstall = next(i for i,c in enumerate(calls) if 'pm uninstall --user 0 '+PACKAGE in (c['script'] or ''))
        remove = next(i for i,c in enumerate(calls) if 'rm -rf /system/priv-app/VoyahHlCTRL' in (c['script'] or ''))
        reboot = next(i for i,c in enumerate(calls) if c['args'] == ['reboot'])
        self.assertLess(uninstall, remove)
        self.assertLess(remove, reboot)
        self.assertTrue(any('dumpsys package permissions' in (c['script'] or '') for c in calls[reboot+1:]))

    def test_decline_cancel_and_eof_leave_app_intact(self):
        for response in [{'confirmRemoveVoyahHlService': False}, {'cancel': True}, None]:
            with self.subTest(response=response):
                code, events = self.interactive(response)
                self.assertEqual(code, 130, events[-5:])
                self.assertIn('operation-cancelled', [e['type'] for e in events])
                self.assertNotIn('operation-failed', [e['type'] for e in events])
                self.assertTrue(self.directory.exists())
                self.assertTrue((self.cache / 'stale').exists())

    def test_explicit_flag_and_already_uninstalled_user_package(self):
        self.f.state['packages'].pop(PACKAGE)
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 0, result.stdout[-4000:])
        self.assertFalse(self.directory.exists())

    def test_apply_plan_forwards_separate_removal_consent(self):
        plan_file = self.f.base / 'plan.json'
        plan_file.write_text(json.dumps(self.f.plan('light')))
        result = self.f.cli('apply-plan', str(plan_file), '--yes', '--remove-voyah-hl-service',
                            '--logs', str(self.f.base / 'logs'), okay=False)
        self.assertEqual(result.returncode, 0, result.stdout[-4000:])
        self.assertFalse(self.directory.exists())

    def test_existing_permission_without_owner_does_not_use_next_package(self):
        self.f.state['canbusOwner'] = None
        self.f.state['packages'].pop(PACKAGE)
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 1)
        self.assertIn('Владелец не определён', result.stdout)
        self.assertTrue(self.directory.exists())

    def test_neither_condition_matches_and_similar_package_is_ignored(self):
        self.f.state['packages'].pop(PACKAGE)
        self.f.state['packages'][PACKAGE + '.other'] = '/data/app/other/base.apk'
        for owner in [None, 'ru.big.town.anative']:
            with self.subTest(owner=owner):
                if owner is None:
                    self.f.state.pop('canbusOwner')
                else:
                    self.f.read_state()
                    self.f.state['canbusOwner'] = owner
                self.f.write_state()
                result = self.run_cli('--remove-voyah-hl-service')
                self.assertEqual(result.returncode, 0, result.stdout[-4000:])
                self.assertNotIn('canbus-removal-started', result.stdout)
                self.assertTrue(self.directory.exists())

    def test_installed_package_prompts_with_absent_native_or_other_owner(self):
        for owner in [None, 'ru.big.town.anative', 'com.example.other']:
            with self.subTest(owner=owner):
                if owner is None:
                    self.f.state.pop('canbusOwner', None)
                else:
                    self.f.state['canbusOwner'] = owner
                self.f.write_state()
                result = self.run_cli()
                self.assertEqual(result.returncode, 3, result.stdout[-4000:])
                self.assertIn('canbus-conflict', result.stdout)
                self.assertNotIn('operation-failed', result.stdout)
                self.assertTrue(self.directory.exists())

    def test_package_only_consent_removes_and_rechecks_after_reboot(self):
        self.f.state.pop('canbusOwner')
        self.f.state['failUninstall'] = PACKAGE
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 0, result.stdout[-4000:])
        self.assertFalse(self.directory.exists())
        self.assertNotIn(PACKAGE, self.f.read_state()['packages'])
        calls = self.f.calls()
        reboot = next(i for i, c in enumerate(calls) if c['args'] == ['reboot'])
        self.assertTrue(any('pm list packages --user 0' in (c['script'] or '') for c in calls[reboot+1:]))

    def test_remaining_package_without_permission_stops_after_reboot(self):
        self.f.state.pop('canbusOwner')
        self.f.state['failUninstall'] = PACKAGE
        self.f.state['retainCanbusPackage'] = True
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 1, result.stdout[-4000:])
        self.assertIn('осталось установленным', result.stdout)
        self.assertEqual(self.f.read_state()['reboots'], 1)
        self.assertFalse((self.f.device / 'system/priv-app/Native/Native.apk').exists())

    def test_failed_package_query_does_not_assume_absence(self):
        self.f.state.pop('canbusOwner')
        self.f.state['failShell'] = 'pm list packages --user 0'
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 1, result.stdout[-4000:])
        self.assertTrue(self.directory.exists())

    def test_failed_system_deletion_does_not_continue_installation(self):
        self.f.state['failShell'] = 'rm -rf /system/priv-app/VoyahHlCTRL'
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 1)
        self.assertTrue(self.directory.exists())
        self.assertFalse((self.f.device / 'system/priv-app/Native/Native.apk').exists())

    def test_user_uninstall_failure_still_removes_system_owner(self):
        self.f.state['failUninstall'] = PACKAGE
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 0, result.stdout[-4000:])
        self.assertIn('DELETE_FAILED_INTERNAL_ERROR', result.stdout)
        self.assertFalse(self.directory.exists())
        self.assertNotIn(PACKAGE, self.f.read_state()['packages'])

    def test_backup_failure_keeps_package_and_system_directory(self):
        self.f.state['failPull'] = True
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertTrue(self.directory.exists())
        self.assertIn(PACKAGE, self.f.read_state()['packages'])
        self.assertNotIn('pm uninstall', '\n'.join(c['script'] or '' for c in self.f.calls()))

    def test_stale_owner_after_reboot_does_not_loop_or_install_native(self):
        self.f.state['retainCanbusOwner'] = True
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertEqual(self.f.read_state()['reboots'], 1)
        self.assertIn('не освободилось', result.stdout)
        self.assertFalse((self.f.device / 'system/priv-app/Native/Native.apk').exists())

    def test_other_owner_is_never_removed_even_with_flag(self):
        self.f.state['canbusOwner'] = 'com.example.other'
        self.f.state['packages'].pop(PACKAGE)
        self.f.write_state()
        result = self.run_cli('--remove-voyah-hl-service')
        self.assertEqual(result.returncode, 1)
        self.assertNotIn('canbus-conflict', result.stdout)
        self.assertTrue(self.directory.exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
