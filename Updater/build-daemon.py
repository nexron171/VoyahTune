#!/usr/bin/env python3
"""Build the independent root executable with the existing Android NDK/Rust toolchain."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], default='arm64-v8a')
    args = parser.parse_args()
    targets = {'arm64-v8a': 'aarch64-linux-android', 'x86_64': 'x86_64-linux-android'}
    target = targets[args.abi]
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64', 'Windows': 'windows-x86_64'}[platform.system()]
    suffix = '.cmd' if os.name == 'nt' else ''
    toolchain = args.ndk.resolve() / 'toolchains/llvm/prebuilt' / host / 'bin'
    clang = toolchain / (target + '30-clang' + suffix)
    if not clang.is_file(): parser.error(f'NDK compiler not found: {clang}')
    env = os.environ.copy()
    executable = 'cargo.exe' if os.name == 'nt' else 'cargo'
    cargo = shutil.which(executable)
    if not cargo:
        candidate = ROOT.parent / 'Releases/cache/cargo/bin' / executable
        if not candidate.is_file(): parser.error('Rust cargo not found; see Docs/releasing.md')
        cargo = str(candidate)
    cached = ROOT.parent / 'Releases/cache/cargo'
    if Path(cargo).parent == cached / 'bin':
        env.setdefault('CARGO_HOME', str(cached))
        env.setdefault('RUSTUP_HOME', str(ROOT.parent / 'Releases/cache/rustup'))
    env['CARGO_TARGET_' + target.upper().replace('-', '_') + '_LINKER'] = str(clang)
    env['CARGO_TARGET_DIR'] = str(ROOT / 'build/rust')
    subprocess.run([cargo, 'build', '--locked', '--offline', '--release', '--target', target,
                    '--manifest-path', str(ROOT / 'daemon/Cargo.toml')], cwd=ROOT, env=env, check=True)
    output = ROOT / 'build/daemon' / args.abi
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / 'build/rust' / target / 'release/voyahtune-updater', output / 'voyahtune-updater')
    print(output / 'voyahtune-updater')

if __name__ == '__main__': main()
