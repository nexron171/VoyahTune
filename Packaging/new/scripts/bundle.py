#!/usr/bin/env python3
"""Assemble the unified ARM native/Java runtime from compiled components."""

import argparse
import hashlib
import json
import re
import shutil
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPONENTS = ("libvoyahtune-agent.so", "libvoyahtune-agent-arm.so", "agents.dex",
              "voyahtune-inject", "voyahtune-inject-arm", "voyahtune-loader")
EXECUTABLES = {"voyahtune-inject", "voyahtune-inject-arm", "voyahtune-loader", "stop-loader.sh"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def arm_component(path, arm32=False):
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"Missing regular native component: {path}")
    with path.open("rb") as source:
        header = source.read(20)
    elf_class, machine = (1, 40) if arm32 else (2, 183)
    if (len(header) != 20 or header[:6] != b"\x7fELF" + bytes((elf_class, 1))
            or int.from_bytes(header[18:20], "little") != machine):
        raise ValueError(f"Android ARM{32 if arm32 else 64} ELF component required: {path}")


def validate_destination(destination):
    if destination.is_symlink():
        raise ValueError("Runtime output must not be a symlink")
    if not destination.exists():
        return
    if not destination.is_dir():
        raise ValueError("Runtime output must be a directory")
    if not any(destination.iterdir()):
        return
    known = set(COMPONENTS) | {"stop-loader.sh", "agents.json", "runtime-manifest.json"}
    known.update(asset.name for asset in (ROOT / "config").glob("voyahtune_*.json"))
    if not (destination / "voyahtune-loader").is_file() or any(
        not item.is_file() or item.is_symlink() or item.name not in known
        for item in destination.iterdir()
    ):
        raise ValueError("Refusing to replace unrelated output directory")


def component_metadata(components):
    components = Path(components)
    native_id = (components / "native-id.txt").read_text().strip()
    if not re.fullmatch(r"native-[a-f0-9]{64}", native_id):
        raise ValueError("Missing compiled native identity")
    assets = sorted((ROOT / "config").glob("voyahtune_*.json"))
    sources = {name: components / name for name in COMPONENTS if name != "voyahtune-loader"}
    sources.update({asset.name: asset for asset in assets})
    sources["stop-loader.sh"] = ROOT / "system/stop-loader.sh"
    files = {name: digest(path) for name, path in sorted(sources.items())}
    code_inputs = {name: files[name] for name in ["agents.dex", *[asset.name for asset in assets]]}
    runtime_id = "java-" + hashlib.sha256(
        json.dumps(code_inputs, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    return {"schema": 2, "runtime": "unified", "androidApi": 30,
            "nativeId": native_id, "runtimeId": runtime_id, "sha256": files}


def loader_overlay(components, directory):
    metadata = component_metadata(components)
    directory = Path(directory).resolve()
    generated = directory / "runtime_components.go"
    hashes = ",\n".join(
        json.dumps(name) + ": " + json.dumps(value)
        for name, value in metadata["sha256"].items()
    )
    generated.write_text(
        'package main\n\nvar embeddedNativeCode = nativeCode{\n'
        'Schema: 2, Runtime: "unified", AndroidAPI: 30,\n'
        f'NativeID: {json.dumps(metadata["nativeId"])}, RuntimeID: {json.dumps(metadata["runtimeId"])},\n'
        'SHA256: map[string]string{\n' + hashes + ',\n},\n}\n'
    )
    overlay = directory / "loader-overlay.json"
    overlay.write_text(json.dumps({"Replace": {
        str(ROOT / "loader/runtime_components.go"): str(generated)
    }}))
    return overlay


def bundle(destination, components):
    destination = Path(destination).absolute()
    components = Path(components).resolve()
    validate_destination(destination)
    for name in COMPONENTS:
        path = components / name
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing compiled component: {path}")
        if name != "agents.dex":
            arm_component(path, name in ("libvoyahtune-agent-arm.so", "voyahtune-inject-arm"))
    dex = components / "agents.dex"
    with dex.open("rb") as source:
        if source.read(8) not in (b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0"):
            raise ValueError("Android 11 agent DEX required")
    component_metadata(components)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-runtime-", dir=destination.parent) as temporary:
        stage = Path(temporary) / "runtime"
        stage.mkdir(mode=0o755)
        for name in COMPONENTS:
            shutil.copyfile(components / name, stage / name)
        shutil.copyfile(ROOT / "system/stop-loader.sh", stage / "stop-loader.sh")
        assets = sorted((ROOT / "config").glob("voyahtune_*.json"))
        for asset in assets:
            shutil.copyfile(asset, stage / asset.name)
        for path in stage.iterdir():
            path.chmod(0o755 if path.name in EXECUTABLES else 0o644)
        if destination.exists():
            shutil.rmtree(destination)
        stage.rename(destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/runtime")
    parser.add_argument("--components", type=Path, default=ROOT / "build/native-components")
    arguments = parser.parse_args()
    bundle(arguments.output, arguments.components)
