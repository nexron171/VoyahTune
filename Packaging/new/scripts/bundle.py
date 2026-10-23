#!/usr/bin/env python3
"""Assemble deterministic standalone Frida 16 agents without a JavaScript toolchain."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = ROOT.parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bundle(destination):
    destination.mkdir(parents=True, exist_ok=True)
    bundles = json.loads((ROOT / 'config/bundles.json').read_text())
    manifest = json.loads((ROOT / 'config/agents.json').read_text())
    for name, spec in bundles.items():
        sources = ['runtime/adapters.js', 'runtime/bootstrap.js'] + spec['sources']
        content = ['var VOYAH_READY_MARKERS = ' + json.dumps(spec['ready']) + ';',
                   'var VoyahLauncherAdapters = {};']
        for source in sources:
            path = ROOT / source
            content.append('// Source: ' + source + '\n' + path.read_text())
        content.append('VoyahBootstrap.attach();\n')
        (destination / name).write_text('\n'.join(content))
    shutil.copyfile(ROOT / 'config/agents.json', destination / 'agents.json')
    shutil.copyfile(ROOT / 'system/stop-loader.sh', destination / 'stop-loader.sh')
    (destination / 'stop-loader.sh').chmod(0o755)
    for path in (ROOT / 'config').glob('voyahtune_*.json'):
        shutil.copyfile(path, destination / path.name)
    tool = json.loads((ROOT / 'config/frida.json').read_text())
    source = REPOSITORY_ROOT / tool['source']
    if digest(source) != tool['sha256']:
        raise ValueError('Frida source hash differs from the reviewed tool')
    shutil.copyfile(source, destination / 'frida-inject')
    (destination / 'frida-inject').chmod(0o755)
    for agent in manifest['agents']:
        if agent['script'] not in bundles:
            raise ValueError('Unknown agent bundle: ' + agent['script'])
    files = {p.name: digest(p) for p in sorted(destination.iterdir()) if p.is_file() and p.name != 'runtime-manifest.json'}
    output = {'schema': 1, 'runtime': 'unified', 'androidApi': 30,
              'nativeContract': 'driveHookV2', 'nativeRestorePolicy': 'acc-hooks',
              'installRoot': '/data/local/bin/voyahtune-new', 'sha256': files}
    (destination / 'runtime-manifest.json').write_text(json.dumps(output, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/runtime')
    bundle(parser.parse_args().output.resolve())
