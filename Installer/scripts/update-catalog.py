#!/usr/bin/env python3
"""Verify a published immutable payload before atomically adding it to the catalog."""
import argparse, hashlib, json, subprocess, tempfile, urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse
ROOT=Path(__file__).resolve().parents[2]

class HTTPSRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):
  if urlparse(newurl).scheme!='https':raise ValueError('Asset redirect must use HTTPS')
  return super().redirect_request(req,fp,code,msg,headers,newurl)

def verify_remote(entry):
 asset=entry['payload'];url=asset['url']
 if urlparse(url).scheme!='https':raise ValueError('Asset must use HTTPS')
 digest=hashlib.sha256();size=0
 with urllib.request.build_opener(HTTPSRedirect()).open(url,timeout=30) as response:
  while block:=response.read(1024*1024):
   size+=len(block)
   if size>asset['size']:raise ValueError('Published asset exceeds declared size')
   digest.update(block)
 if size!=asset['size'] or digest.hexdigest()!=asset['sha256']:
  raise ValueError('Published asset differs from the locally verified payload')

def merge(index,entry):
 if 'ota' in entry and type(entry['ota']) is not bool:
  raise ValueError('ota must be a JSON boolean')
 for old in index['releases']:
  if old['version']==entry['version']:
   if old['payload']!=entry['payload'] or old['requirements']!=entry['requirements']:
    raise ValueError('Published version is immutable; choose a new release version')
   # Availability may change without replacing the immutable archive. An entry
   # regenerated without the optional marker must not silently revoke OTA.
   if 'ota' in entry and old.get('ota',False)!=entry['ota']:
    old['ota']=entry['ota']
    index['generatedAt']=datetime.now(timezone.utc).isoformat()
    return True
   return False
 index['releases'].append(entry)
 index['generatedAt']=datetime.now(timezone.utc).isoformat()
 return True

def update(index_path,entry_path,builder,verify):
 index=json.loads(index_path.read_text())
 if entry_path is not None:
  entry=json.loads(entry_path.read_text());changed=merge(index,entry)
 else:changed=False
 # Core is the single format validator. Validate before network or modifying the index.
 with tempfile.TemporaryDirectory(dir=index_path.parent) as directory:
  staging=Path(directory)/'index.json';staging.write_text(json.dumps(index,ensure_ascii=False))
  normalized=subprocess.check_output([str(builder),'verify-catalog',str(staging)],text=True)
  if entry_path is not None:
   if not verify:raise ValueError('--verify-remote is required before updating the public catalog')
   verify_remote(entry)
  if changed:
   staging.write_text(normalized);staging.replace(index_path)
 return changed

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--index',type=Path,default=ROOT/'Installer/releases/index.json')
 p.add_argument('--entry',type=Path,help='Generated Releases/dist/payload_VERSION.json')
 p.add_argument('--builder',type=Path,default=ROOT/'Installer/target/release/installer-build')
 p.add_argument('--verify-remote',action='store_true')
 args=p.parse_args();changed=update(args.index,args.entry,args.builder,args.verify_remote)
 print('Catalog updated' if changed else 'Catalog verified; unchanged')
if __name__=='__main__':main()
