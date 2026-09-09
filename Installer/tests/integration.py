#!/usr/bin/env python3
"""Run after building the dev payload. No system ADB or real devices are used."""
import hashlib,json,os,shutil,subprocess,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
CLI=Path(os.environ.get('VOYAH_TEST_CLI',ROOT/'Installer/target/debug/installer-cli'))
PAYLOAD=Path(os.environ.get('VOYAH_TEST_PAYLOAD',ROOT/'Releases/build/installer-payload-dev-v2'))
class InstallerTests(unittest.TestCase):
 maxDiff=1500
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(prefix='voyah-installer-test-');self.base=Path(self.tmp.name);self.bundle=self.base/'bundle';(self.bundle/'adb').mkdir(parents=True)
  (self.bundle/'payload').symlink_to(PAYLOAD,target_is_directory=True)
  self.fixture=self.bundle/'adb/adb';shutil.copyfile(ROOT/'Installer/tests/fake_adb.py',self.fixture);self.fixture.chmod(0o755)
  # Entry name selects the host protocol. The same fixture also implements Android commands.
  self.fixture.rename(self.bundle/'adb/fake-adb');self.fixture.symlink_to('fake-adb')
  for name in ['getprop','setprop','id','pm','cmd','dumpsys','settings','restorecon','chown','mount','am','pidof','sha256sum','pkill','ps','grep']:
   p=self.base/'bin'/name;p.parent.mkdir(exist_ok=True);p.symlink_to(self.bundle/'adb/fake-adb')
  (self.bundle/'host-tools.json').write_text(json.dumps({'schema':1,'files':[{'path':'adb/adb','sha256':hashlib.sha256(self.fixture.read_bytes()).hexdigest()}]}))
  self.device=self.base/'device'
  for path in ['system/etc/init','system/etc/permissions','system/bin','system/priv-app','data/local/tmp','data/local/bin','proc/sys/kernel/random','sdcard/tmp']:(self.device/path).mkdir(parents=True,exist_ok=True)
  (self.device/'system/bin/sh').symlink_to('/bin/sh');(self.device/'proc/sys/kernel/random/boot_id').write_text('0\n')
  self.state={'root':False,'packages':{'android':'/system/framework/framework-res.apk','com.qinggan.canbus.service':'/system/canbus.apk','com.qinggan.keymanager.service':'/system/keys.apk'},'settings':{}}
  self.write_state();self.env={**os.environ,'VOYAH_FAKE_ROOT':str(self.base)}
 def tearDown(self):self.tmp.cleanup()
 def write_state(self): (self.base/'state.json').write_text(json.dumps(self.state))
 def read_state(self):self.state=json.loads((self.base/'state.json').read_text());return self.state
 def cli(self,*args,okay=True):
  result=subprocess.run([str(CLI),'--bundle',str(self.bundle),*args],env=self.env,text=True,capture_output=True,timeout=120)
  if okay:self.assertEqual(result.returncode,0,result.stdout[-5000:]+result.stderr)
  return result
 def plan(self,action='full'):
  return json.loads(self.cli('plan','--device','CAR-001','--action',action).stdout)
 def apply(self,plan,okay=True):return self.cli('apply','--device','CAR-001','--action',plan['request']['action'],'--token',plan['request']['inventoryToken'],'--yes','--logs',str(self.base/'logs'),okay=okay)
 def seed_apps(self,old_key=False,broken=False):
  files={'ru.big.town.anative':('/system/priv-app/Native/Native.apk',ROOT/'Native/app/release/app-release.apk' if old_key else PAYLOAD/'light/native.apk'),
         'ru.big.town.restoremode':('/data/app/ru.big.town.restoremode/base.apk',ROOT/'RestoreMode/app/debug/app-debug.apk' if old_key else PAYLOAD/'light/restore_mode.apk')}
  for package,(path,source) in files.items():
   target=self.device/path.lstrip('/');target.parent.mkdir(parents=True,exist_ok=True)
   if broken:target.write_bytes(b'broken unsigned APK')
   else:shutil.copyfile(source,target)
   self.state['packages'][package]=path
   for parent in ['data/user/0','data/user_de/0']:
    data=self.device/parent/package;data.mkdir(parents=True,exist_ok=True);(data/'settings-marker').write_text('preserve unless reset')
  if old_key:
   self.state['rejectRestoreUpdate']=True
   self.state['nativeOldHash']=hashlib.sha256((self.device/'system/priv-app/Native/Native.apk').read_bytes()).hexdigest()
  self.write_state()
 def calls(self):return [json.loads(line) for line in (self.base/'calls.jsonl').read_text().splitlines()]
 def assert_app_data(self,present):
  for package in ['ru.big.town.anative','ru.big.town.restoremode']:
   for parent in ['data/user/0','data/user_de/0']:
    self.assertEqual((self.device/parent/package/'settings-marker').exists(),present)
 def test_remove_does_not_verify_old_or_broken_signatures(self):
  self.seed_apps(old_key=True,broken=True)
  p=self.plan('remove');self.assertTrue(all(not app['signers'] for app in p['inventory']['packages'].values()))
  self.apply(p);self.assert_app_data(False);self.assertEqual(self.plan('remove')['inventory']['state'],'absent')
 def ephemeral_links(self):
  target=self.device/'data/local/tmp/unrelated-tool.txt';target.write_text('keep target bytes')
  names=['voyah_swk_km.busy','voyahtune_swk_km.busy','voyahtune_load.v2.lock','voyah_load.v2.lock']
  for i,name in enumerate(names):
   (self.device/'data/local/tmp'/name).symlink_to(target if i==0 else '123|456|token')
  directory=self.device/'data/local/tmp/voyah_load.lock';directory.mkdir();(directory/'pid').write_text('123')
  return target,names,directory
 def assert_ephemeral_removed(self,target,names,directory):
  self.assertEqual(target.read_text(),'keep target bytes');self.assertFalse(directory.exists())
  for name in names:self.assertFalse((self.device/'data/local/tmp'/name).is_symlink())
  for path in (self.base/'logs').rglob('backup.json'):
   self.assertFalse(any('/data/local/tmp/' in item['path'] for item in json.loads(path.read_text())))
 def legacy_installer_lock(self,owner=True):
  lock=self.device/'data/local/voyahtune-installer/lock';lock.mkdir(parents=True)
  if owner:(lock/'owner').write_text('20260908T161851844-84804')
  return lock
 def test_regular_file_symlink_matches_classic_backup(self):
  target=self.device/'data/local/tmp/unrelated-tool.txt';target.write_text('keep')
  link=self.device/'data/local/bin/keyboard_ru.js';link.symlink_to(target)
  r=self.cli('plan','--device','CAR-001','--action','full',okay=False)
  self.assertEqual(r.returncode,0,r.stdout);self.assertEqual(target.read_text(),'keep')
 def test_traversable_data_without_listing_permission(self):
  parent=self.device/'data';parent.chmod(0o111)
  try:
   self.assertFalse(os.access(parent,os.R_OK));self.assertTrue(os.access(parent,os.X_OK))
   self.assertEqual(self.plan('light')['inventory']['state'],'absent')
   self.assertEqual(self.plan('remove')['inventory']['state'],'absent')
  finally:parent.chmod(0o755)
 def test_profile_and_release_metadata_do_not_block_classic_flow(self):
  self.state['sdk']='31';self.state['abi']='armeabi-v7a';self.state['packages'].pop('com.qinggan.keymanager.service');self.write_state()
  p=self.plan('light');self.assertTrue(p['warnings']);self.assertEqual(p['request']['action'],'light')
 def test_plan_requests_root_before_inspecting_files(self):
  self.plan('light');calls=self.calls()
  root=next(i for i,c in enumerate(calls) if c['args']==['root'])
  file_check=next(i for i,c in enumerate(calls) if 'sha256sum' in (c['script'] or ''))
  self.assertLess(root,file_check)
 def test_cancellation_is_reported_without_mutations(self):
  p=self.plan();r=subprocess.run([str(CLI),'--bundle',str(self.bundle),'apply','--device','CAR-001','--action','full','--token',p['request']['inventoryToken'],'--yes','--interactive','--logs',str(self.base/'logs')],env=self.env,input='{"cancel":true}\n',text=True,capture_output=True,timeout=120)
  self.assertEqual(r.returncode,130,r.stdout[-5000:]);self.assertIn('CANCELLED',r.stdout[-5000:]);self.assertFalse((self.device/'data/local/voyahtune-installer').exists())
 def test_multiple_including_unauthorized_blocks_plan(self):
  self.state['devices']=[['CAR-001','device'],['PHONE','unauthorized']];self.write_state();r=self.cli('plan','--device','CAR-001','--action','full',okay=False);self.assertNotEqual(r.returncode,0);self.assertIn('MULTIPLE_DEVICES',r.stdout);self.assertFalse((self.device/'data/local/voyahtune-installer').exists())
 def test_confirmation_required_before_mutation(self):
  p=self.plan();r=self.cli('apply','--device','CAR-001','--action','full','--token',p['request']['inventoryToken'],okay=False);self.assertIn('CONFIRMATION_REQUIRED',r.stdout);self.assertFalse((self.device/'data/local/voyahtune-installer').exists())
if __name__=='__main__':unittest.main(verbosity=2)
