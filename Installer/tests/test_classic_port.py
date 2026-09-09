#!/usr/bin/env python3
"""Differential tests: run the original host script and its Rust port on isolated cars.
The production GUI never executes these host scripts. They are the test oracle.
"""
import hashlib,json,os,shutil,subprocess,unittest
from pathlib import Path
from integration import InstallerTests,ROOT,PAYLOAD
class ClassicPortTests(unittest.TestCase):
 def fixture(self,state=None):
  f=InstallerTests();f.setUp();self.addCleanup(f.tearDown)
  if state:f.state.update(state);f.write_state()
  return f
 def classic(self,f,action):
  variant='light' if action=='light' else 'full';verb='remove' if action=='remove' else 'install'
  release=f.base/'classic';release.mkdir(exist_ok=True)
  manifest=json.loads((PAYLOAD/'manifest.json').read_text())
  names={'frida-inject':'frida-inject-16.2.1-android-arm64','whitelist.xml':'privapp-permissions-ru.big.town.anative.xml','dns-helper.sh':'dns-overlay-device.sh','dns.apk':'framework-res__config_ethernet_interfaces_yandexdns.apk'}
  for artifact in manifest['artifacts']:
   if artifact.get('variant') not in [None,variant]:continue
   shutil.copyfile(PAYLOAD/artifact['path'],release/names.get(artifact['name'],artifact['name']))
  shutil.copyfile(ROOT/f'Packaging/installer/{variant}/{verb}.sh',release/f'{verb}.sh')
  shutil.copyfile(ROOT/'Packaging/installer/common/dns-overlay.sh',release/'dns-overlay.sh')
  env={**f.env,'PATH':str(f.bundle/'adb')+os.pathsep+os.environ['PATH']}
  result=subprocess.run(['/bin/sh',f'{verb}.sh'],cwd=release,env=env,text=True,capture_output=True,timeout=180)
  return result
 def state(self,f):
  files={str(p.relative_to(f.device)):hashlib.sha256(p.read_bytes()).hexdigest() for p in f.device.rglob('*') if p.is_file() and not p.is_symlink()}
  return files,f.read_state()['settings'],f.read_state()['packages']
 def compare(self,action,state=None,seed=None):
  reference=self.fixture(state);port=self.fixture(state)
  if seed:seed(reference);seed(port)
  old=self.classic(reference,action);new=port.apply(port.plan(action),okay=False)
  self.assertEqual(new.returncode==0,old.returncode==0,old.stdout[-1600:]+old.stderr[-1000:]+'\nPORT:\n'+new.stdout[-3500:])
  self.assertEqual(self.state(port),self.state(reference))
  return reference,port,old,new
 def test_full_fresh_matches_classic(self):
  _,_,old,new=self.compare('full')
  self.assertEqual(old.returncode,0,old.stdout+old.stderr)
  self.assertEqual(new.returncode,0,new.stdout)
 def seed_client_migration(self,f):
  for path in ['data/local/bin/fullscreen_client.js','data/local/bin/fullscreen_client.js.voyahtune.new','data/local/tmp/voyahtune_fullscreen_client.old','data/local/tmp/voyahtune_app_client.old']:
   (f.device/path).write_text('legacy client')
  f.state['settings']['voyahtune_fullscreen_apps']='ru.yandex.yandexnavi,com.example.player'
  f.write_state()
 def test_app_client_migration_matches_classic(self):
  _,port,old,new=self.compare('full',seed=self.seed_client_migration)
  self.assertEqual(old.returncode,0,old.stdout+old.stderr)
  self.assertEqual(new.returncode,0,new.stdout)
  self.assertTrue((port.device/'data/local/bin/app_client.js').is_file())
  self.assertFalse((port.device/'data/local/bin/fullscreen_client.js').exists())
 def test_app_client_migration_failure_matches_classic(self):
  _,_,old,new=self.compare('full',{'failShell':'for app_client_pkg in $fullscreen_csv'},self.seed_client_migration)
  self.assertNotEqual(old.returncode,0)
  self.assertNotEqual(new.returncode,0)
 def test_light_and_remove_clean_both_client_generations(self):
  def seed(f):
   self.seed_client_migration(f)
   for path in ['data/local/bin/app_client.js','data/local/bin/app_client.js.voyahtune.new']:
    (f.device/path).write_text('new client')
  for action in ['light','remove']:
   with self.subTest(action=action):
    _,port,old,new=self.compare(action,seed=seed)
    self.assertEqual(old.returncode,0,old.stdout+old.stderr)
    self.assertEqual(new.returncode,0,new.stdout)
    self.assertFalse((port.device/'data/local/bin/app_client.js').exists())
    self.assertFalse((port.device/'data/local/bin/fullscreen_client.js').exists())
 def test_light_fresh_matches_classic(self):self.compare('light')
 def test_remove_matches_classic_and_does_not_wait_after_reboot(self):
  _,port,_,_=self.compare('remove',seed=lambda f:f.seed_apps(old_key=True,broken=True))
  calls=port.calls();last_reboot=max(i for i,c in enumerate(calls) if c['args']==['reboot']);self.assertEqual(last_reboot,len(calls)-1)
 def test_light_ignores_backup_pull_failure_like_classic(self):self.compare('light',{'failPull':True},lambda f:f.seed_apps())
 def test_full_backup_failure_stops_before_runtime_like_classic(self):self.compare('full',{'failPull':True},lambda f:f.seed_apps())
 def test_full_ignores_freeform_setting_failure_like_classic(self):self.compare('full',{'failShell':'settings put global enable_freeform_support'})
 def test_atomic_publish_failure_stops_and_restores_loader_like_classic(self):self.compare('full',{'failShell':'&& mv -f'})
 def test_obsolete_engine_records_do_not_block(self):
  f=self.fixture();base=f.device/'data/local/voyahtune-installer';(base/'lock').mkdir(parents=True);(base/'lock/owner').write_text('dead-operation');(base/'load.bin.installed').write_text('invalid-old-hash')
  p=f.plan('light');(f.device/'data/local/bin/keyboard_ru.js').write_text('changed after plan')
  self.assertEqual(f.apply(p).returncode,0);self.assertFalse((base/'lock').exists())
 def test_full_light_remove_sequence_matches_classic(self):
  reference=self.fixture();port=self.fixture()
  for action in ['full','light','remove']:
   old=self.classic(reference,action);new=port.apply(port.plan(action),okay=False)
   self.assertEqual(new.returncode,old.returncode,old.stdout[-1200:]+new.stdout[-2500:]);self.assertEqual(self.state(port),self.state(reference))
 def test_no_new_hash_gate_after_push(self):self.compare('full',{'corruptPush':True})
 def test_readonly_remove_has_no_extra_reboot_attempt(self):self.compare('remove',{'readOnly':True})
 def seed_legacy(self,f):
  p=f.device/'system/etc/init.logcat.sh';p.write_text('#!/system/bin/sh\n# init.logcat.sh Open Voyah:\n/system/bin/logcat -v threadtime\n')
 def test_legacy_migration_matches_classic(self):self.compare('full',seed=self.seed_legacy)
 def test_light_legacy_refusal_matches_classic(self):self.compare('light',seed=self.seed_legacy)
 def test_legacy_rollback_on_boot_publish_failure_matches_classic(self):self.compare('full',{'failShell':'mv -f /system/etc/.voyahtune.load.sh.new'},self.seed_legacy)
 def test_changed_signatures_reset_both_apps(self):
  f=self.fixture();f.seed_apps(old_key=True);result=f.apply(f.plan('light'))
  f.assert_app_data(False);self.assertEqual(f.read_state()['reboots'],2);self.assertIn('package-reset',result.stdout)
 def test_matching_signatures_keep_data(self):
  f=self.fixture();f.seed_apps();f.apply(f.plan('light'));f.assert_app_data(True)
 def test_android_signature_rejection_retries_once(self):
  f=self.fixture();f.seed_apps();f.state['rejectRestoreUpdate']=True;f.write_state();f.apply(f.plan('light'))
  self.assertEqual(sum(c['args'][0]=='install' for c in f.calls()),2)
  self.assertFalse((f.device/'data/user/0/ru.big.town.restoremode/settings-marker').exists())
 def test_storage_error_does_not_reset_data(self):
  f=self.fixture();f.seed_apps();f.state['installError']='INSTALL_FAILED_INSUFFICIENT_STORAGE';f.write_state()
  result=f.apply(f.plan('light'),okay=False);self.assertNotEqual(result.returncode,0);f.assert_app_data(True)
 def test_steps_match_plan(self):
  f=self.fixture();plan=f.plan('light');events=[json.loads(s) for s in f.apply(plan).stdout.splitlines()]
  self.assertEqual([s['id'] for s in plan['steps']],[e['stepId'] for e in events if e.get('type')=='step-started'])
  self.assertEqual([s['id'] for s in plan['steps']],[e['stepId'] for e in events if e.get('type')=='step-completed'])
if __name__=='__main__':unittest.main()
