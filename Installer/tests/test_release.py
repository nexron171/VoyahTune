#!/usr/bin/env python3
"""Release selection and replacement with tiny local build fixtures (no SDK/ADB)."""
import importlib.util
import json
import re
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release', REPO/'Installer/scripts/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root/'Installer').mkdir()
        (self.root/'Installer/rust-toolchain.toml').write_text('[toolchain]\nchannel="1.98.1"\n')
        (self.root/'Installer/Cargo.toml').write_text('[workspace.package]\nversion="1.0.0"\n')
        self.generation = 0
        self.selected = []
        self.fail_build = False

    def fake_run(self, command, **kwargs):
        args = list(map(str, command))
        if Path(args[0]).name == 'installer-build':
            if self.fail_build:
                raise subprocess.CalledProcessError(1, args)
            payload = Path(args[args.index('--output')+1]);payload.mkdir()
            version = args[args.index('--version')+1]
            (payload/'manifest.json').write_text(json.dumps({'schema':4,'requirements':{'minInstallerVersion':'1.0.0','requiredCapabilities':['files-v1']},'releaseVersion':version,'buildRevision':'fixture','infrastructure':args[args.index('--infrastructure')+1],'generation':self.generation}))
        elif Path(args[0]).name == 'build-all-macos.sh':
            dest = Path(args[args.index('--output')+1]);dest.mkdir()
            self.assertNotIn('--pi', args)
            self.assertNotIn('--od', args)
            names = [name for flag,name in [('--mac','macos'),('--windows','windows'),('--linux','linux')] if flag in args]
            self.selected = names
            entries = {}
            for name in names:
                filename = {'macos':'macos-universal.tar.gz','windows':'windows.exe','linux':'linux.run'}[name]
                artifact = dest/filename
                if name == 'macos':
                    app = dest/'VoyahTune Installer.app';app.mkdir()
                    (app/'fixture').write_text(str(self.generation))
                    with tarfile.open(artifact,'w:gz') as archive:archive.add(app,arcname=app.name)
                else:artifact.write_text(str(self.generation))
                entries[name] = {'file':filename,'sha256':release.sha(artifact)}
            record = dict(installerVersion='1.0.0',embeddedPayload='--payload' in args,platforms=entries,generation=self.generation)
            (dest/'build-info.json').write_text(json.dumps(record))

    def run_release(self, *flags):
        self.generation += 1
        with patch.object(release,'ROOT',self.root), patch.object(release,'run',self.fake_run), patch.object(release.platform,'system',return_value='Darwin'), patch('sys.argv',['release.py','4.5.6','--revision','fixture',*(['--od'] if '--pi' not in flags and '--od' not in flags else []),*flags]):
            release.main()

    def test_platform_selection_and_same_version_overwrite(self):
        for flags, expected in [([],['macos','windows','linux']),(['--mac'],['macos']),(['--windows'],['windows']),(['--linux'],['linux']),(['--mac','--linux'],['macos','linux'])]:
            with self.subTest(flags=flags):
                self.run_release(*flags)
                self.assertEqual(self.selected,expected)
                dest=self.root/'Releases/dist/VoyahTune-Installer-1.0.0'
                self.assertEqual(sorted(p.name for p in dest.glob('*.zip')),sorted('VoyahTune-Installer-1.0.0-'+('windows-x64' if name=='windows' else name)+'.zip' for name in expected))
                self.assertEqual(json.loads((dest/'release.json').read_text())['generation'],self.generation)

    def test_payload_only_skips_platform_packaging(self):
        self.run_release('--payload')
        self.assertEqual(self.selected,[])
        archive=self.root/'Releases/dist/payload_4.5.6-od.zip'
        entry=json.loads(archive.with_suffix('.json').read_text())
        self.assertEqual(entry['sha256'],release.sha(archive))
        self.assertEqual(set(entry), {'version','url','size','sha256'})
        self.assertEqual(entry['size'],archive.stat().st_size)
        import zipfile
        with zipfile.ZipFile(archive) as z:self.assertIn('manifest.json',z.namelist())
        self.assertFalse((archive.parent/'VoyahTune-Installer-1.0.0').exists())

    def test_failed_rebuild_preserves_previous_outputs(self):
        self.run_release('--mac')
        payload=self.root/'Releases/build/installer-payload-4.5.6-od/manifest.json'
        record=self.root/'Releases/dist/VoyahTune-Installer-1.0.0/release.json'
        previous=(payload.read_bytes(),record.read_bytes())
        self.fail_build=True
        with self.assertRaises(subprocess.CalledProcessError):self.run_release('--windows')
        self.assertEqual((payload.read_bytes(),record.read_bytes()),previous)

    def test_publication_failure_rolls_back_both_outputs(self):
        sources=[self.root/'new-payload',self.root/'new-packages']
        dests=[self.root/'payload',self.root/'packages']
        for path in sources+dests:path.mkdir();(path/'value').write_text(path.name)
        original=Path.rename
        def rename(path,target):
            if path==sources[1]:raise OSError('publication failed')
            return original(path,target)
        with patch.object(Path,'rename',rename),self.assertRaises(OSError):release.publish_outputs(zip(sources,dests))
        for path in sources+dests:self.assertEqual((path/'value').read_text(),path.name)

    def test_mac_flag_dispatches_without_installers_flag(self):
        r=subprocess.run(['sh',str(REPO/'make_release.sh'),'4.5.6','--mac','--help'],text=True,capture_output=True)
        self.assertEqual(r.returncode,0,r.stderr)
        self.assertIn('--windows',r.stdout)
        self.assertIn('release.py',r.stdout)

    def test_prebuild_checks_exist_and_match_classic_release(self):
        with patch.object(self, 'fake_run', wraps=self.fake_run) as fake:
            self.run_release('--mac')
            commands = [list(map(str, call.args[0])) for call in fake.call_args_list]
        checks = [c for c in commands if c[0] == 'python3']
        self.assertEqual(checks, [['python3', str(self.root/'Packaging/tests/test_infrastructure_profiles.py'), '--profile', 'od']])
        self.assertIn('test_infrastructure_profiles.py', (REPO/'make_release.sh').read_text())

    def test_profile_outputs_coexist(self):
        self.run_release('--od', '--mac')
        archive = self.root/'Releases/dist/payload_4.5.6-od.zip'
        previous = archive.read_bytes()
        self.run_release('--pi', '--mac', '--offline-bundle')
        self.assertEqual(archive.read_bytes(), previous)
        record = json.loads((self.root/'Releases/dist/VoyahTune-Installer-1.0.0/release.json').read_text())
        self.assertTrue(record['embeddedPayload'])
        self.assertTrue((self.root/'Releases/dist/payload_4.5.6-pi.zip').exists())

    def test_windows_architectures_do_not_overwrite_each_other(self):
        self.run_release('--od', '--windows')
        original = self.root/'Releases/dist/VoyahTune-Installer-1.0.0/VoyahTune-Installer-1.0.0-windows-x64.zip'
        before = original.read_bytes()
        self.run_release('--od', '--windows', '--windows-arch', 'x86')
        self.assertEqual(original.read_bytes(), before)
        self.assertTrue((self.root/'Releases/dist/VoyahTune-Installer-1.0.0-windows-x86/VoyahTune-Installer-1.0.0-windows-x86.zip').exists())

    def test_release_version_carries_selected_infrastructure(self):
        self.assertEqual(release.normalize_version('3.22.0', 'od'), '3.22.0-od')
        self.assertEqual(release.normalize_version('v3.22.0-pi', 'pi'), '3.22.0-pi')
        self.assertEqual(release.normalize_version('3.22.0-beta.1+test', 'pi'), '3.22.0-beta.1-pi+test')
        with self.assertRaises(ValueError): release.normalize_version('3.22.0-od', 'pi')

    def test_release_requires_exactly_one_profile(self):
        for flags in ([], ['--pi', '--od']):
            with self.subTest(flags=flags), patch('sys.argv', ['release.py', '4.5.6', '--payload', *flags]):
                with self.assertRaises(SystemExit) as raised:
                    release.main()
                self.assertEqual(raised.exception.code, 2)

if __name__=='__main__':unittest.main(verbosity=2)
