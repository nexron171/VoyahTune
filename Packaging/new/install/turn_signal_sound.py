"""Transactional turn-signal WAV replacement on the OD QNX cluster."""

import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import struct
import subprocess

AUDIO = Path('/usr/share/audio')
STATE = Path('/var/data/voyahtune-turn-sound')
RESULT_PREFIX = 'VOYAHTUNE_TURN_SOUND_RESULT '


def progress(stage):
    if __name__ == '__main__':
        print('VOYAHTUNE_TURN_SOUND_PROGRESS ' + json.dumps({'stage': stage}), flush=True)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    require(not path.is_symlink() and path.is_file(), 'Invalid file: ' + str(path))
    require(path.stat().st_size <= 1024 * 1024, 'File too large')
    return path.read_bytes()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sync_directory(path):
    descriptor = os.open(str(path), os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write(path, data, metadata=None):
    temporary = path.with_name(path.name + '.voyahtune-new')
    require(not temporary.is_symlink(), 'Invalid temporary file')
    if temporary.exists():
        temporary.unlink()
    with temporary.open('xb') as output:
        output.write(data)
        if metadata is not None:
            os.fchown(output.fileno(), metadata.st_uid, metadata.st_gid)
            os.fchmod(output.fileno(), stat.S_IMODE(metadata.st_mode))
        else:
            os.fchmod(output.fileno(), 0o600)
        output.flush()
        os.fsync(output.fileno())
    os.replace(str(temporary), str(path))
    sync_directory(path.parent)


def save(path, value):
    write(path, json.dumps(value).encode())


def scale(data, volume):
    require(15 <= volume <= 100, 'Invalid volume')
    require(len(data) >= 44 and data[:4] == b'RIFF' and data[8:12] == b'WAVE', 'Invalid WAV')
    require(struct.unpack_from('<I', data, 4)[0] + 8 == len(data), 'Truncated WAV')
    output = bytearray(data)
    position = 12
    pcm = False
    found = False
    while position + 8 <= len(data):
        kind = data[position:position + 4]
        length = struct.unpack_from('<I', data, position + 4)[0]
        start = position + 8
        require(length <= len(data) - start, 'Truncated WAV chunk')
        if kind == b'fmt ':
            require(length >= 16, 'Invalid WAV format')
            encoding, channels, rate, byte_rate, alignment, bits = struct.unpack_from('<HHIIHH', data, start)
            require(encoding == 1 and channels in (1, 2) and bits == 16 and alignment == channels * 2, 'Expected PCM16')
            pcm = True
        elif kind == b'data':
            require(pcm and length > 0 and length % alignment == 0, 'Invalid PCM data')
            found = True
            for offset in range(start, start + length, 2):
                sample = struct.unpack_from('<h', data, offset)[0]
                struct.pack_into('<h', output, offset, round(sample * volume / 100))
        position = start + length + (length & 1)
    require(found, 'Missing PCM data')
    return bytes(output)


def remount(readonly):
    subprocess.run(['/bin/mount', '-ur' if readonly else '-uw', '/'], check=True)
    require(bool(os.statvfs(AUDIO).f_flag & os.ST_RDONLY) == readonly, 'Mount mode unchanged')


def targets(audio):
    result = {}
    for part in ('di', 'da'):
        matches = list(audio.glob('*' + part + '+3dB.wav'))
        require(len(matches) == 1, 'Unsupported turn-signal audio layout')
        read(matches[0])
        result[part] = matches[0]
    return result


def create_backup(path, data):
    # Exclusive creation preserves even an incomplete backup after a failed write.
    with path.open('xb') as output:
        os.fchmod(output.fileno(), 0o400)
        output.write(data)
        output.flush()
        os.fsync(output.fileno())
    sync_directory(path.parent)
    require(read(path) == data, 'Backup verification failed')


def originals(audio, state, create):
    progress('checking_backup')
    manifest = state / 'originals.json'
    if not manifest.exists():
        backups = [state / (part + '.wav') for part in ('di', 'da')]
        require(not any(path.exists() or path.is_symlink() for path in backups),
                'Incomplete original backup; existing files are preserved')
        require(not (state / 'pending.json').exists(), 'Missing recovery backup')
        selection = state / 'selection.json'
        require(not selection.exists() or not json.loads(read(selection)).get('enabled'),
                'Missing original backup for an enabled replacement')
        if not create:
            return {}
        sources = {part: (path, read(path)) for part, path in targets(audio).items()}
        for path, data in sources.values():
            scale(data, 100)
        record = {}
        for part, (path, data) in sources.items():
            record[part] = {'name': path.name, 'sha256': digest(data)}
            progress('backup_' + part)
            create_backup(state / (part + '.wav'), data)
        create_backup(manifest, json.dumps(record).encode())
    progress('verifying_backup')
    record = json.loads(read(manifest))
    require(set(record) == {'di', 'da'}, 'Invalid backup manifest')
    result = {}
    for part, entry in record.items():
        name = entry['name']
        require(Path(name).name == name and name.endswith(part + '+3dB.wav'), 'Invalid backup target')
        data = read(state / (part + '.wav'))
        require(digest(data) == entry['sha256'], 'Corrupt original audio backup')
        path = audio / name
        read(path)
        result[part] = (path, data)
    return result


def replace(original, replacement, state, selection):
    pending = state / 'pending.json'
    readonly = bool(os.statvfs(next(iter(original.values()))[0]).f_flag & os.ST_RDONLY)
    if pending.exists():
        readonly = json.loads(read(pending))['readOnly']
    save(pending, {'readOnly': readonly})
    try:
        progress('preparing_write')
        remount(False)
        for part, (path, backup) in original.items():
            progress(('write_' if replacement else 'restore_') + part)
            data = replacement.get(part, backup)
            write(path, data, path.stat())
            progress('verifying_' + part)
            require(read(path) == data, 'Audio verification failed')
        progress('saving_state')
        save(state / 'selection.json', selection)
    finally:
        progress('finishing')
        remount(readonly)
    pending.unlink()
    sync_directory(state)


def execute(action, sound='original', volume=60, sounds=None, audio=AUDIO, state=STATE):
    require(action in ('status', 'apply', 'restore'), 'Invalid action')
    require(not state.is_symlink(), 'Invalid state directory')
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (state / 'lock').open('a') as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        original = originals(audio, state, False)
        disabled = {'enabled': False, 'sound': 'original', 'volume': 100}
        if (state / 'pending.json').exists():
            require(original, 'Missing recovery backup')
            replace(original, {}, state, disabled)
        selection = state / 'selection.json'
        if action == 'status':
            progress('reading_state')
            return json.loads(read(selection)) if selection.exists() else disabled
        if action == 'restore':
            if original:
                replace(original, {}, state, disabled)
            else:
                save(selection, disabled)
            return disabled
        require(isinstance(sound, str) and 15 <= volume <= 100, 'Invalid selection')
        progress('preparing_audio')
        replacement = {part: scale(bytes.fromhex(sounds[part]), volume) for part in ('di', 'da')}
        original = originals(audio, state, True)
        enabled = {'enabled': True, 'sound': sound, 'volume': volume}
        try:
            replace(original, replacement, state, enabled)
        except Exception:
            replace(original, {}, state, disabled)
            raise
        return enabled


if __name__ == '__main__':
    try:
        result = execute(**REQUEST)
        result.update(ok=True, action=REQUEST['action'])
    except Exception as error:
        result = {'ok': False, 'error': str(error)}
    print(RESULT_PREFIX + json.dumps(result), flush=True)
