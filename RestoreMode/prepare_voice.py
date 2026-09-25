#!/usr/bin/env python3
"""Pinned offline voice assets and native filters. Run automatically by Gradle.

Build requirements: Python 3.11+, Rust stable with Android targets, Android NDK.
Downloads happen on the build machine only, never in the car.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "app/build/voice-deps"


def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def download(name, spec):
    dest = BUILD / "downloads" / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    if not dest.exists():
        print(f"Downloading {name}", flush=True)
        partial = dest.with_suffix(dest.suffix + ".part")
        with urllib.request.urlopen(spec["url"], timeout=120) as src, partial.open("wb") as out:
            shutil.copyfileobj(src, out)
        if hashlib.sha256(partial.read_bytes()).hexdigest() != spec["sha256"]:
            partial.unlink()
            raise RuntimeError(f"Checksum mismatch: {name}")
        partial.replace(dest)
    if hashlib.sha256(dest.read_bytes()).hexdigest() != spec["sha256"]:
        raise RuntimeError(f"Checksum mismatch: {dest}; remove it and retry")
    return dest


def extract(archive, destination):
    if destination.is_dir():
        return
    destination.mkdir(parents=True)
    try:
        with tarfile.open(archive) as src:
            for member in src.getmembers():
                parts = Path(member.name).parts[1:]
                if not parts:
                    continue
                member.name = str(Path(*parts))
                if member.issym() or member.islnk():
                    continue
                if not (destination / member.name).resolve().is_relative_to(destination.resolve()):
                    raise RuntimeError("Unsafe archive path")
                src.extract(member, destination, filter="data")
    except BaseException:
        shutil.rmtree(destination)
        raise


def prepare_assets():
    files = {name: download(name, spec) for name, spec in
             json.loads((ROOT / "voice-dependencies.json").read_text()).items()}
    assets = BUILD / "assets"
    # Replace generated assets so removed dependencies cannot survive incremental builds.
    if assets.exists():
        shutil.rmtree(assets)
    model = assets / "zipformer-ru-0.54-int8"
    model.mkdir(parents=True, exist_ok=True)
    for name in ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.vocab"]:
        shutil.copyfile(files[name], model / name)
    shutil.copyfile(files["silero_vad.onnx"], assets / "silero_vad.onnx")
    extract(files["df.tar.gz"], BUILD / "df")
    # Build libDF through our small wrapper rather than the upstream Python/demo workspace.
    (BUILD / "df/Cargo.toml").write_text('[workspace]\nmembers = ["libDF"]\n')
    licenses = assets / "voice-licenses"
    licenses.mkdir(exist_ok=True)
    for source, name in [(files["sherpa-LICENSE"], "sherpa-onnx-LICENSE"),
                         (files["silero-LICENSE"], "Silero-VAD-LICENSE"),
                         (files["onnxruntime-LICENSE"], "ONNX-Runtime-LICENSE"),
                         (files["tract-LICENSE-MIT"], "tract-LICENSE-MIT"),
                         (files["tract-LICENSE-APACHE"], "tract-LICENSE-APACHE"),
                         (files["vosk-README.md"], "zipformer-model-card.md"),
                         (BUILD / "df/LICENSE-MIT", "DeepFilterNet-LICENSE-MIT"),
                         (BUILD / "df/LICENSE-APACHE", "DeepFilterNet-LICENSE-APACHE")]:
        shutil.copyfile(source, licenses / name)


def native(ndk, abis):
    host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
    toolchain = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    targets = {"arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
               "x86_64": ("x86_64-linux-android", "x86_64-linux-android"),
               "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi"),
               "x86": ("i686-linux-android", "i686-linux-android")}
    jni = BUILD / "jniLibs"
    if jni.exists():
        shutil.rmtree(jni)
    for abi in abis:
        target, clang_target = targets[abi]
        output = BUILD / "jniLibs" / abi
        output.mkdir(parents=True, exist_ok=True)
        clang = toolchain / (clang_target + "30-clang")
        env = os.environ.copy()
        env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = str(clang)
        env["CC_" + target.replace("-", "_")] = str(clang)
        env["AR_" + target.replace("-", "_")] = str(toolchain / "llvm-ar")
        env["CARGO_TARGET_DIR"] = str(BUILD / "rust-target")
        env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
        run("cargo", "build", "--locked", "--release", "--target", target,
            "--manifest-path", ROOT / "voice-native/Cargo.toml", env=env)
        shutil.copyfile(BUILD / "rust-target" / target / "release/libvoyah_df.so", output / "libvoyah_df.so")
        run(toolchain / "llvm-strip", output / "libvoyah_df.so")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--abis", default="arm64-v8a,x86_64")
    args = parser.parse_args()
    prepare_assets()
    if args.ndk:
        native(args.ndk, args.abis.split(","))
