#!/usr/bin/env python3
"""Runtime mode and data-preserving Light cleanup on isolated fake devices."""
import json, unittest
from integration import InstallerTests, PAYLOAD

class ModeTests(InstallerTests):
 def no_writes(self,calls):
  for c in calls:
   self.assertNotIn(c['args'][0],['push','install','uninstall','reboot','remount'])
   self.assertFalse(any(s in (c['script'] or '') for s in ['settings put','settings delete','rm -','chmod ','chown ','mv -']))
 def assert_light_clean(self):
  manifest=json.loads((PAYLOAD/'manifest.json').read_text())
  for f in manifest['recipe']['files']:
   if 'light' not in f['variants']:
    self.assertFalse((self.device/f['destination'].lstrip('/')).exists(),f['destination'])
  for path in ['data/local/bin/fullscreen_client.js','data/local/bin/voyahtune-hook-manifest.json','data/local/tmp/voyahtune-hook-status.v1']:
   self.assertFalse((self.device/path).exists(),path)
 def test_all_previous_modes_allow_both_actions_without_writes(self):
  self.seed_apps()
  for flag in [None,'light','full','invalid']:
   self.state['settings']={} if flag is None else {'voyahtune_install_mode':flag};self.write_state()
   for action in ['light','full']:
    with self.subTest(flag=flag,action=action):self.plan(action)
  self.state['failShell']='settings get global voyahtune_install_mode';self.write_state()
  self.plan('light');self.no_writes(self.calls())
 def test_full_light_and_repeat_preserve_data_and_shared_apks(self):
  installed=[]
  for action in ['full','light','light','full','remove','light']:
   with self.subTest(action=action):
    if installed and action!='remove':
     for package in ['ru.big.town.anative','ru.big.town.restoremode']:
      for parent in ['data/user/0','data/user_de/0']:
       p=self.device/parent/package/'settings-marker';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('keep')
    self.read_state();self.state['settings']['voyahtune_user_test']='keep';self.write_state()
    self.apply(self.plan(action));settings=self.read_state()['settings']
    if action=='remove':self.assertNotIn('voyahtune_install_mode',settings)
    else:
     self.assertEqual(settings['voyahtune_install_mode'],action)
     if installed:self.assert_app_data(True)
     if action=='light':self.assert_light_clean()
     installed.append(tuple((self.device/p).read_bytes() for p in ['system/priv-app/Native/Native.apk','data/app/ru.big.town.restoremode/base.apk']))
     self.assertEqual(settings['voyahtune_user_test'],'keep')
  self.assertTrue(all(apks==installed[0] for apks in installed))
 def test_legacy_light_soft_cleanup_preserves_settings_and_dns(self):
  self.seed_apps();self.state['settings']={'voyahtune_install_mode':'full','voyahtune_dock1':'com.example.player','voyahtune_keyboard_mode':'ru','voyahtune_fullscreen_apps':'com.example.player','open_voyah_apollo_master':'1'};self.write_state()
  expected=dict(self.state['settings']);expected['voyahtune_install_mode']='light'
  (self.device/'system/etc/init.logcat.sh').write_text('#!/system/bin/sh\n# init.logcat.sh Open Voyah:\n/system/bin/logcat -v threadtime\n')
  for path in ['data/local/bin/load.bin','data/local/bin/frida-inject','data/local/bin/fullscreen_client.js','data/local/bin/voyahtune-hook-manifest.json','data/local/tmp/voyahtune-hook-status.v1']:(self.device/path).write_text('old Full')
  dns=self.device/'data/local/open_voyah/qgdns/original';dns.parent.mkdir(parents=True);dns.write_text('saved DNS')
  self.apply(self.plan('light'));self.assert_app_data(True);self.assert_light_clean()
  self.assertEqual(self.read_state()['settings'],expected);self.assertEqual(dns.read_text(),'saved DNS')
  self.assertNotIn('Open Voyah:',(self.device/'system/etc/init.logcat.sh').read_text())
  self.assertFalse(any('pm uninstall' in (c['script'] or '') for c in self.calls()))
 def test_full_without_existing_loader_does_not_stop_missing_service(self):
  self.seed_apps();self.state['settings']['voyahtune_install_mode']='light'
  self.state['loader']='';self.state['failShell']='setprop ctl.stop voyahtune_load';self.write_state()
  result=self.apply(self.plan('full'))
  self.assertNotIn('diagnostic-warning',result.stdout)
  self.assertFalse(any('setprop ctl.stop voyahtune_load' in (c['script'] or '') for c in self.calls()))
  self.assertEqual(self.read_state()['settings']['voyahtune_install_mode'],'full')
 def test_unknown_mode_light_updates_without_question(self):
  self.seed_apps();self.state['settings'].clear();self.write_state()
  self.apply(self.plan('light'));self.assert_app_data(True);self.assert_light_clean()
  self.assertEqual(self.read_state()['settings']['voyahtune_install_mode'],'light')
 def test_soft_cleanup_failure_stops_before_apks_commit_and_reboot(self):
  self.seed_apps();self.state['settings']['voyahtune_install_mode']='full';self.state['failShell']='ACTIVE_RC=/system/etc/init/voyahtune.load.rc';self.write_state()
  native=(self.device/'system/priv-app/Native/Native.apk').read_bytes()
  result=self.apply(self.plan('light'),okay=False);self.assertNotEqual(result.returncode,0)
  self.assert_app_data(True);self.assertEqual(self.read_state()['settings']['voyahtune_install_mode'],'full')
  self.assertEqual(native,(self.device/'system/priv-app/Native/Native.apk').read_bytes())
  self.assertFalse(any(c['args'][0] in ['install','reboot'] for c in self.calls()))
  self.assertFalse(any('setprop ctl.start voyahtune_load' in (c['script'] or '') for c in self.calls()))
 def test_failed_mode_write_cannot_report_success(self):
  plan=self.plan('light');self.read_state();self.state['ignoreModeWrite']=True;self.write_state()
  result=self.apply(plan,okay=False);self.assertIn('MODE_WRITE_FAILED',result.stdout)
  self.assertNotIn('voyahtune_install_mode',self.read_state()['settings'])
  self.assertFalse(any(c['args']==['reboot'] for c in self.calls()))
 def test_failure_before_mode_commit_keeps_previous_mode(self):
  self.state['settings']['voyahtune_install_mode']='full';self.state['installError']='INSTALL_FAILED_INSUFFICIENT_STORAGE';self.write_state()
  result=self.apply(self.plan('light'),okay=False);self.assertNotEqual(result.returncode,0)
  self.assertEqual(self.read_state()['settings']['voyahtune_install_mode'],'full')
 def test_remove_failed_flag_delete_does_not_claim_success(self):
  self.state['settings']['voyahtune_install_mode']='full';self.state['ignoreModeDelete']=True;self.write_state()
  result=self.apply(self.plan('remove'),okay=False);self.assertIn('MODE_WRITE_FAILED',result.stdout)
  self.assertEqual(self.read_state()['settings']['voyahtune_install_mode'],'full')
 def test_failure_after_commit_keeps_committed_mode(self):
  self.state['failReboot']=True;self.write_state()
  result=self.apply(self.plan('light'),okay=False)
  self.assertNotEqual(result.returncode,0);self.assertEqual(self.read_state()['settings']['voyahtune_install_mode'],'light')
 def test_force_stop_failure_does_not_commit_mode(self):
  self.state['failForceStop']='ru.big.town.anative';self.write_state()
  result=self.apply(self.plan('light'),okay=False)
  self.assertNotEqual(result.returncode,0)
  self.assertNotIn('voyahtune_install_mode',self.read_state()['settings'])
  self.assertFalse(any(c['args'][0] in ['install','reboot'] for c in self.calls()))

def load_tests(loader, tests, pattern):
 suite=unittest.TestSuite()
 for name in ModeTests.__dict__:
  if name.startswith('test_') and (not loader.testNamePatterns or any(__import__('fnmatch').fnmatchcase(name,p) for p in loader.testNamePatterns)):suite.addTest(ModeTests(name))
 return suite
if __name__=='__main__':unittest.main(verbosity=2)
