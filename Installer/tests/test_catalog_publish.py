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
 def test_head_mode_updates_without_payload_download(self):
  with tempfile.TemporaryDirectory() as d:
   index=Path(d)/'index.json';index.write_text('{"releases":[]}')
   entry=Path(d)/'entry.json';entry.write_text(json.dumps(self.entry()))
   normalized=json.dumps({'releases':[self.entry()]})
   with patch.object(u.subprocess,'check_output',return_value=normalized),patch.object(u,'verify_remote') as remote,patch.object(u,'verify_remote_head') as head:
    self.assertTrue(u.update(index,entry,Path('builder'),False,verify_head=True))
    remote.assert_not_called();head.assert_called_once_with(self.entry())
   self.assertEqual(json.loads(index.read_text())['releases'],[self.entry()])
 def test_head_failure_preserves_index(self):
  with tempfile.TemporaryDirectory() as d:
   index=Path(d)/'index.json';index.write_text('{"releases":[]}');before=index.read_bytes()
   entry=Path(d)/'entry.json';entry.write_text(json.dumps(self.entry()))
   with patch.object(u.subprocess,'check_output',return_value='{}'),patch.object(u,'verify_remote_head',side_effect=ValueError('403')):
    with self.assertRaises(ValueError):u.update(index,entry,Path('builder'),False,verify_head=True)
   self.assertEqual(index.read_bytes(),before)
 def test_head_uses_only_head_and_checks_both_headers(self):
  from unittest.mock import MagicMock
  response=MagicMock();response.__enter__.return_value=response
  response.headers={'Content-Length':'10','x-amz-meta-sha256':'a'*64}
  opener=MagicMock();opener.open.return_value=response
  with patch.object(u.urllib.request,'build_opener',return_value=opener):
   u.verify_remote_head(self.entry())
   self.assertEqual(opener.open.call_args.args[0].get_method(),'HEAD')
   response.read.assert_not_called()
   for headers in ({'Content-Length':'11','x-amz-meta-sha256':'a'*64},{'Content-Length':'10'}):
    response.headers=headers
    with self.assertRaises(ValueError):u.verify_remote_head(self.entry())
if __name__=='__main__':unittest.main()
