#!/usr/bin/env python3
"""Prepare a signed OTA archive and a candidate copy of the shared catalog; never publish."""
import argparse
import datetime
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
    parser.add_argument('--notes-url', required=True)
    parser.add_argument('--key', type=Path, required=True)
    parser.add_argument('--sequence', type=int, required=True)
    parser.add_argument('--source-versions', default='>=3.15.0, <4.0.0')
    args = parser.parse_args()
    builder = ROOT / 'Installer/target/release/installer-build'
    manifest = json.loads(subprocess.check_output([str(builder), 'verify-payload', str(args.payload)], text=True))['manifest']
    public = subprocess.check_output(['openssl', 'pkey', '-in', str(args.key), '-pubout', '-outform', 'DER'])
    if public != (ROOT / 'Packaging/system/voyahtune-ota-key.der').read_bytes():
        parser.error('Signing key does not match the key installed in this OTA line')
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
    entry = dict(version=version, publishedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 channel='stable', notesUrl=args.notes_url, requirements=manifest['requirements'],
                 payload=dict(url=args.archive_url, size=archive.stat().st_size, sha256=digest, manifestSchema=manifest['schema']))
    unsigned = args.output / f'payload_{version}.json'
    unsigned.write_text(json.dumps(entry, ensure_ascii=False, indent=2) + '\n')
    signed = args.output / f'payload_{version}.ota.json'
    subprocess.run(['python3', str(ROOT / 'Installer/scripts/sign-ota.py'), '--entry', str(unsigned),
                    '--archive', str(archive), '--payload', str(args.payload), '--key', str(args.key),
                    '--sequence', str(args.sequence), '--source-versions', args.source_versions,
                    '--builder', str(builder), '--output', str(signed)], check=True)
    subprocess.run([str(builder), 'verify-ota', str(signed), str(ROOT / 'Packaging/system/voyahtune-ota-key.der'), str(args.payload)], check=True)
    catalog = json.loads((ROOT / 'Installer/releases/index.json').read_text())
    if any(release['version'] == version for release in catalog['releases']):
        raise ValueError('Version is already in the public catalog; use its immutable published archive')
    catalog['releases'].insert(0, json.loads(signed.read_text()))
    catalog['generatedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    candidate = args.output / 'index.json'
    candidate.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    normalized = subprocess.check_output([str(builder), 'verify-catalog', str(candidate)], text=True)
    candidate.write_text(normalized)
    print(f'Candidate only, not uploaded: {args.output}')


if __name__ == '__main__':
    main()
