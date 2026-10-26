#!/usr/bin/env python3
"""Stage the unified runtime and APK identity inputs for the Android 11 VoyEmu."""

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

SOLUTION = Path(__file__).resolve().parents[1]
REPOSITORY = SOLUTION.parents[1]
OUTPUT = SOLUTION / "build/voyemu"


def finalize():
    recipe_hash = hashlib.sha256((OUTPUT / "recipe.json").read_bytes()).hexdigest()
    sources = json.loads((OUTPUT / "sources.json").read_text())["artifacts"]
    expected = {
        item["name"]: hashlib.sha256(
            (REPOSITORY / item["source"]).read_bytes()
        ).hexdigest()
        for item in sources
    }
    for project, name in [
        ("Native", "native.apk"),
        ("RestoreMode", "restore_mode.apk"),
    ]:
        source = REPOSITORY / project / "app/build/outputs/apk/release/app-release.apk"
        with zipfile.ZipFile(source) as apk:
            identity = json.loads(apk.read("assets/voyahtune-build.json"))
        if (
            identity["infrastructure"] != "unified"
            or identity["recipeSha256"] != recipe_hash
        ):
            raise ValueError(project + ": build identity does not match VoyEmu recipe")
        if identity["runtimeHashes"] != expected:
            raise ValueError(project + ": runtime hashes do not match staging")
        shutil.copyfile(source, OUTPUT / name)
    files = {
        str(path.relative_to(OUTPUT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(OUTPUT.rglob("*"))
        if path.is_file() and path.name != "build-manifest.json"
    }
    (OUTPUT / "build-manifest.json").write_text(
        json.dumps(
            {"schema": 1, "purpose": "voyemu-local-validation", "sha256": files},
            indent=2,
        )
        + "\n"
    )
    print(OUTPUT)


def main():
    runtime = SOLUTION / "build/runtime"
    if not (runtime / "voyahtune-loader").is_file():
        raise SystemExit("Run Packaging/new/build.sh first")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    shutil.copytree(runtime, OUTPUT / "runtime", dirs_exist_ok=True)
    shutil.copytree(SOLUTION / "system", OUTPUT / "system", dirs_exist_ok=True)
    # VoyEmu checks Enforcing; its system_server JIT mapping cannot be patched under that policy.
    boot = OUTPUT / "system/voyahtune.load.rc"
    boot.write_text(
        "on early-init\n    setprop dalvik.vm.usejit false\n\n"
        + boot.read_text().replace(
            "/system/bin/setenforce 0", "/system/bin/setenforce 1"
        )
    )
    shutil.copyfile(
        REPOSITORY / "Packaging/system/privapp-permissions-ru.big.town.anative.xml",
        OUTPUT / "whitelist.xml",
    )
    shutil.copyfile(
        REPOSITORY / "RunYN/app/build/outputs/apk/release/app-release.apk",
        OUTPUT / "runyn.apk",
    )

    files = []
    artifacts = []

    def add(name, source, destination):
        files.append({"artifact": name, "destination": destination})
        artifacts.append({"name": name, "source": str(source.relative_to(REPOSITORY))})

    for source in sorted((OUTPUT / "runtime").iterdir()):
        if source.is_file():
            add(source.name, source, "/data/local/bin/voyahtune-new/" + source.name)
    add("voyahtune.load.rc", boot, "/system/etc/init/voyahtune.load.rc")
    add(
        "voyahtune.load.sh",
        OUTPUT / "system/voyahtune.load.sh",
        "/system/etc/init.voyahtune.load.sh",
    )
    add(
        "whitelist.xml",
        OUTPUT / "whitelist.xml",
        "/system/etc/permissions/privapp-permissions-ru.big.town.anative.xml",
    )
    artifacts.append(
        {
            "name": "runyn.apk",
            "source": str((OUTPUT / "runyn.apk").relative_to(REPOSITORY)),
        }
    )
    recipe = {
        "schema": 3,
        "infrastructure": "unified",
        "purpose": "voyemu-local-validation",
        "files": files,
        "packages": [
            {"artifact": name}
            for name in ["native.apk", "restore_mode.apk", "runyn.apk"]
        ],
    }
    (OUTPUT / "recipe.json").write_text(json.dumps(recipe, indent=2) + "\n")
    (OUTPUT / "sources.json").write_text(
        json.dumps({"artifacts": artifacts}, indent=2) + "\n"
    )
    print(OUTPUT)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--finalize",
        action="store_true",
        help="Verify and copy the freshly built common APKs",
    )
    arguments = parser.parse_args()
    if arguments.finalize:
        finalize()
    else:
        main()
