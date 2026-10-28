#!/usr/bin/env python3

import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "native/dependencies.lock.json"


def git(source, *arguments, check=True):
    return subprocess.run(
        ["git", "-C", str(source), *arguments], check=check,
        capture_output=True, text=True,
    )


def require_clean(source):
    if git(source, "diff", "--quiet", "--ignore-submodules=all", "HEAD", check=False).returncode:
        raise RuntimeError(f"Native dependency has source changes: {source}")
    if git(source, "ls-files", "--others", "--exclude-standard").stdout.strip():
        raise RuntimeError(f"Native dependency has untracked inputs: {source}")


def verify_dependencies(directory):
    lock = json.loads(LOCK.read_text())
    for dependency in lock["dependencies"]:
        source = directory / dependency["path"]
        revision = git(source, "rev-parse", "HEAD").stdout.strip()
        if revision != dependency["revision"]:
            raise RuntimeError(f"Native dependency revision differs from lock: {source}")
        require_clean(source)
        for patch in dependency.get("patches", []):
            path = ROOT / "native" / patch["path"]
            if hashlib.sha256(path.read_bytes()).hexdigest() != patch["sha256"]:
                raise RuntimeError(f"Native dependency patch differs from lock: {path}")
    return lock


def stage_lsplant(directory, destination):
    lock = verify_dependencies(directory)
    dependency = next(item for item in lock["dependencies"] if item["path"] == "LSPlant")
    source = (directory / "LSPlant").resolve()
    destination = destination.resolve()
    if destination == source or destination.is_relative_to(source):
        raise RuntimeError("Patched build sources must be separate from the pinned checkout")
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns(".git", "build", ".cxx"))
    for patch in dependency.get("patches", []):
        subprocess.run(["patch", "-p1", "-t", "-i", str(ROOT / "native" / patch["path"])],
                       cwd=destination, check=True, capture_output=True, text=True)


def prepare(directory):
    lock = json.loads(LOCK.read_text())
    for dependency in lock["dependencies"]:
        source = directory / dependency["path"]
        if not (source / ".git").exists():
            source.mkdir(parents=True, exist_ok=True)
            git(source, "init")
            git(source, "remote", "add", "origin", dependency["repository"])
        if git(source, "rev-parse", "--verify", "HEAD", check=False).returncode == 0:
            require_clean(source)
        revision = dependency["revision"]
        if git(source, "cat-file", "-e", revision + "^{commit}", check=False).returncode:
            git(source, "fetch", "--depth", "1", dependency["repository"], revision)
        git(source, "checkout", "--detach", revision)
        print(f"{dependency['path']}: {revision}")
    verify_dependencies(directory)


def main():
    parser = argparse.ArgumentParser(description="Prepare only the pinned native-agent sources")
    parser.add_argument("--directory", type=Path, default=ROOT / "cache/native-sources")
    parser.add_argument("--stage-lsplant", action="store_true")
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    if arguments.stage_lsplant:
        if arguments.output is None:
            parser.error("--stage-lsplant requires --output")
        stage_lsplant(arguments.directory.resolve(), arguments.output)
    else:
        prepare(arguments.directory.resolve())


if __name__ == "__main__":
    main()
