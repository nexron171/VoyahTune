#!/usr/bin/env python3
"""Upload a checked release directory to Yandex S3 without replacing objects."""
import argparse
import base64
import hashlib
import importlib.util
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
ENDPOINT = 'https://storage.yandexcloud.net'
MAX_OBJECT_SIZE = 5_000_000_000  # Single PutObject; no multipart state to clean up.
SAFE_NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]*\Z')
VERSION = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?\Z')
spec = importlib.util.spec_from_file_location('catalog_update', Path(__file__).with_name('update-catalog.py'))
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


def hashes(path):
    sha, md5 = hashlib.sha256(), hashlib.md5(usedforsecurity=False)
    size = 0
    with path.open('rb') as source:
        while block := source.read(1024 * 1024):
            size += len(block)
            sha.update(block)
            md5.update(block)
    return {'size': size, 'sha256': sha.hexdigest(), 'md5': base64.b64encode(md5.digest()).decode('ascii')}


def release_url(bucket, version, name):
    return f'{ENDPOINT}/{bucket}/v{version}/{name}'


def prepare(directory, version, bucket):
    """Require an exact, flat SHA256SUMS inventory before any network operation."""
    if not VERSION.fullmatch(version):
        raise ValueError('Use a release version such as 3.17.0 (without v)')
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', bucket):
        raise ValueError('Invalid bucket name')
    files = {}
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file() or not SAFE_NAME.fullmatch(path.name):
            raise ValueError(f'Only regular files with simple names are allowed: {path.name}')
        if path.stat().st_size > MAX_OBJECT_SIZE:
            raise ValueError(f'File exceeds the supported 5 GB single-upload limit: {path.name}')
        files[path.name] = hashes(path)
    payload_name = f'payload_{version}.zip'
    entry_name = f'payload_{version}.json'
    required = {'SHA256SUMS', payload_name, entry_name}
    if not required <= files.keys():
        raise ValueError(f'Missing required files: {sorted(required - files.keys())}')
    expected = {}
    for line in (directory / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)', line)
        if not match or match[2] in expected or match[2] == 'SHA256SUMS':
            raise ValueError('SHA256SUMS must contain unique SHA-256 + two spaces + basename lines')
        expected[match[2]] = match[1]
    if set(expected) != set(files) - {'SHA256SUMS'}:
        raise ValueError('SHA256SUMS must list every release file except itself; remove unrelated files')
    for name, digest in expected.items():
        if files[name]['sha256'] != digest:
            raise ValueError(f'Local SHA-256 mismatch: {name}')
    entry = json.loads((directory / entry_name).read_text())
    catalog.merge({'releases': []}, entry)  # Enforce the shared four-field contract.
    if (entry['version'] != version or type(entry['size']) is not int
            or entry['size'] != files[payload_name]['size']
            or entry['sha256'] != files[payload_name]['sha256']):
        raise ValueError('Payload entry does not match the local ZIP/version')
    expected_url = release_url(bucket, version, payload_name)
    if entry['url'] != expected_url:
        raise ValueError(f'Set entry url to {expected_url}, then regenerate SHA256SUMS')
    return files, entry


class AwsError(RuntimeError):
    def __init__(self, message):
        super().__init__(message)
        match = re.search(r'An error occurred \(([^)]+)\)', message)
        self.code = match[1] if match else None


class S3:
    def __init__(self, profile, bucket):
        self.bucket = bucket
        self.command = ['aws', '--profile', profile, '--region', 'ru-central1',
                        '--endpoint-url', ENDPOINT, '--no-cli-pager', '--output', 'json',
                        '--cli-connect-timeout', '15', '--cli-read-timeout', '300', 's3api']

    def call(self, *args):
        # Content-MD5 provides Yandex's server-side transport integrity check.
        env = {**os.environ, 'AWS_REQUEST_CHECKSUM_CALCULATION': 'when_required'}
        result = subprocess.run(self.command + list(args), capture_output=True, text=True, env=env)
        if result.returncode:
            raise AwsError(result.stderr.strip() or f'AWS CLI exited with {result.returncode}')
        return json.loads(result.stdout or '{}')

    def head(self, key):
        try:
            return self.call('head-object', '--bucket', self.bucket, '--key', key)
        except AwsError as error:
            if error.code in {'404', 'NoSuchKey', 'NotFound'}:
                return None
            raise  # AccessDenied/network errors must never be mistaken for absence.

    def put(self, key, path, info):
        return self.call('put-object', '--bucket', self.bucket, '--key', key,
                         '--body', str(path), '--if-none-match', '*',
                         '--content-md5', info['md5'],
                         '--metadata', json.dumps({'sha256': info['sha256']}),
                         '--content-type', mimetypes.guess_type(path.name)[0] or 'application/octet-stream',
                         '--cache-control', 'public,max-age=31536000,immutable')


