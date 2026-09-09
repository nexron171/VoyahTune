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
        self.generation = 0
        self.selected = []
        self.fail = False

    def fake_run(self, command, **kwargs):
        args = list(map(str, command))
        if Path(args[0]).name == 'installer-build':
            if self.fail:
                raise subprocess.CalledProcessError(1, args)
            payload = Path(args[args.index('--output')+1]);payload.mkdir()
            version = args[args.index('--version')+1]
            (payload/'manifest.json').write_text(json.dumps({'releaseVersion':version,'buildRevision':'fixture','generation':self.generation}))
        elif Path(args[0]).name == 'build-all-macos.sh':
            dest = Path(args[args.index('--output')+1]);dest.mkdir()
            payload = Path(args[args.index('--payload')+1])
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
            record = json.loads((payload/'manifest.json').read_text())
            record.update(platforms=entries,payloadSha256=release.sha(payload/'manifest.json'))
            (dest/'build-info.json').write_text(json.dumps(record))

    def run_release(self, *flags):
        self.generation += 1
        with patch.object(release,'ROOT',self.root), patch.object(release,'run',self.fake_run), patch.object(release.platform,'system',return_value='Darwin'), patch('sys.argv',['release.py','4.5.6','--revision','fixture',*flags]):
            release.main()

    def test_platform_selection_and_same_version_overwrite(self):
        for flags, expected in [([],['macos','windows','linux']),(['--mac'],['macos']),(['--windows'],['windows']),(['--linux'],['linux']),(['--mac','--linux'],['macos','linux'])]:
            with self.subTest(flags=flags):
                self.run_release(*flags)
                self.assertEqual(self.selected,expected)
                dest=self.root/'Releases/dist/VoyahTune-4.5.6-installers'
                self.assertEqual(sorted(p.name for p in dest.glob('*.zip')),sorted('VoyahTune-4.5.6-'+name+'.zip' for name in expected))
                self.assertEqual(json.loads((dest/'release.json').read_text())['generation'],self.generation)

    def test_failed_rebuild_preserves_previous_outputs(self):
        self.run_release('--mac')
        payload=self.root/'Releases/build/installer-payload-4.5.6/manifest.json'
        record=self.root/'Releases/dist/VoyahTune-4.5.6-installers/release.json'
        previous=(payload.read_bytes(),record.read_bytes())
        self.fail=True
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
            # Record the real orchestrator's calls while keeping SDK/build operations fake.
            self.run_release('--mac')
            commands = [list(map(str, call.args[0])) for call in fake.call_args_list]
        actual = [Path(c[1]).relative_to(self.root).as_posix()
                  for c in commands if c[0] in ('sh', 'bash')]
        classic = (REPO/'make_release.sh').read_text()
        expected = ['Packaging/tests/' + name for name in re.findall(
            r'sh "\$COMMON/tests/([^"]+)"', classic)]
        expected += ['Utils/android11-oem-stubs/tests/static-checks.sh']
        self.assertEqual(actual, expected)
        for script in actual:
            self.assertTrue((REPO/script).is_file(), script)

if __name__=='__main__':unittest.main(verbosity=2)
