#!/usr/bin/env python3
"""Read build versions and synchronize derived Installer manifests."""

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSTALLER_PACKAGES = {
    "installer-build",
    "installer-core",
    "qnx-fonts",
    "release-core",
    "voyahtune-desktop",
}


def build_environment(root, release_version=None, minimum_installer_version=None):
    versions = json.loads((root / "versions.json").read_text())
    if set(versions) != {"installer", "voyahtune"} or any(
        not isinstance(value, str)
        or not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value)
        for value in versions.values()
    ):
        raise ValueError(
            "versions.json must contain installer and voyahtune major.minor.patch versions"
        )
    return {
        "VOYAH_INSTALLER_VERSION": versions["installer"],
        "VOYAH_RELEASE_VERSION": release_version or versions["voyahtune"],
        "VOYAH_MINIMUM_INSTALLER_VERSION": minimum_installer_version or versions["installer"],
    }


def write_if_changed(path, contents):
    if path.read_text() != contents:
        path.write_text(contents)


def sync_installer_version(root):
    version = build_environment(root)["VOYAH_INSTALLER_VERSION"]
    manifest = root / "Installer/Cargo.toml"
    contents, count = re.subn(
        r'(\[workspace\.package\][^\[]*?\bversion\s*=\s*")[^"]+(")',
        lambda match: match[1] + version + match[2],
        manifest.read_text(),
        count=1,
    )
    if count != 1:
        raise ValueError("Missing workspace.package.version in Installer/Cargo.toml")
    write_if_changed(manifest, contents)
    tauri = root / "Installer/desktop/src-tauri/tauri.conf.json"
    if tauri.exists():
        config = json.loads(tauri.read_text())
        if "version" in config:
            del config["version"]
            tauri.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    for relative in [
        "Installer/Cargo.lock",
        "Updater/daemon/Cargo.lock",
        "Packaging/new/tests/install-contract-driver/Cargo.lock",
    ]:
        path = root / relative
        if not path.exists():
            continue
        packages = path.read_text().split("[[package]]")
        for index, block in enumerate(packages[1:], 1):
            name = re.search(r'^name = "([^"]+)"$', block, re.MULTILINE)
            if (
                name
                and name[1] in INSTALLER_PACKAGES
                and not re.search(r"^source =", block, re.MULTILINE)
            ):
                packages[index] = re.sub(
                    r'^version = "[^"]+"$',
                    f'version = "{version}"',
                    block,
                    count=1,
                    flags=re.MULTILINE,
                )
        write_if_changed(path, "[[package]]".join(packages))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sync-installer", action="store_true")
    args = parser.parse_args()
    if args.sync_installer:
        sync_installer_version(ROOT)
    print(json.dumps(build_environment(ROOT)))


if __name__ == "__main__":
    main()
