import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('turn_sound', Path(__file__).parents[1] / 'install/turn_signal_sound.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ASSETS = Path(__file__).parents[1] / 'turn-sounds'


class TurnSoundTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.audio = root / 'audio'
        self.audio.mkdir()
        self.state = root / 'state'
        self.originals = {}
        for part in ['di', 'da']:
            data = (ASSETS / ('original.' + part + '.wav')).read_bytes()
            path = self.audio / ('turn_' + part + '+3dB.wav')
            path.write_bytes(data)
            self.originals[path] = data
        self.mount = patch.object(MODULE, 'remount')
        self.mount.start()
        self.addCleanup(self.mount.stop)

    def run_action(self, action, sound='Tesla', volume=60):
        sounds = {part: (ASSETS / (sound + '.' + part + '.wav')).read_bytes().hex() for part in ['di', 'da']}
        return MODULE.execute(action, sound, volume, sounds, self.audio, self.state)

    def test_disabled_by_default_and_restore_without_backup_is_noop(self):
        self.assertFalse(self.run_action('status')['enabled'])
        self.run_action('restore')
        for path, original in self.originals.items():
            self.assertEqual(path.read_bytes(), original)

    def test_every_sound_and_volume_restores_exact_original_bytes(self):
        for path in ASSETS.glob('*.di.wav'):
            for volume in [15, 60, 100]:
                self.run_action('apply', path.name[:-7], volume)
                self.assertTrue(self.run_action('status')['enabled'])
                self.run_action('restore')
                for target, original in self.originals.items():
                    self.assertEqual(target.read_bytes(), original)
                self.assertFalse(self.run_action('status')['enabled'])

    def test_repeated_apply_does_not_overwrite_backup(self):
        self.run_action('apply')
        self.run_action('apply', 'BMW', 15)
        self.run_action('restore')
        self.run_action('restore')
        for path, original in self.originals.items():
            self.assertEqual(path.read_bytes(), original)

    def test_corrupt_backup_blocks_mutation(self):
        self.run_action('apply')
        backup = next(self.state.glob('*.wav'))
        backup.chmod(0o600)
        backup.write_bytes(b'corrupt')
        with self.assertRaises(ValueError):
            self.run_action('restore')
        with self.assertRaises(ValueError):
            self.run_action('status')

    def test_incomplete_backup_is_never_overwritten(self):
        self.state.mkdir()
        backup = self.state / 'di.wav'
        backup.write_bytes(b'first backup must survive')
        for action in ['apply', 'restore']:
            with self.assertRaises(ValueError):
                self.run_action(action)
        self.assertEqual(backup.read_bytes(), b'first backup must survive')
        for path, original in self.originals.items():
            self.assertEqual(path.read_bytes(), original)

    def test_missing_manifest_after_apply_never_replaces_originals(self):
        self.run_action('apply')
        backups = {path: path.read_bytes() for path in self.state.glob('*.wav')}
        (self.state / 'originals.json').unlink()
        with self.assertRaises(ValueError):
            self.run_action('apply', 'BMW')
        for path, data in backups.items():
            self.assertEqual(path.read_bytes(), data)

    def test_backup_failure_does_not_enable_or_touch_audio(self):
        with patch.object(MODULE, 'create_backup', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.run_action('apply')
        self.assertFalse((self.state / 'selection.json').exists())
        for path, original in self.originals.items():
            self.assertEqual(path.read_bytes(), original)

    def test_existing_backup_rejects_exclusive_creation(self):
        self.state.mkdir()
        backup = self.state / 'di.wav'
        backup.write_bytes(b'original')
        with self.assertRaises(FileExistsError):
            MODULE.create_backup(backup, b'replacement')
        self.assertEqual(backup.read_bytes(), b'original')

    def test_sync_failure_blocks_audio_changes(self):
        with patch.object(MODULE.os, 'fsync', side_effect=OSError('sync failed')):
            with self.assertRaises(OSError):
                self.run_action('apply')
        self.assertFalse((self.state / 'selection.json').exists())
        for path, original in self.originals.items():
            self.assertEqual(path.read_bytes(), original)

    def test_interrupted_replacement_recovers_and_keeps_backups(self):
        class PowerLoss(BaseException):
            pass

        real_write = MODULE.write
        def interrupt_second_file(path, data, metadata=None):
            if path.parent == self.audio and 'da+' in path.name:
                raise PowerLoss()
            return real_write(path, data, metadata)

        with patch.object(MODULE, 'write', side_effect=interrupt_second_file):
            with self.assertRaises(PowerLoss):
                self.run_action('apply')
        backups = {path: (path.read_bytes(), path.stat().st_ino)
                   for path in self.state.iterdir() if path.name in ('di.wav', 'da.wav', 'originals.json')}
        self.assertFalse(self.run_action('status')['enabled'])
        self.run_action('apply', 'BMW', 15)
        self.run_action('restore')
        for path, (data, inode) in backups.items():
            self.assertEqual((path.read_bytes(), path.stat().st_ino), (data, inode))
        for path, original in self.originals.items():
            self.assertEqual(path.read_bytes(), original)

    def test_progress_reports_verified_backup_before_replacement_and_restore(self):
        stages = []
        # Inspect backup validity without recursively observing the inspection itself.
        def observe_write(stage):
            stages.append(stage)
            if stage.startswith('write_') or stage.startswith('restore_'):
                with patch.object(MODULE, 'progress'):
                    self.assertEqual(len(MODULE.originals(self.audio, self.state, False)), 2)

        with patch.object(MODULE, 'progress', side_effect=observe_write):
            self.run_action('apply')
        for stage in ['backup_di', 'backup_da', 'verifying_backup', 'write_di', 'write_da', 'finishing']:
            self.assertIn(stage, stages)
        self.assertLess(stages.index('backup_da'), stages.index('write_di'))
        self.assertLess(stages.index('verifying_backup'), stages.index('write_di'))
        stages.clear()
        with patch.object(MODULE, 'progress', side_effect=observe_write):
            self.run_action('restore')
        self.assertIn('restore_di', stages)
        self.assertIn('restore_da', stages)
        self.assertNotIn('backup_di', stages)

    def test_invalid_wav_and_volume_rejected_before_write(self):
        for volume in [0, 101]:
            with self.assertRaises(ValueError):
                self.run_action('apply', volume=volume)
        with self.assertRaises(ValueError):
            MODULE.scale(b'bad', 60)
        for path, original in self.originals.items():
            self.assertEqual(path.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