def matches(remote, info):
    # Yandex canonicalizes metadata header names; AWS CLI can return "Sha256".
    digests = {value for key, value in (remote or {}).get('Metadata', {}).items() if key.lower() == 'sha256'}
    return (remote is not None and remote.get('ContentLength') == info['size']
            and digests == {info['sha256']})


def publish(directory, version, bucket, files, store, check_only=False):
    # Check the whole batch before the first write, so a late conflict uploads nothing.
    missing = []
    for name, info in sorted(files.items()):
        key = f'v{version}/{name}'
        remote = store.head(key)
        if remote is None:
            missing.append(name)
        elif not matches(remote, info):
            raise ValueError(f'Remote conflict (size or SHA-256 metadata): {key}; nothing overwritten')
        else:
            print(f'Exists, matches upload metadata: {key}', flush=True)
    if check_only and missing:
        raise ValueError(f'Missing remote files: {", ".join(missing)}')
    with tempfile.TemporaryDirectory(prefix='voyahtune-s3-') as temporary:
        for name in missing:
            info = files[name]
            snapshot = Path(temporary) / name
            shutil.copyfile(directory / name, snapshot)
            if hashes(snapshot) != info:
                raise ValueError(f'Local file changed after validation: {name}')
            key = f'v{version}/{name}'
            print(f'Uploading {name} ({info["size"]} bytes)…', flush=True)
            try:
                store.put(key, snapshot, info)
            except AwsError as error:
                # Another publisher or an SDK retry may have completed the same object.
                if error.code not in {'412', 'PreconditionFailed', '409', 'ConditionalRequestConflict'}:
                    raise
                if not matches(store.head(key), info):
                    raise ValueError(f'Concurrent upload conflict: {key}; nothing overwritten') from error
            if not matches(store.head(key), info):
                raise ValueError(f'Uploaded object HEAD differs: {key}')
            snapshot.unlink()
    for name, info in sorted(files.items()):
        catalog.verify_remote_head({**info, 'url': release_url(bucket, version, name)})
        print(f'Public HEAD OK: {name}', flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='VoyahTune release version, e.g. 3.17.0')
    parser.add_argument('--directory', type=Path, help='Flat release directory (default: Releases/dist/s3-vVERSION)')
    parser.add_argument('--profile', default='voyahtune')
    parser.add_argument('--bucket', default='voyahtune')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--dry-run', action='store_true', help='Validate local inputs and print plan; no network or writes')
    modes.add_argument('--check-remote', action='store_true', help='Check existing objects and public HEAD; no upload or catalog edits')
    parser.add_argument('--update-catalog', action='store_true', help='After all HEAD checks succeed, merge into the local catalog; no git commit/push')
    parser.add_argument('--index', type=Path, default=ROOT / 'Releases/ota/index.json')
    parser.add_argument('--builder', type=Path, default=ROOT / 'Installer/target/release/installer-build')
    args = parser.parse_args(argv)
    if args.update_catalog and (args.dry_run or args.check_remote):
        parser.error('--update-catalog cannot be combined with --dry-run or --check-remote')
    directory = args.directory or ROOT / 'Releases/dist' / f's3-v{args.version}'
    files, entry = prepare(directory, args.version, args.bucket)
    # Check version immutability even if this invocation does not edit the catalog.
    index = json.loads(args.index.read_text())
    catalog.merge(index, entry)
    if args.update_catalog:
        # Reject catalog/schema/builder errors before writing anything to S3.
        with tempfile.TemporaryDirectory() as temporary:
            merged = Path(temporary) / 'index.json'
            merged.write_text(json.dumps(index))
            subprocess.run([str(args.builder.resolve()), 'verify-catalog', str(merged)], check=True, stdout=subprocess.DEVNULL)
    for name, info in sorted(files.items()):
        print(f's3://{args.bucket}/v{args.version}/{name}  {info["size"]} bytes  sha256={info["sha256"]}')
    if args.dry_run:
        print('Local validation passed. No network requests or writes performed.')
        return
    publish(directory, args.version, args.bucket, files, S3(args.profile, args.bucket), args.check_remote)
    if args.update_catalog:
        with tempfile.TemporaryDirectory() as temporary:
            checked_entry = Path(temporary) / 'entry.json'
            checked_entry.write_text(json.dumps(entry))
            changed = catalog.update(args.index, checked_entry, args.builder.resolve(), False, verify_head=True)
        print('Local catalog updated; commit/push separately.' if changed else 'Local catalog already up to date.')
    print('Done. Archives were not downloaded; HEAD checks compare size and upload metadata.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'Error: {error}', file=sys.stderr)
        sys.exit(1)
