#!/usr/bin/env python3
"""Build standalone desktop installers from source with their complete payload embedded."""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def run(args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def zip_tree(folder, output):
    # Preserve Unix execute bits and .app symlinks; do not dereference symlinks.
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(folder.rglob('*')):
            relative = str(path.relative_to(folder.parent))
            if path.is_symlink():
                info = zipfile.ZipInfo(relative)
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(info, os.readlink(path))
            elif path.is_file():
                archive.write(path, relative)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError(f'Повреждён новый ZIP: {output}')


def publish_outputs(pairs):
    """Replace completed outputs; keep the previous release on a publication error."""
    pairs = [(Path(source), Path(dest)) for source, dest in pairs]
    for source, dest in pairs:
        if not source.is_dir():
            raise ValueError(f'Missing completed output: {source}')
        dest.parent.mkdir(parents=True, exist_ok=True)
    backup = Path(tempfile.mkdtemp(prefix='.release-previous-', dir=pairs[0][1].parent))
    saved, published = [], []
    try:
        for index, (source, dest) in enumerate(pairs):
            if dest.exists() or dest.is_symlink():
                old = backup / str(index)
                dest.rename(old)
                saved.append((old, dest))
            source.rename(dest)
            published.append((source, dest))
    except BaseException:
        # Preserve backup on a rollback failure for manual recovery.
        for source, dest in reversed(published):
            dest.rename(source)
        for old, dest in reversed(saved):
            old.rename(dest)
        backup.rmdir()
        raise
    shutil.rmtree(backup)


def main():
    import tomllib
    parser = argparse.ArgumentParser(description='Build standalone installers with embedded Full/Light payload (macOS build host).')
    parser.add_argument('version')
    parser.add_argument('--installers', action='store_true', help='Build standalone installers; all platforms unless selected below')
    for flag in ['mac', 'windows', 'linux']:
        parser.add_argument('--'+flag, action='store_true', help='Build only selected installer platforms (flags may be combined)')
    parser.add_argument('--no-build', action='store_true', help='Reuse matching Android APKs; desktop installers are still rebuilt')
    parser.add_argument('--no-zip', action='store_true', help='Only build and verify the internal payload')
    parser.add_argument('--revision', help='Defaults to Git HEAD plus -dirty if modified')
    args = parser.parse_args()
    selected = [name for name, enabled in [('macos', args.mac), ('windows', args.windows), ('linux', args.linux)] if enabled] or ['macos', 'windows', 'linux']
    version = args.version.removeprefix('v')
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?', version):
        parser.error('Нужна SemVer-версия, например 3.3.0')
    if not args.no_zip and platform.system() != 'Darwin':
        parser.error('Сборка релиза установщиков выполняется из macOS. Нативная сборка одной ОС: Installer/scripts/build.mjs --payload DIRECTORY.')
    build = ROOT / 'Releases/build'
    build.mkdir(parents=True, exist_ok=True)
    destination = ROOT / 'Releases/dist' / f'VoyahTune-{version}-installers'
    payload_output = build / f'installer-payload-{version}'
    revision = args.revision or subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip() + ('-dirty' if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT) else '')
    env = os.environ.copy()
    cached = ROOT / 'Releases/cache/cargo'
    if not env.get('CARGO_HOME') and cached.exists():
        env.update(CARGO_HOME=str(cached),RUSTUP_HOME=str(ROOT/'Releases/cache/rustup'))
        env['PATH'] = str(cached/'bin') + os.pathsep + env['PATH']
    env['RUSTUP_TOOLCHAIN'] = tomllib.loads((ROOT/'Installer/rust-toolchain.toml').read_text())['toolchain']['channel']
    target = Path(env.get('CARGO_TARGET_DIR', ROOT/'Installer/target')).resolve()
    builder = target/'release'/('installer-build.exe' if os.name=='nt' else 'installer-build')
    lock = build / f'.release-{version}.lock'
    lock.mkdir()
    try:
        with tempfile.TemporaryDirectory(prefix='.release-',dir=build) as temporary:
            work = Path(temporary)
            for script in ['test_android11_package_lifecycle.sh','test_saved_config_startup_wake.sh','test_keyboard_modes.sh','test_hook_status.sh','test_app_client.sh','test_mapkit_dpi_client.sh']:
                run(['sh',ROOT/'Packaging/tests'/script])
            run(['bash',ROOT/'Utils/android11-oem-stubs/tests/static-checks.sh'])
            run(['cargo','build','--locked','--release','--manifest-path',ROOT/'Installer/Cargo.toml','-p','installer-build'],env=env,cwd=ROOT)
            payload = work/'payload'
            run([builder,'--root',ROOT,'--version',version,'--revision',revision,'--output',payload,*(['--skip-android'] if args.no_build else [])],env=env)
            packages = work/'packages'
            packages.mkdir()
            if not args.no_zip:
                compiled = work/'compiled'
                run([ROOT/'Installer/scripts/build-all-macos.sh','--payload',payload,'--output',compiled,*['--'+('mac' if name=='macos' else name) for name in selected]],env=env)
                record = json.loads((compiled/'build-info.json').read_text())
                if record['releaseVersion'] != version or record['payloadSha256'] != sha(payload/'manifest.json'):
                    raise ValueError('Собранные установщики относятся к другому комплекту')
                if set(record['platforms']) != set(selected):
                    raise ValueError('Список собранных платформ не совпадает с выбранным')
                for os_name, entry in record['platforms'].items():
                    tool = compiled/entry['file']
                    if sha(tool) != entry['sha256']:
                        raise ValueError(f'Повреждён установщик {os_name}')
                    folder = work/f'VoyahTune-{version}-{os_name}'
                    folder.mkdir()
                    if os_name == 'macos':
                        with tarfile.open(tool) as archive: archive.extractall(folder,filter='data')
                    else:
                        filename = 'VoyahTune-Installer.exe' if os_name=='windows' else 'VoyahTune-Installer.run'
                        shutil.copy2(tool,folder/filename)
                    (folder/'README.txt').write_text('Распакуйте ZIP и запустите установщик. Full, Light, удаление, ADB и все файлы уже внутри.\nLinux: chmod +x VoyahTune-Installer.run; ./VoyahTune-Installer.run\nCLI Linux: ./VoyahTune-Installer.run --cli --help\n')
                    zip_tree(folder,packages/f'{folder.name}.zip')
                (packages/'SHA256SUMS').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in sorted(packages.glob('*.zip'))))
                (packages/'release.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
            destination.parent.mkdir(parents=True,exist_ok=True)
            outputs = [(payload, payload_output)]
            if not args.no_zip:
                outputs.append((packages, destination))
            publish_outputs(outputs)
            print(f'Готово: {payload_output}' if args.no_zip else f'Готово: {destination}\nПлатформы: {", ".join(selected)}\nСлужебный payload: {payload_output}')
    finally:
        lock.rmdir()


if __name__ == '__main__':
    main()
