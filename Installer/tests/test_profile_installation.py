#!/usr/bin/env python3
"""Independent signed PI/OD installation and removal fixtures; no real ADB."""
import json, os, shutil, unittest
from pathlib import Path
from integration import InstallerTests

class ProfileInstallationTests(InstallerTests):
 def use(self, payload):
  target=self.bundle/'payload';target.unlink();target.symlink_to(payload,target_is_directory=True)
 def payload(self, infrastructure):
  name='VOYAH_TEST_'+infrastructure.upper()+'_PAYLOAD'
  self.assertIn(name,os.environ,f'Set {name} to a verified signed payload')
  return Path(os.environ[name])
 def clean_install_remove(self, infrastructure):
  self.use(self.payload(infrastructure));self.apply(self.plan())
  binary='loaderFrida' if infrastructure=='pi' else 'load.bin'
  self.assertTrue((self.device/'data/local/bin'/binary).is_file())
  self.assertEqual(json.loads((self.device/'system/etc/voyahtune-ota-bootstrap.json').read_text())['infrastructure'],infrastructure)
  if infrastructure=='pi':self.assertIn('big.town.runyn',self.read_state()['packages'])
  self.apply(self.plan('remove'))
  for name in ['load.bin','loaderFrida','injects.json']:
   self.assertFalse((self.device/'data/local/bin'/name).exists(),name)
  self.assertNotIn('big.town.runyn',self.read_state()['packages'])
 def test_od_clean_install_remove(self):self.clean_install_remove('od')
 def test_pi_clean_install_remove(self):self.clean_install_remove('pi')
 def test_same_profile_reinstall_does_not_restore_own_loader_backup(self):
  self.use(self.payload('od'));self.apply(self.plan());self.apply(self.plan())
  backup=next((self.base/'logs').rglob('backup'))
  self.assertFalse((backup/'load.bin').exists())
  # Simulate a known own backup produced by an older installer. Removal-only
  # recovery has no runtime artifacts; optional signed installed evidence identifies it.
  shutil.copyfile(self.device/'data/local/bin/load.bin',backup/'load.bin')
  recovery=self.base/'recovery';self.cli('removal',str(self.payload('od')),str(recovery));self.use(recovery)
  self.apply(self.plan('remove'))
  self.assertFalse((self.device/'data/local/bin/load.bin').exists())
 def test_foreign_loader_backup_is_restored(self):
  self.use(self.payload('od'));foreign=b'foreign shell supervisor'
  path=self.device/'data/local/bin/load.bin';path.write_bytes(foreign)
  self.apply(self.plan());self.apply(self.plan('remove'))
  self.assertEqual(path.read_bytes(),foreign)
 def test_runyn_signature_rejection_preserves_existing_data(self):
  self.use(self.payload('pi'));self.apply(self.plan())
  marker=self.device/'data/user/0/big.town.runyn/settings-marker';marker.write_text('keep settings')
  self.state=self.read_state();self.state['runynInstallError']='INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures differ';self.write_state()
  result=self.apply(self.plan(),okay=False);self.assertNotEqual(result.returncode,0)
  self.assertEqual(marker.read_text(),'keep settings');self.assertIn('big.town.runyn',self.read_state()['packages'])
  self.assertFalse(any('pm uninstall big.town.runyn' in (c['script'] or '') for c in self.calls()))

def load_tests(loader,tests,pattern):
 return unittest.TestSuite(ProfileInstallationTests(name) for name in ProfileInstallationTests.__dict__ if name.startswith('test_'))
if __name__=='__main__':unittest.main(verbosity=2)
