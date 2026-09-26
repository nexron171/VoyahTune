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
