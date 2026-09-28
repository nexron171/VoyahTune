import importlib.util, json, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('catalog_update',Path(__file__).resolve().parents[1]/'scripts/update-catalog.py')
u=importlib.util.module_from_spec(spec);spec.loader.exec_module(u)
class CatalogPublishTests(unittest.TestCase):
 def entry(self):
  return dict(version='3.15.0',url='https://example.org/payload.zip',size=10,sha256='a'*64)
 def test_existing_version_is_immutable_and_repeating_is_idempotent(self):
  e=self.entry();index={'releases':[]}
  self.assertTrue(u.merge(index,e));self.assertFalse(u.merge(index,e.copy()))
  for changed in ({'sha256':'b'*64},{'size':11}):
   with self.assertRaises(ValueError):u.merge(index,{**e,**changed})
 def test_mirror_url_can_change_without_replacing_archive(self):
  e=self.entry();index={'releases':[e.copy()]}
  self.assertTrue(u.merge(index,{**e,'url':'https://mirror.example.org/payload.zip'}))
  self.assertEqual(index['releases'][0]['sha256'],e['sha256'])
 def test_removed_metadata_and_missing_fields_are_rejected(self):
  for key in ('signature','metadata','ota','otaMetadata'):
   with self.assertRaises(ValueError):u.merge({'releases':[]},{**self.entry(),key:True})
  e=self.entry();del e['sha256']
  with self.assertRaises(ValueError):u.merge({'releases':[]},e)
 def test_remote_failure_never_changes_index(self):
  with tempfile.TemporaryDirectory() as d:
   index=Path(d)/'index.json';index.write_text('{"releases":[]}');before=index.read_bytes()
   entry=Path(d)/'entry.json';entry.write_text(json.dumps(self.entry()))
   with patch.object(u.subprocess,'check_output',return_value='{}'),patch.object(u,'verify_remote',side_effect=ValueError('404')):
    with self.assertRaises(ValueError):u.update(index,entry,Path('builder'),True)
   self.assertEqual(index.read_bytes(),before)
 def test_local_only_check_does_not_publish(self):
  with tempfile.TemporaryDirectory() as d:
   index=Path(d)/'index.json';index.write_text('{"releases":[]}')
   with patch.object(u.subprocess,'check_output',return_value='{}'),patch.object(u,'verify_remote') as remote:
    self.assertFalse(u.update(index,None,Path('builder'),False));remote.assert_not_called()
if __name__=='__main__':unittest.main()
