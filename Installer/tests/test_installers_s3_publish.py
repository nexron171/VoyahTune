import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('installers_upload', Path(__file__).resolve().parents[1] / 'scripts/upload-installers-s3.py')
upload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upload)


class FakeS3:
    def __init__(self):
        self.objects = {}
        self.writes = []

    def head(self, key):
        return self.objects.get(key)

    def put(self, key, path, info):
        self.writes.append(key)
        self.objects[key] = {'ContentLength': path.stat().st_size,
                             'Metadata': {'sha256': info['sha256']}}


class InstallerUploadTests(unittest.TestCase):
    def test_complete_directory_uploads_under_installer_version_and_resumes(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            version = '1.3.0'
            for suffix in ('macos.zip', 'windows-x64.exe', 'windows-x86.exe'):
                (directory / f'VoyahTune-Installer-{version}-{suffix}').write_bytes(suffix.encode())
            (directory / 'SHA256SUMS').write_text(''.join(
                f'{upload.release.hashes(path)["sha256"]}  {path.name}\n'
                for path in sorted(directory.iterdir())))
            files = upload.prepare(directory, version)
            store = FakeS3()
            with patch.object(upload.release.catalog, 'verify_remote_head'):
                upload.publish(directory, version, 'voyahtune', files, store)
                upload.publish(directory, version, 'voyahtune', files, store)
            self.assertEqual(len(store.writes), 4)
            self.assertTrue(all(key.startswith('Installers/1.3.0/') for key in store.writes))
            (directory / 'payload_3.16.0.zip').write_bytes(b'payload')
            with self.assertRaisesRegex(ValueError, 'Installer directory'):
                upload.prepare(directory, version)


if __name__ == '__main__':
    unittest.main()
