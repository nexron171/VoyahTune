import base64
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('s3_publish', Path(__file__).resolve().parents[1] / 'scripts/upload-release-s3.py')
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)


class FakeS3:
    def __init__(self):
        self.objects = {}
        self.writes = []

    def head(self, key):
        return self.objects.get(key)

    def put(self, key, path, info):
        if key in self.objects:
            raise u.AwsError('An error occurred (PreconditionFailed)')
        self.writes.append(key)
        self.objects[key] = {'ContentLength': path.stat().st_size, 'Metadata': {'sha256': u.hashes(path)['sha256']}}


class S3PublishTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'release'
        self.directory.mkdir()
        self.version = '3.17.0-od'
        with zipfile.ZipFile(self.directory / 'payload_3.17.0-od.zip', 'w') as archive:
            archive.writestr('manifest.json', json.dumps({'product':'VoyahTune','releaseVersion':self.version,'infrastructure':'od'}))
        digest = u.hashes(self.directory / 'payload_3.17.0-od.zip')
        self.entry = {'version': self.version, 'url': u.release_url('voyahtune', self.version, 'payload_3.17.0-od.zip'), 'size': digest['size'], 'sha256': digest['sha256']}
        (self.directory / 'payload_3.17.0-od.json').write_text(json.dumps(self.entry))
        (self.directory / 'Installer.exe').write_bytes(b'locally verified installer')
        self.checksums()
        self.index = Path(self.temp.name) / 'index.json'
        self.index.write_text('{"releases":[]}')
        self.files, _ = u.prepare(self.directory, self.version, 'voyahtune')
        self.store = FakeS3()
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)

    def checksums(self):
        (self.directory / 'SHA256SUMS').write_text(''.join(f'{u.hashes(p)["sha256"]}  {p.name}\n' for p in sorted(self.directory.iterdir()) if p.name != 'SHA256SUMS'))

    def publish(self, check_only=False):
        return u.publish(self.directory, self.version, 'voyahtune', self.files, self.store, check_only)

    def test_selected_profile_is_checked_inside_payload_manifest(self):
        payload = self.directory / 'payload_3.17.0-od.zip'
        with zipfile.ZipFile(payload, 'w') as archive:
            archive.writestr('manifest.json', json.dumps({'product':'VoyahTune','releaseVersion':self.version,'infrastructure':'pi'}))
        digest = u.hashes(payload)
        (self.directory / 'payload_3.17.0-od.json').write_text(json.dumps({**self.entry, 'size':digest['size'], 'sha256':digest['sha256']}))
        self.checksums()
        with self.assertRaisesRegex(ValueError, 'manifest version/infrastructure'):
            u.prepare(self.directory, self.version, 'voyahtune')

    def test_pi_dry_run_uses_full_version_and_shared_catalog_without_network(self):
        directory = Path(self.temp.name) / 'pi-release'
        directory.mkdir()
        version = '3.17.0-pi'
        archive = directory / f'payload_{version}.zip'
        with zipfile.ZipFile(archive, 'w') as payload:
            payload.writestr('manifest.json', json.dumps({'product':'VoyahTune','releaseVersion':version,'infrastructure':'pi'}))
        digest = u.hashes(archive)
        entry = {'version':version,'url':u.release_url('voyahtune',version,archive.name),'size':digest['size'],'sha256':digest['sha256']}
        (directory / f'payload_{version}.json').write_text(json.dumps(entry))
        (directory / 'SHA256SUMS').write_text(''.join(f'{u.hashes(path)["sha256"]}  {path.name}\n' for path in sorted(directory.iterdir())))
        with patch.object(u, 'S3') as store, patch.object(u.catalog, 'verify_remote_head') as head:
            u.main([version, '--directory', str(directory), '--index', str(self.index), '--dry-run'])
            store.assert_not_called()
            head.assert_not_called()
        self.assertEqual(json.loads(self.index.read_text()), {'releases':[]})

    def test_upload_and_resume_are_idempotent_without_download(self):
        with patch.object(u.catalog, 'verify_remote_head') as head:
            self.publish()
            writes = self.store.writes.copy()
            self.publish()
            self.assertEqual(writes, self.store.writes)
            self.assertEqual(len(writes), len(self.files))
            self.assertEqual(head.call_count, 2 * len(self.files))

    def test_late_conflict_prevents_all_uploads(self):
        self.store.objects['v3.17.0-od/payload_3.17.0-od.zip'] = {'ContentLength': self.entry['size'], 'Metadata': {'sha256': 'f' * 64}}
        with self.assertRaisesRegex(ValueError, 'Remote conflict'):
            self.publish()
        self.assertEqual(self.store.writes, [])

    def test_read_only_mode_never_fills_missing_objects(self):
        with self.assertRaisesRegex(ValueError, 'Missing remote'):
            self.publish(True)
        self.assertEqual(self.store.writes, [])

    def test_missing_remote_metadata_does_not_allow_overwrite(self):
        self.store.objects['v3.17.0-od/payload_3.17.0-od.zip'] = {'ContentLength': self.entry['size']}
        with self.assertRaisesRegex(ValueError, 'Remote conflict'):
            self.publish()
        self.assertEqual(self.store.writes, [])

    def test_yandex_metadata_names_are_case_insensitive(self):
        remote = {'ContentLength': self.entry['size'], 'Metadata': {'Sha256': self.entry['sha256']}}
        self.assertTrue(u.matches(remote, self.entry))
        remote['Metadata']['sha256'] = 'b' * 64
        self.assertFalse(u.matches(remote, self.entry))

    def test_local_corruption_and_unlisted_files_are_rejected(self):
        payload = self.directory / 'payload_3.17.0-od.zip'
        payload.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            u.prepare(self.directory, self.version, 'voyahtune')
        (self.directory / 'credentials.txt').write_text('must not upload')
        with self.assertRaisesRegex(ValueError, 'list every release file'):
            u.prepare(self.directory, self.version, 'voyahtune')

    def test_symlinks_and_checksum_path_traversal_are_rejected(self):
        link = self.directory / 'link.txt'
        link.symlink_to(self.index)
        with self.assertRaisesRegex(ValueError, 'regular files'):
            u.prepare(self.directory, self.version, 'voyahtune')
        link.unlink()
        (self.directory / 'SHA256SUMS').write_text('a' * 64 + '  ../secret\n')
        with self.assertRaisesRegex(ValueError, 'SHA256SUMS must contain'):
            u.prepare(self.directory, self.version, 'voyahtune')

    def test_wrong_entry_url_and_payload_hash_are_rejected(self):
        path = self.directory / 'payload_3.17.0-od.json'
        for change, expected in [({'url': 'https://github.com/payload.zip'}, 'Set entry url'), ({'sha256': 'b' * 64}, 'does not match')]:
            path.write_text(json.dumps({**self.entry, **change}))
            self.checksums()
            with self.assertRaisesRegex(ValueError, expected):
                u.prepare(self.directory, self.version, 'voyahtune')

    def test_changed_input_is_not_uploaded_with_stale_hash(self):
        (self.directory / 'Installer.exe').write_bytes(b'changed during release')
        with self.assertRaisesRegex(ValueError, 'changed after validation'):
            self.publish()
        self.assertEqual(self.store.writes, [])

    def test_concurrent_different_upload_is_not_replaced(self):
        def racing_put(key, path, info):
            self.store.objects[key] = {'ContentLength': 1, 'Metadata': {'sha256': '0' * 64}}
            raise u.AwsError('An error occurred (PreconditionFailed)')
        with patch.object(self.store, 'put', side_effect=racing_put), self.assertRaisesRegex(ValueError, 'Concurrent upload conflict'):
            self.publish()

    def test_same_concurrent_upload_is_accepted(self):
        original = self.store.put
        def racing_put(*args):
            original(*args)
            raise u.AwsError('An error occurred (PreconditionFailed)')
        with patch.object(self.store, 'put', side_effect=racing_put), patch.object(u.catalog, 'verify_remote_head'):
            self.publish()
        self.assertEqual(len(self.store.writes), len(self.files))

    def test_aws_put_has_server_condition_and_content_md5(self):
        result = subprocess.CompletedProcess([], 0, '{}', '')
        path = self.directory / 'Installer.exe'
        with patch.object(u.subprocess, 'run', return_value=result) as run:
            u.S3('profile', 'bucket').put('v3.17.0-od/Installer.exe', path, self.files[path.name])
        command = run.call_args.args[0]
        self.assertEqual(command[command.index('--if-none-match') + 1], '*')
        expected = base64.b64encode(hashlib.md5(path.read_bytes(), usedforsecurity=False).digest()).decode()
        self.assertEqual(command[command.index('--content-md5') + 1], expected)

    def test_only_404_is_treated_as_missing(self):
        store = u.S3('profile', 'bucket')
        for code in ('403', 'AccessDenied', 'RequestTimeout'):
            with patch.object(store, 'call', side_effect=u.AwsError(f'An error occurred ({code})')):
                with self.assertRaises(u.AwsError):
                    store.head('key')
        with patch.object(store, 'call', side_effect=u.AwsError('An error occurred (404)')):
            self.assertIsNone(store.head('key'))

    def test_dry_run_never_uses_network_or_changes_catalog(self):
        before = self.index.read_bytes()
        with patch.object(u, 'S3') as store, patch.object(u.catalog, 'verify_remote_head') as head:
            u.main([self.version, '--directory', str(self.directory), '--index', str(self.index), '--dry-run'])
            store.assert_not_called()
            head.assert_not_called()
        self.assertEqual(self.index.read_bytes(), before)

    def test_public_head_failure_leaves_catalog_unchanged(self):
        before = self.index.read_bytes()
        with patch.object(u, 'S3', return_value=self.store), patch.object(u.subprocess, 'run'), patch.object(u.catalog, 'verify_remote_head', side_effect=ValueError('not public')):
            with self.assertRaisesRegex(ValueError, 'not public'):
                u.main([self.version, '--directory', str(self.directory), '--index', str(self.index), '--update-catalog'])
        self.assertEqual(self.index.read_bytes(), before)

    def test_existing_catalog_version_cannot_change_before_network(self):
        self.index.write_text(json.dumps({'releases': [{**self.entry, 'sha256': 'b' * 64}]}))
        with patch.object(u, 'S3') as store:
            with self.assertRaisesRegex(ValueError, 'immutable'):
                u.main([self.version, '--directory', str(self.directory), '--index', str(self.index)])
            store.assert_not_called()


if __name__ == '__main__':
    unittest.main()
