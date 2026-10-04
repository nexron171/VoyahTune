#!/usr/bin/env python3
"""Build signed A/B payload fixtures with the regular APK builder; never publish them."""
import json, os, shutil, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'Releases/build/installer-remake-acceptance'
SOURCE=WORK/'source'
SOURCE.mkdir(parents=True,exist_ok=True)
shutil.copytree(ROOT/'Packaging',SOURCE/'Packaging',dirs_exist_ok=True)
for name in ['Native','RestoreMode']:
 path=SOURCE/name
 if path.is_symlink():path.unlink()
 shutil.copytree(ROOT/name,path,dirs_exist_ok=True,ignore=shutil.ignore_patterns('build','.gradle','.kotlin','release','debug'))
for name in ['SharedAndroid']:
 path=SOURCE/name
 if not path.exists():path.symlink_to(ROOT/name,target_is_directory=True)
base=json.loads((SOURCE/'Packaging/installer/payload-spec.json').read_text())
for label,version in [('A','3.13.0'),('B','3.13.1')]:
 recipe=json.loads(json.dumps(base))
 for filename in ['voyahtune_acceptance_old.json','voyahtune_acceptance_new.json']:
  (SOURCE/'Packaging/inject'/filename).unlink(missing_ok=True)
 filename='voyahtune_acceptance_'+('old' if label=='A' else 'new')+'.json'
 (SOURCE/'Packaging/inject'/filename).write_text(json.dumps({'fixture':label}))
 recipe['files'].append(dict(artifact=filename,destination='/data/local/bin/'+filename,mode=420,phase='files'))
 if label=='B':
  recipe['removeFiles'].append('/data/local/bin/voyahtune_acceptance_old.json')
  recipe['directories']=[dict(path='/data/local/bin/voyahtune_fixture',mode=493)]
  recipe['attributes']=[dict(path='/data/local/bin/'+filename,mode=493)]
 (SOURCE/'Packaging/installer/payload-spec.json').write_text(json.dumps(recipe))
 subprocess.run([str(ROOT/'Installer/target/release/installer-build'),'build','--root',str(SOURCE),'--version',version,'--revision','acceptance-'+label,'--output',str(WORK/label)],check=True)
