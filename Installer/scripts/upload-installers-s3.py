#!/usr/bin/env python3
"""Publish standalone Installer binaries under Installers/VERSION without replacing objects."""
import argparse
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location('release_upload', Path(__file__).with_name('upload-release-s3.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def prepare(directory, version):
    if not release.VERSION.fullmatch(version):
        raise ValueError('Invalid Installer version')
    files = {}
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file() or not release.SAFE_NAME.fullmatch(path.name):
            raise ValueError(f'Only regular files with simple names are allowed: {path.name}')
        if path.stat().st_size > release.MAX_OBJECT_SIZE:
            raise ValueError(f'File exceeds the 5 GB limit: {path.name}')
        files[path.name] = release.hashes(path)
    required = {f'VoyahTune-Installer-{version}-macos.zip',
                f'VoyahTune-Installer-{version}-windows-x64.exe',
                f'VoyahTune-Installer-{version}-windows-x86.exe', 'SHA256SUMS'}
    allowed = required | {'BUILD-INFO.json'}
    if not required <= files.keys() or not files.keys() <= allowed:
        raise ValueError(f'Installer directory must contain {sorted(required)} and optionally BUILD-INFO.json')
    expected = {}
    for line in (directory / 'SHA256SUMS').read_text().splitlines():
        match = release.re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)', line)
        if not match or match[2] in expected or match[2] == 'SHA256SUMS':
            raise ValueError('Invalid SHA256SUMS')
        expected[match[2]] = match[1]
    if set(expected) != set(files) - {'SHA256SUMS'}:
        raise ValueError('SHA256SUMS must list every installer file')
    if any(files[name]['sha256'] != digest for name, digest in expected.items()):
        raise ValueError('Local SHA-256 mismatch')
    return files


def publish(directory, version, bucket, files, store, check_only=False):
    prefix = f'Installers/{version}/'
    missing = []
    for name, info in sorted(files.items()):
        key = prefix + name
        remote = store.head(key)
        if remote is None:
            missing.append(name)
        elif not release.matches(remote, info):
            raise ValueError(f'Remote conflict: {key}; nothing overwritten')
    if check_only and missing:
        raise ValueError(f'Missing remote files: {", ".join(missing)}')
    with tempfile.TemporaryDirectory(prefix='voyahtune-installers-s3-') as temporary:
        for name in missing:
            snapshot = Path(temporary) / name
            shutil.copyfile(directory / name, snapshot)
            if release.hashes(snapshot) != files[name]:
                raise ValueError(f'Local file changed after validation: {name}')
            key = prefix + name
            try:
                store.put(key, snapshot, files[name])
            except release.AwsError as error:
                if error.code not in {'412', 'PreconditionFailed', '409', 'ConditionalRequestConflict'}:
                    raise
                if not release.matches(store.head(key), files[name]):
                    raise ValueError(f'Concurrent upload conflict: {key}') from error
            if not release.matches(store.head(key), files[name]):
                raise ValueError(f'Uploaded object HEAD differs: {key}')
    for name, info in sorted(files.items()):
        release.catalog.verify_remote_head({**info, 'url': f'{release.ENDPOINT}/{bucket}/{prefix}{name}'})
        print(f'Public HEAD OK: {prefix}{name}', flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--profile', default='voyahtune')
    parser.add_argument('--bucket', default='voyahtune')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--dry-run', action='store_true')
    modes.add_argument('--check-remote', action='store_true')
    args = parser.parse_args(argv)
    directory = args.directory or release.ROOT / 'Releases/dist' / f'installers-{args.version}'
    files = prepare(directory, args.version)
    for name, info in sorted(files.items()):
        print(f's3://{args.bucket}/Installers/{args.version}/{name}  {info["size"]} bytes  sha256={info["sha256"]}')
    if not args.dry_run:
        publish(directory, args.version, args.bucket, files, release.S3(args.profile, args.bucket), args.check_remote)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'Error: {error}', file=sys.stderr)
        sys.exit(1)
