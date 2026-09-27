#!/usr/bin/env python3
"""Acceptance-stage test against built APKs, with an ephemeral signing key (no production secrets)."""
import hashlib,json,os,subprocess,tempfile,unittest,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BUILDER=ROOT/'Installer/target/release/installer-build'
PAYLOAD=Path(os.environ.get('VOYAH_TEST_PAYLOAD',ROOT/'Releases/build/ota-acceptance-3.15.0'))
class OtaSignatureTest(unittest.TestCase):
 def test_real_payload_signature_identity_and_tampering(self):
  manifest=json.loads((PAYLOAD/'manifest.json').read_text())
  with tempfile.TemporaryDirectory() as temporary:
   t=Path(temporary);key=t/'key.pem';public=t/'key.der';archive=t/'payload.zip'
   subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(key)],check=True,stderr=subprocess.DEVNULL)
   subprocess.run(['openssl','pkey','-in',str(key),'-pubout','-outform','DER','-out',str(public)],check=True)
   with zipfile.ZipFile(archive,'w',zipfile.ZIP_STORED) as z:
    for p in PAYLOAD.rglob('*'):
     if p.is_file():z.write(p,p.relative_to(PAYLOAD))
   with archive.open('rb') as stream: archive_sha=hashlib.file_digest(stream,'sha256').hexdigest()
   entry=dict(version=manifest['releaseVersion'],publishedAt='2026-09-28',channel='stable',notesUrl='https://example.org/notes',requirements=manifest['requirements'],payload=dict(url='https://example.org/payload.zip',size=archive.stat().st_size,sha256=archive_sha,manifestSchema=manifest['schema']))
   source=t/'entry.json';source.write_text(json.dumps(entry));signed=t/'signed.json'
   subprocess.run(['python3',str(ROOT/'Installer/scripts/sign-ota.py'),'--entry',str(source),'--archive',str(archive),'--payload',str(PAYLOAD),'--key',str(key),'--sequence','1','--output',str(signed),'--builder',str(BUILDER)],check=True,stdout=subprocess.DEVNULL)
   command=[str(BUILDER),'verify-ota',str(signed),str(public),str(PAYLOAD)]
   result=subprocess.run(command,text=True,capture_output=True);self.assertEqual(result.returncode,0,result.stderr)
   original=json.loads(signed.read_text())
   changed=json.loads(signed.read_text());changed['otaMetadata']['body']=changed['otaMetadata']['body'].replace('"sequence":1','"sequence":2');signed.write_text(json.dumps(changed))
   self.assertNotEqual(subprocess.run(command,capture_output=True).returncode,0)
   changed=original;changed['payload']['size']+=1;signed.write_text(json.dumps(changed))
   self.assertNotEqual(subprocess.run(command,capture_output=True).returncode,0)
if __name__=='__main__':unittest.main()
