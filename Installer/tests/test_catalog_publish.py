import importlib.util, json, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('catalog_update',Path(__file__).resolve().parents[1]/'scripts/update-catalog.py')
u=importlib.util.module_from_spec(spec);spec.loader.exec_module(u)
class CatalogPublishTests(unittest.TestCase):
 def test_existing_version_is_immutable_and_repeating_is_idempotent(self):
  e=dict(version='1.0.0',payload={'sha256':'a'},requirements={'minInstallerVersion':'1.0.0'})
  index={'releases':[]};self.assertTrue(u.merge(index,e));self.assertFalse(u.merge(index,e.copy()))
  with self.assertRaises(ValueError):u.merge(index,{**e,'payload':{'sha256':'b'}})
 def test_ota_marker_can_change_without_replacing_published_archive(self):
  entry=dict(version='1.0.0',payload={'sha256':'a'},requirements={'minInstallerVersion':'1.0.0'})
  index={'releases':[entry.copy()]}
  self.assertTrue(u.merge(index,{**entry,'ota':True}))
  self.assertIs(index['releases'][0]['ota'],True)
  self.assertFalse(u.merge(index,entry))
  self.assertIs(index['releases'][0]['ota'],True)
  self.assertFalse(u.merge(index,{**entry,'ota':True}))
  with self.assertRaises(ValueError):u.merge(index,{**entry,'ota':False,'payload':{'sha256':'b'}})
  self.assertIs(index['releases'][0]['ota'],True)
  self.assertTrue(u.merge(index,{**entry,'ota':False}))
  self.assertIs(index['releases'][0]['ota'],False)
  self.assertEqual(index['releases'][0]['payload'],entry['payload'])
 def test_ota_marker_rejects_non_boolean_values(self):
  for invalid in ('true',1,None):
   with self.assertRaises(ValueError):u.merge({'releases':[]},dict(version='1.0.0',ota=invalid))
 def test_remote_failure_never_changes_index(self):
  with tempfile.TemporaryDirectory() as d:
   index=Path(d)/'index.json';index.write_text('{"releases":[]}');before=index.read_bytes()
   entry=Path(d)/'entry.json';entry.write_text(json.dumps(dict(version='1.0.0',payload={},requirements={})))
   with patch.object(u.subprocess,'check_output',return_value='{}'),patch.object(u,'verify_remote',side_effect=ValueError('404')):
    with self.assertRaises(ValueError):u.update(index,entry,Path('builder'),True)
   self.assertEqual(index.read_bytes(),before)
 def test_local_only_check_does_not_publish(self):
  with tempfile.TemporaryDirectory() as d:
   index=Path(d)/'index.json';index.write_text('{"releases":[]}')
   with patch.object(u.subprocess,'check_output',return_value='{}'),patch.object(u,'verify_remote') as remote:
    self.assertFalse(u.update(index,None,Path('builder'),False));remote.assert_not_called()
if __name__=='__main__':unittest.main()
