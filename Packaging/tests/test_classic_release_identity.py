#!/usr/bin/env python3
"""Run the classic packager's actual verifier against isolated APK/runtime fixtures."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERIFIER = (ROOT / 'make_release.sh').read_text().split("<<'VERIFY_CLASSIC'\n", 1)[1].split('\nVERIFY_CLASSIC', 1)[0]
ALIASES = {'whitelist.xml': 'privapp-permissions-ru.big.town.anative.xml',
           'frida-inject': 'frida-inject-16.2.1-android-arm64'}


class ClassicReleaseIdentityTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix='classic-identity-')
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.output = self.root / 'output'
        self.source = self.root / 'profile'
        self.output.mkdir()
        (self.source / 'inject').mkdir(parents=True)
        for name in ('new-hook.js', 'hook-settings.json'):
            (self.source / 'inject' / name).write_text('fixture')

    def fixture(self, profile):
        names = {'whitelist.xml', 'frida-inject', 'voyahtune.load.rc', 'voyahtune.load.sh',
                 'new-hook.js', 'hook-settings.json'}
        names |= {'loaderFrida', 'injects.json', 'runyn.apk'} if profile == 'pi' else {'load.bin'}
        hashes = {}
        for name in names:
            content = f'{profile} runtime bytes: {name}'.encode()
            (self.output / ALIASES.get(name, name)).write_bytes(content)
            hashes[name] = hashlib.sha256(content).hexdigest()
        return {'infrastructure': profile, 'releaseVersion': '3.22.0-' + profile,
                'runtimeHashes': hashes}

    def verify(self, profile, metadata):
        for apk in ('native.apk', 'restore_mode.apk'):
            with zipfile.ZipFile(self.output / apk, 'w') as archive:
                archive.writestr('assets/voyahtune-build.json', json.dumps(metadata))
        return subprocess.run([sys.executable, '-', str(self.output), '3.22.0-' + profile,
                               profile, str(self.source)], input=VERIFIER, text=True,
                              capture_output=True, timeout=10)

    def test_matching_od_runtime_passes(self):
        result = self.verify('od', self.fixture('od'))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_matching_pi_runtime_including_runyn_passes(self):
        result = self.verify('pi', self.fixture('pi'))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_pi_missing_runyn_hash_is_rejected_even_when_apk_exists(self):
        metadata = self.fixture('pi')
        del metadata['runtimeHashes']['runyn.apk']
        result = self.verify('pi', metadata)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing=['runyn.apk']", result.stderr)

    def test_pi_stale_runyn_hash_is_rejected(self):
        metadata = self.fixture('pi')
        (self.output / 'runyn.apk').write_bytes(b'replaced RunYN')
        result = self.verify('pi', metadata)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('stale runtime runyn.apk', result.stderr)

    def test_new_profile_hook_must_be_present_in_signed_metadata(self):
        metadata = self.fixture('od')
        del metadata['runtimeHashes']['new-hook.js']
        result = self.verify('od', metadata)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing=['new-hook.js']", result.stderr)

    def test_runtime_key_from_other_profile_is_rejected(self):
        metadata = self.fixture('pi')
        metadata['runtimeHashes']['load.bin'] = '0' * 64
        result = self.verify('pi', metadata)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unexpected=['load.bin']", result.stderr)


if __name__ == '__main__':
    unittest.main()
