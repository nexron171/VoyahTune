#!/usr/bin/env python3

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from check_java_agents import check_candidate, java_home
from android_toolchain import android_library, d8_path


ROOT = Path(__file__).resolve().parents[1]


def main():
    destination = ROOT / "build/java-runtime"
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("agents.dex", "manifest.json"):
        (destination / name).unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix="java-agent-", dir=destination) as temporary:
        stage = Path(temporary)
        code, classes = check_candidate(stage, os.environ.get("VOYAHTUNE_TEST_GSON_JAR"))
        if code:
            print("Java-agent behavior gate failed; no runtime DEX was produced.", file=sys.stderr)
            raise SystemExit(code)
        java = java_home()
        android = android_library()
        dex = stage / "dex"
        dex.mkdir()
        subprocess.run([
            str(d8_path()), "--min-api", "30", "--lib", str(android),
            "--output", str(dex), *map(str, sorted(classes.rglob("*.class"))),
        ], env=dict(os.environ, JAVA_HOME=str(java)), check=True)
        shutil.copyfile(dex / "classes.dex", destination / "agents.dex")
        (destination / "agents.dex").chmod(0o644)
    digest = hashlib.sha256((destination / "agents.dex").read_bytes()).hexdigest()
    (destination / "manifest.json").write_text(json.dumps({
        "schema": 1, "infrastructure": "unified", "component": "java-agents",
        "behaviorContracts": "passed", "sha256": {"agents.dex": digest},
        "androidExecution": "requires-separate-validation",
    }, indent=2) + "\n")
    print(destination / "agents.dex")


if __name__ == "__main__":
    main()
