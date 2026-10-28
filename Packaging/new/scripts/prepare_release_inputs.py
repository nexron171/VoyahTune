#!/usr/bin/env python3
"""Prepare the common runtime file recipe consumed by USB and OTA."""

import hashlib
import json
from pathlib import Path

SOLUTION = Path(__file__).resolve().parents[1]
REPOSITORY = SOLUTION.parents[1]
RUNTIME_ROOT = "/data/local/bin/voyahtune-new"


def prepare():
    runtime = SOLUTION / "build/runtime"
    manifest = json.loads((runtime / "runtime-manifest.json").read_text())
    for name, expected in manifest["sha256"].items():
        if hashlib.sha256((runtime / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Runtime hash mismatch: {name}")
    files = []
    sources = []

    def add(name, source, destination, mode, phase="files"):
        files.append({"artifact": name, "destination": destination, "mode": mode, "phase": phase})
        sources.append({"name": name, "source": str(source.relative_to(REPOSITORY))})

    for source in sorted(runtime.iterdir()):
        if source.is_file():
            name = "voyahtune-new-" + source.name
            mode = 0o755 if source.name in ("voyahtune-inject", "voyahtune-inject-arm", "voyahtune-loader", "stop-loader.sh") else 0o644
            add(name, source, f"{RUNTIME_ROOT}/{source.name}", mode)
    for name, destination, mode, phase in [
        ("voyahtune.load.rc", "/system/etc/init/voyahtune.load.rc", 0o644, "boot-hooks"),
        ("voyahtune.load.sh", "/system/etc/init.voyahtune.load.sh", 0o755, "boot-hooks"),
    ]:
        add(name, SOLUTION / "system" / name, destination, mode, phase)

    retired = (SOLUTION / "config/retired-files.txt").read_text().splitlines()
    overlay = {
        "schema": 1,
        "infrastructure": "unified",
        "files": files,
        "directories": [{"path": RUNTIME_ROOT, "mode": 0o755}],
        "removeFiles": retired,
        "artifacts": sources,
    }
    destination = SOLUTION / "build/release-inputs.json"
    destination.write_text(json.dumps(overlay, indent=2) + "\n")
    print(destination)


if __name__ == "__main__":
    prepare()
