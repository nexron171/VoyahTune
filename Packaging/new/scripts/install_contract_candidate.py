#!/usr/bin/env python3

import argparse
import contextlib
import io
import json
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

import bundle


def assemble_runtime():
    with tempfile.TemporaryDirectory(prefix="native-layout-") as temporary:
        root = Path(temporary)
        components = root / "compiled"
        components.mkdir()
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[18:20] = (183).to_bytes(2, "little")
        for name in ("libvoyahtune-agent.so", "voyahtune-inject", "voyahtune-loader"):
            (components / name).write_bytes(header + name.encode())
        header[:6] = b"\x7fELF\x01\x01"
        header[18:20] = (40).to_bytes(2, "little")
        for name in ("libvoyahtune-agent-arm.so", "voyahtune-inject-arm"):
            (components / name).write_bytes(header + name.encode())
        (components / "agents.dex").write_bytes(b"dex\n039\0compiled-component-boundary")
        (components / "native-id.txt").write_text("native-" + "a" * 64 + "\n")
        runtime = root / "runtime"
        bundle.bundle(runtime, components)
        files = list(runtime.iterdir())
        abis = set()
        for path in files:
            header = path.read_bytes()[:20]
            if header[:4] == b"\x7fELF":
                architecture = (header[4], int.from_bytes(header[18:20], "little"))
                abis.add({(2, 183): "arm64-v8a", (1, 40): "armeabi-v7a"}.get(architecture, "unknown"))
        return {
            "fridaFiles": sum("frida" in path.name.lower() or path.suffix == ".js" for path in files),
            "agentDex": (runtime / "agents.dex").read_bytes().startswith(b"dex\n"),
            "nativeAbis": sorted(abis),
            "executablesHaveMode755": all(stat.S_IMODE((runtime / name).stat().st_mode) == 0o755
                                          for name in bundle.EXECUTABLES),
        }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--driver", required=True)
    arguments = parser.parse_args()
    request = json.load(sys.stdin)
    results = []
    delegated = []
    for case in request["cases"]:
        if case["input"].get("operation") == "assemble-runtime":
            with contextlib.redirect_stdout(io.StringIO()):
                observed = assemble_runtime()
            results.append({"id": case["id"], "observed": observed})
        else:
            delegated.append(case)
    if delegated:
        process = subprocess.run([arguments.driver], input=json.dumps({"schema": 1, "cases": delegated}),
                                 text=True, capture_output=True, timeout=10, check=True)
        results.extend(json.loads(process.stdout)["results"])
    json.dump({"schema": 1, "results": results}, sys.stdout)


if __name__ == "__main__":
    main()
