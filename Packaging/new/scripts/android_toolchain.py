import json
import hashlib
import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SETTINGS = json.loads((ROOT / "native/dependencies.lock.json").read_text())


def sdk_directory():
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured)
    return Path.home() / ("Library/Android/sdk" if sys.platform == "darwin" else "Android/Sdk")


def java_home():
    configured = os.environ.get("JAVA_HOME")
    if configured:
        return Path(configured)
    if sys.platform == "darwin":
        result = subprocess.run(
            ["/usr/libexec/java_home", "-v", str(SETTINGS["jdk"])],
            check=True, capture_output=True, text=True,
        )
        return Path(result.stdout.strip())
    raise SystemExit(f"Set JAVA_HOME to JDK {SETTINGS['jdk']}")


def android_library(sdk=None):
    root = sdk or sdk_directory()
    path = root / f"platforms/android-{SETTINGS['compileApi']}/android.jar"
    if not path.is_file():
        raise SystemExit(f"Missing compile-only Android library: {path}")
    return path


def d8_path(sdk=None):
    root = sdk or sdk_directory()
    return root / "build-tools" / SETTINGS["buildTools"] / "d8"


def ndk_compiler(sdk=None, cpp=False, abi="arm64-v8a"):
    root = sdk or sdk_directory()
    host = "darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64"
    triple = {"arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "armv7a-linux-androideabi"}[abi]
    name = f"{triple}{SETTINGS['api']}-" + ("clang++" if cpp else "clang")
    return root / "ndk" / SETTINGS["ndk"] / "toolchains/llvm/prebuilt" / host / "bin" / name


def go_android_environment(sdk=None, abi="arm64-v8a"):
    inputs = [ROOT / "native" / name for name in ("bootstrap_record.h", "remote_injector.h", "remote_injector.c", "remote_registers.h")]
    fingerprint = hashlib.sha256()
    for path in inputs:
        fingerprint.update(path.name.encode() + b"\0" + path.read_bytes())
    flags = os.environ.get("CGO_CFLAGS", "")
    flags += " -DVOYAHTUNE_INJECT_SOURCE_ID=0x" + fingerprint.hexdigest()[:16]
    return dict(
        os.environ, GOTOOLCHAIN="local", GOPROXY="off",
        GOCACHE=str(ROOT / "cache/go-build"), GOMODCACHE=str(ROOT / "cache/go-mod"),
        CGO_ENABLED="1", GOOS="android", GOARCH={"arm64-v8a": "arm64", "armeabi-v7a": "arm"}[abi], GOARM="7", CC=str(ndk_compiler(sdk, abi=abi)), CGO_CFLAGS=flags.strip(),
    )
