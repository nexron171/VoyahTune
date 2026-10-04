#!/usr/bin/env python3
"""Build signed A/B payload fixtures for PI and OD; never publish them."""
import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--infrastructure', choices=['pi', 'od'], help='Default: build both independently')
args = parser.parse_args()
for infrastructure in ([args.infrastructure] if args.infrastructure else ['pi', 'od']):
    work = ROOT / 'Releases/build/installer-remake-acceptance' / infrastructure
    source = work / 'source'
    source.mkdir(parents=True, exist_ok=True)
    shutil.copytree(ROOT / 'Packaging', source / 'Packaging', dirs_exist_ok=True)
    for name in ['Native', 'RestoreMode', 'Updater', 'RunYN']:
        path = source / name
        if path.is_symlink():
            path.unlink()
        shutil.copytree(ROOT / name, path, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns('build', '.gradle', '.kotlin', 'release', 'debug', 'target'))
    for name in ['SharedAndroid', 'Installer']:
        path = source / name
        if not path.exists():
            path.symlink_to(ROOT / name, target_is_directory=True)
    spec = source / 'Packaging/installer/payload-spec.json'
    base = json.loads(spec.read_text())
    inject = source / 'Packaging' / infrastructure / 'inject'
    for label, version in [('A', '3.13.0'), ('B', '3.13.1')]:
        recipe = json.loads(json.dumps(base))
        for filename in ['voyahtune_acceptance_old.json', 'voyahtune_acceptance_new.json']:
            (inject / filename).unlink(missing_ok=True)
        filename = 'voyahtune_acceptance_' + ('old' if label == 'A' else 'new') + '.json'
        (inject / filename).write_text(json.dumps({'fixture': label}))
        recipe['files'].append(dict(artifact=filename, destination='/data/local/bin/' + filename, mode=420, phase='files'))
        if label == 'B':
            recipe['removeFiles'].append('/data/local/bin/voyahtune_acceptance_old.json')
            recipe['directories'] = [dict(path='/data/local/bin/voyahtune_fixture', mode=493)]
            recipe['attributes'] = [dict(path='/data/local/bin/' + filename, mode=493)]
        spec.write_text(json.dumps(recipe))
        subprocess.run([str(ROOT / 'Installer/target/release/installer-build'), 'build',
                        '--infrastructure', infrastructure, '--root', str(source), '--version', version + '-' + infrastructure,
                        '--revision', f'acceptance-{infrastructure}-{label}', '--output', str(work / label)],
                       env={**os.environ, 'VOYAH_INFRASTRUCTURE': infrastructure}, check=True)
