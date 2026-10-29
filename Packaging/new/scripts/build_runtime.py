#!/usr/bin/env python3
"""Build and assemble the unified Android 11 ARM32/ARM64 injection runtime."""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from android_toolchain import SETTINGS, go_android_environment, sdk_directory
from bundle import bundle, loader_overlay
from prepare_native_dependencies import prepare

ROOT = Path(__file__).resolve().parents[1]


def run(*arguments, environment=None):
    subprocess.run(list(map(str, arguments)), check=True, env=environment)


def main():
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    environment = dict(os.environ)
    environment.setdefault("SOURCE_DATE_EPOCH", "0")
    run(sys.executable, ROOT / "scripts/build_java_agents.py", environment=environment)
    prepare(ROOT / "cache/native-sources")
    sdk = sdk_directory()
    cmake_directory = sdk / "cmake" / SETTINGS["cmake"] / "bin"
    cmake = cmake_directory / "cmake"
    ndk = sdk / "ndk" / SETTINGS["ndk"]
    with tempfile.TemporaryDirectory(prefix="native-components-", dir=build) as temporary:
        components = Path(temporary) / "components"
        components.mkdir()
        shutil.copyfile(build / "java-runtime/agents.dex", components / "agents.dex")
        native_id = None
        for abi, suffix in (("arm64-v8a", "arm64"), ("armeabi-v7a", "arm")):
            native = build / ("native-" + suffix)
            run(cmake, "-S", ROOT / "native", "-B", native,
                "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
                "-DCMAKE_MAKE_PROGRAM=" + str(cmake_directory / "ninja"), "-G", "Ninja",
                "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-30", "-DCMAKE_BUILD_TYPE=Release",
                environment=environment)
            run(cmake, "--build", native, "--target", "voyahtune-agent", "--parallel", environment=environment)
            identity = (native / "native-id.txt").read_text()
            if native_id is not None and identity != native_id:
                raise SystemExit("Native component identities differ between ABIs")
            native_id = identity
            library = "libvoyahtune-agent.so" if abi == "arm64-v8a" else "libvoyahtune-agent-arm.so"
            shutil.copyfile(native / ("libvoyahtune-agent-" + suffix + ".so"), components / library)
            (components / "native-id.txt").write_text(identity)
            go_environment = go_android_environment(sdk, abi=abi)
            go_environment["SOURCE_DATE_EPOCH"] = environment["SOURCE_DATE_EPOCH"]
            packages = [("voyahtune-inject" if abi == "arm64-v8a" else "voyahtune-inject-arm", "./cmd/voyahtune-inject")]
            for name, package in packages:
                run(os.environ.get("GO_BIN", "go"), "-C", ROOT / "loader", "build", "-trimpath", "-buildvcs=false",
                    "-buildmode=pie", "-o", components / name, package, environment=go_environment)
        overlay = loader_overlay(components, Path(temporary))
        loader_environment = go_android_environment(sdk, abi="arm64-v8a")
        loader_environment["SOURCE_DATE_EPOCH"] = environment["SOURCE_DATE_EPOCH"]
        run(os.environ.get("GO_BIN", "go"), "-C", ROOT / "loader", "build", "-trimpath",
            "-buildvcs=false", "-buildmode=pie", "-overlay", overlay,
            "-o", components / "voyahtune-loader", ".", environment=loader_environment)
        bundle(build / "runtime", components)
    system = build / "system"
    if system.exists():
        shutil.rmtree(system)
    shutil.copytree(ROOT / "system", system)
    for obsolete in ("release-inputs.json", "install-layout.json"):
        (build / obsolete).unlink(missing_ok=True)
    print(build / "runtime")


if __name__ == "__main__":
    main()
