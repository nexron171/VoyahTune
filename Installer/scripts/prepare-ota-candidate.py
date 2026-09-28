#!/usr/bin/env python3
"""Prepare an OTA archive and a simple candidate catalog; never publish."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New directory')
    parser.add_argument('--archive-url', required=True, help='Intended HTTPS URL; upload is a separate step')
    args = parser.parse_args()
    builder = ROOT / 'Installer/target/release/installer-build'
    manifest = json.loads(subprocess.check_output([str(builder), 'verify-payload', str(args.payload)], text=True))['manifest']
    version = manifest['releaseVersion']
    args.output.mkdir(parents=True, exist_ok=False)
    archive = args.output / f'payload_{version}.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for path in sorted(args.payload.rglob('*')):
            if path.is_symlink():
                raise ValueError(f'Symlink in payload: {path}')
            if path.is_file():
                bundle.write(path, path.relative_to(args.payload))
    with archive.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    entry = dict(version=version, url=args.archive_url, size=archive.stat().st_size, sha256=digest)
    entry_file = args.output / f'payload_{version}.json'
    entry_file.write_text(json.dumps(entry, ensure_ascii=False, indent=2) + '\n')
    subprocess.run([str(builder), 'verify-ota', str(entry_file), str(args.payload)], check=True)
    catalog = json.loads((ROOT / 'Releases/ota/index.json').read_text())
    if any(release['version'] == version for release in catalog['releases']):
        raise ValueError('Version is already in the public catalog; use its immutable published archive')
    catalog['releases'].insert(0, entry)
    candidate = args.output / 'index.json'
    candidate.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    normalized = subprocess.check_output([str(builder), 'verify-catalog', str(candidate)], text=True)
    candidate.write_text(normalized)
    print(f'Candidate only, not uploaded: {args.output}')


if __name__ == '__main__':
    main()
