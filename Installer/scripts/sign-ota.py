#!/usr/bin/env python3
"""Sign an already built immutable archive entry. Does not publish or modify the public catalog."""
import argparse, hashlib, json, subprocess, tempfile
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--entry',type=Path,required=True)
    p.add_argument('--archive',type=Path,required=True)
    p.add_argument('--payload',type=Path,required=True)
    p.add_argument('--key',type=Path,required=True,help='Existing RSA private key PEM, outside Git')
    p.add_argument('--sequence',type=int,required=True)
    p.add_argument('--source-versions',default='>=3.14.0, <4.0.0')
    p.add_argument('--builder',type=Path,default=Path('Installer/target/release/installer-build'))
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    if a.sequence<1: p.error('sequence must be positive')
    entry=json.loads(a.entry.read_text()); archive=entry['payload']
    with a.archive.open('rb') as stream:
        sha=hashlib.file_digest(stream,'sha256').hexdigest()
    if sha!=archive['sha256'] or a.archive.stat().st_size!=archive['size']:p.error('Archive differs from entry')
    checked=json.loads(subprocess.check_output([str(a.builder),'verify-payload',str(a.payload)],text=True))
    if checked['manifest']['releaseVersion']!=entry['version']:p.error('Payload version differs from entry')
    claims=dict(schema=1,version=entry['version'],sequence=a.sequence,archiveSha256=sha,
        archiveSize=archive['size'],manifestSha256=hashlib.sha256((a.payload/'manifest.json').read_bytes()).hexdigest(),
        minUpdaterVersion='0.1.0',sourceVersions=a.source_versions,firmwarePolicy='bootstrap-fingerprint',
        capabilities=['qinggan-ota-v1'],apkSigners=checked['apkSigners'])
    body=json.dumps(claims,ensure_ascii=False,separators=(',',':'))
    with tempfile.TemporaryDirectory() as temp:
        source=Path(temp)/'body';signature=Path(temp)/'signature';source.write_bytes(body.encode())
        subprocess.run(['openssl','dgst','-sha256','-sign',str(a.key),'-out',str(signature),str(source)],check=True)
        entry['ota']=True;entry['otaMetadata']=dict(body=body,signature=signature.read_bytes().hex())
    a.output.write_text(json.dumps(entry,ensure_ascii=False,indent=2)+'\n')
    print(a.output)
if __name__=='__main__':main()
