#!/usr/bin/env python3
"""A single library driver installs signed fixtures A/B, offline imports and removes B."""
import hashlib,json,shutil,unittest,zipfile
from pathlib import Path
from integration import InstallerTests,ROOT,DRIVER
FIXTURES=ROOT/'Releases/build/installer-remake-acceptance'
class EvolutionTests(InstallerTests):
 def use(self,payload):
  p=self.bundle/'payload';p.unlink();p.symlink_to(payload,target_is_directory=True)
 def archive(self,payload,name):
  out=self.base/(name+'.zip')
  with zipfile.ZipFile(out,'w',zipfile.ZIP_STORED) as z:
   for p in payload.rglob('*'):
    if p.is_file():z.write(p,p.relative_to(payload))
  return out
 def test_one_binary_installs_a_b_and_removes_with_saved_recipe(self):
  binary=hashlib.sha256(DRIVER.read_bytes()).hexdigest();cache=self.base/'cache'
  for label in ['A','B']:
   archive=self.archive(FIXTURES/label,label)
   response=json.loads(self.cli('import',str(archive),str(cache)).stdout)
   self.use(Path(response['payloadRoot']));self.apply(self.plan('install'))
   old=self.device/'data/local/bin/voyahtune_acceptance_old.json'
   new=self.device/'data/local/bin/voyahtune_acceptance_new.json'
   if label=='A':self.assertTrue(old.is_file());self.assertFalse(new.exists())
   else:
    self.assertFalse(old.exists());self.assertEqual(json.loads(new.read_text()),{'fixture':'B'})
    self.assertEqual(new.stat().st_mode&0o777,0o755)
    self.assertTrue((self.device/'data/local/bin/voyahtune_fixture').is_dir())
  saved=self.base/'recovery'
  self.cli('removal',response['payloadRoot'],str(saved))
  shutil.rmtree(cache);self.use(saved)
  self.apply(self.plan('remove'))
  self.assertFalse(new.exists());self.assertFalse((self.device/'data/local/bin/voyahtune_fixture').exists())
  self.assertEqual(binary,hashlib.sha256(DRIVER.read_bytes()).hexdigest())
 def test_c_and_unknown_operations_reject_before_adb(self):
  p=self.base/'future';p.mkdir();manifest=json.loads((FIXTURES/'B/manifest.json').read_text())
  manifest['requirements']['minInstallerVersion']='99.0.0';(p/'manifest.json').write_text(json.dumps(manifest));self.use(p)
  result=self.cli('plan','--device','CAR-001','--action','install',okay=False)
  self.assertIn('INSTALLER_UPDATE_REQUIRED',result.stdout);self.assertFalse((self.base/'calls.jsonl').exists())
  manifest['requirements']['minInstallerVersion']='1.0.0';manifest['recipe']['exec']='echo unexpected'
  (p/'manifest.json').write_text(json.dumps(manifest));self.assertNotEqual(self.cli('plan','--device','CAR-001','--action','install',okay=False).returncode,0)
  self.assertFalse((self.base/'calls.jsonl').exists())
 def test_corrupt_zip_never_enters_cache(self):
  archive=self.base/'bad.zip';archive.write_bytes(b'broken zip')
  cache=self.base/'cache';self.assertNotEqual(self.cli('import',str(archive),str(cache),okay=False).returncode,0)
  self.assertEqual(json.loads(self.cli('cache-list',str(cache)).stdout),[])
 def test_manifest_tampering_is_rejected_by_signed_recipe(self):
  p=self.base/'tampered';shutil.copytree(FIXTURES/'B',p)
  manifest=json.loads((p/'manifest.json').read_text());manifest['recipe']['attributes'][0]['mode']=420
  (p/'manifest.json').write_text(json.dumps(manifest));self.use(p)
  result=self.cli('plan','--device','CAR-001','--action','install',okay=False)
  self.assertIn('RECIPE_SIGNATURE',result.stdout);self.assertFalse((self.base/'calls.jsonl').exists())
def load_tests(loader,tests,pattern):
 return unittest.TestSuite(EvolutionTests(n) for n in EvolutionTests.__dict__ if n.startswith('test_'))
if __name__=='__main__':unittest.main(verbosity=2)
