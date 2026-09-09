#!/usr/bin/env python3
"""Package the native Linux x86-64 AppDir without rebuilding the Android payload."""
import argparse
import hashlib
import json
import shutil
import struct
import tarfile
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--appdir', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
if not (args.appdir / 'AppRun').is_file():
    parser.error('Expected an extracted AppDir with AppRun')
for name in ['voyahtune', 'voyahtune-desktop']:
    with (args.appdir / 'usr/bin' / name).open('rb') as binary:
        header = binary.read(20)
    if header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', header, 18)[0] != 62:
        parser.error(f'{name} must be a Linux x86-64 executable')

output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
    archive = Path(temporary) / 'appdir.tar.gz'
    with tarfile.open(archive, 'w:gz', compresslevel=6) as tar:
        tar.add(args.appdir, arcname='x86_64')
    with output.open('wb') as result:
        result.write((root / 'Installer/packaging/linux-x64.sh').read_bytes())
        with archive.open('rb') as source:
            shutil.copyfileobj(source, result)
output.chmod(0o755)
digest = hashlib.sha256()
with output.open('rb') as source:
    for chunk in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(chunk)
sha = digest.hexdigest()
output.with_suffix(output.suffix + '.sha256').write_text(f'{sha}  {output.name}\n')
print(json.dumps({'file': str(output), 'bytes': output.stat().st_size, 'sha256': sha}))
