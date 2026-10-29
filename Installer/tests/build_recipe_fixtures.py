#!/usr/bin/env python3
"""Build signed unified A/B payloads for explicit integration runs; never publish."""

import json
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    work = ROOT / "Releases/build/installer-remake-acceptance/unified"
    source = work / "source"
    source.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        ROOT / "Packaging",
        source / "Packaging",
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("build", "cache", "__pycache__"),
    )
    for name in ["Native", "RestoreMode", "Updater", "RunYN"]:
        path = source / name
        if path.is_symlink():
            path.unlink()
        shutil.copytree(
            ROOT / name,
            path,
            dirs_exist_ok=True,
            ignore=shutil.ignore_patterns(
                "build", ".gradle", ".kotlin", "release", "debug", "target"
            ),
        )
    for name in ["SharedAndroid", "Installer"]:
        path = source / name
        if not path.exists():
            path.symlink_to(ROOT / name, target_is_directory=True)
    config = source / "Packaging/new/config/voyahtune_keyboard_en_config.json"
    base = json.loads(config.read_text())
    for label, version in [("A", "4.5.0"), ("B", "4.5.1")]:
        config.write_text(json.dumps({**base, "acceptanceFixture": label}))
        subprocess.run(
            [
                str(ROOT / "Installer/target/release/installer-build"),
                "build",
                "--root",
                str(source),
                "--version",
                version,
                "--revision",
                f"acceptance-unified-{label}",
                "--output",
                str(work / label),
            ],
            check=True,
        )


if __name__ == "__main__":
    main()
