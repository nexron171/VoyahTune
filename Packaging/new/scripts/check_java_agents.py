#!/usr/bin/env python3

import argparse
import hashlib
import os
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

from android_toolchain import android_library, d8_path, java_home

ROOT = Path(__file__).resolve().parents[1]
GSON_VERSION = "2.11.0"
GSON_SHA256 = "57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b"


def gson_jar(configured):
    if configured:
        path = Path(configured)
        if not path.is_file():
            raise SystemExit(f"Missing test Gson JAR: {path}")
        return path
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" / GSON_VERSION
    jars = sorted(cache.glob("*/gson-*.jar"))
    if len(jars) != 1:
        raise SystemExit("Pass --gson-jar with Gson 2.11.0; this test command does not download dependencies")
    return jars[0]


def check_candidate(stage, configured_gson=None):
    subprocess.run([sys.executable, str(ROOT / "tests/test_agent_contract_runner.py")], check=True)
    java = java_home()
    android = android_library()
    gson = gson_jar(configured_gson)
    if hashlib.sha256(gson.read_bytes()).hexdigest() != GSON_SHA256:
        raise SystemExit("Test Gson JAR differs from the pinned 2.11.0 dependency")
    runtime_classes = stage / "runtime-classes"
    test_classes = stage / "test-classes"
    runtime_classes.mkdir()
    test_classes.mkdir()
    sources = sorted((ROOT / "java/src/main/java").rglob("*.java"))
    for name in ("NavigationAudioSettings", "NavigationAudioRouting"):
        sources.append(ROOT.parents[1] / f"SharedAndroid/src/main/java/ru/big/town/common/{name}.java")
    subprocess.run([
        str(java / "bin/javac"), "--release", "11", "-classpath", str(android),
        "-d", str(runtime_classes), *map(str, sources),
    ], check=True)
    sources = sorted((ROOT / "tests/java").rglob("*.java"))
    subprocess.run([
        str(java / "bin/javac"), "--release", "11", "-classpath",
        os.pathsep.join((str(runtime_classes), str(gson), str(android))),
        "-d", str(test_classes), *map(str, sources),
    ], check=True)
    for test in ("ru.voyahtune.agent.HookRegistryTest", "ru.voyahtune.agent.ModuleRuntimeTest", "ru.voyahtune.contracts.FailureDescriptionTest",
            "ru.voyahtune.agent.runtime.DexMethodTableTest",
                 "ru.voyahtune.agent.SteeringTest", "ru.voyahtune.agent.OemAccessTest",
                 "ru.voyahtune.agent.ModuleListenerTest",
                 "ru.voyahtune.agent.ModuleTimerTest",
                 "ru.voyahtune.agent.DriveSelectionTest",
                 "ru.voyahtune.contracts.ApolloModuleTest", "ru.voyahtune.contracts.MultidisplayModuleTest",
                 "ru.voyahtune.contracts.CabinClimateModuleTest", "ru.voyahtune.contracts.BrightnessModuleTest", "ru.voyahtune.contracts.VehicleAirModuleTest", "ru.voyahtune.contracts.DriveResetModuleTest",
                 "ru.voyahtune.contracts.AccModuleTest", "ru.voyahtune.contracts.NavigationSourceModuleTest",
                 "ru.voyahtune.contracts.OemNowPlayingTest",
                 "ru.voyahtune.contracts.StatusBarModuleTest", "ru.voyahtune.contracts.RadioControllersTest",
                 "ru.voyahtune.contracts.RdsTuneOwnershipTest", "ru.voyahtune.contracts.RdsModuleTest",
                 "ru.voyahtune.contracts.ClusterModuleTest", "ru.voyahtune.contracts.ClusterCardControllerTest",
                 "ru.voyahtune.contracts.ModuleHealthTest",
                 "ru.voyahtune.contracts.WindowSystemPolicyTest",
                 "ru.voyahtune.contracts.WindowPriorityRecalculationTest",
                 "ru.voyahtune.contracts.WindowWifiModuleTest",
                 "ru.voyahtune.contracts.ModuleContentionTest",
                 "ru.voyahtune.contracts.WindowLayoutTest",
                 "ru.voyahtune.contracts.WindowGeometryModuleTest",
                 "ru.voyahtune.contracts.ClientGeometryModuleTest",
                 "ru.voyahtune.contracts.ClientInputMethodModuleTest",
                 "ru.voyahtune.contracts.NavigationAudioModuleTest",
                 "ru.voyahtune.contracts.ClientInputMethodPlatformTest",
                 "ru.voyahtune.contracts.LauncherCatalogTest",
                 "ru.voyahtune.contracts.SystemServerClassesTest",
                 "ru.voyahtune.contracts.LauncherPresentationTest",
                 "ru.voyahtune.contracts.LauncherVisibilityTest",
                 "ru.voyahtune.contracts.ViewOwnershipTest",
                 "ru.voyahtune.contracts.LauncherDockModuleTest",
                 "ru.voyahtune.contracts.LauncherAllAppsModuleTest"):
        subprocess.run([
            str(java / "bin/java"), "-classpath",
            os.pathsep.join((str(runtime_classes), str(test_classes), str(gson))),
            test,
        ], check=True)
    fixture_directory = stage / "dex-callers"
    fixture_directory.mkdir()
    subprocess.run([
        str(d8_path()), "--min-api", "30", "--lib", str(android), "--output", str(fixture_directory),
        *map(str, sorted((test_classes / "ru/voyahtune/contracts").glob("DexCallerFixtures*.class"))),
    ], env=dict(os.environ, JAVA_HOME=str(java)), check=True)
    subprocess.run([
        str(java / "bin/java"), "-classpath", os.pathsep.join((str(runtime_classes), str(test_classes))),
        "ru.voyahtune.contracts.DexCallGraphTest", str(fixture_directory / "classes.dex"),
    ], check=True)
    native_test = stage / "published-callback-test"
    subprocess.run([
        os.environ.get("HOST_CXX", "c++"), "-std=c++20", "-pthread", "-I", str(ROOT / "native"),
        str(ROOT / "tests/native/published_callback_test.cpp"), "-o", str(native_test),
    ], check=True)
    subprocess.run([str(native_test)], check=True)
    bootstrap_probe = stage / "bootstrap-contracts"
    subprocess.run([
        os.environ.get("HOST_CXX", "c++"), "-std=c++20", "-pthread", "-I", str(ROOT / "native"),
        str(ROOT / "tests/native/bootstrap_contract_probe.cpp"), "-o", str(bootstrap_probe),
    ], check=True)
    go_driver = stage / "go-contracts"
    environment = dict(os.environ, GOTOOLCHAIN="local", GOPROXY="off",
                       GOCACHE=str(ROOT / "cache/go-build"), GOMODCACHE=str(ROOT / "cache/go-mod"))
    subprocess.run(["go", "test", "./..."], cwd=ROOT / "loader", env=environment, check=True)
    subprocess.run([
        "go", "build", "-trimpath", "-buildvcs=false", "-o", str(go_driver), ".",
    ], cwd=ROOT / "tests/go-contract-driver", env=environment, check=True)
    rust_environment = os.environ.copy()
    repository = ROOT.parents[1]
    cached_cargo = repository / "Releases/cache/cargo"
    if not rust_environment.get("CARGO_HOME") and cached_cargo.is_dir():
        rust_environment.update(CARGO_HOME=str(cached_cargo), RUSTUP_HOME=str(repository / "Releases/cache/rustup"))
        rust_environment["PATH"] = str(cached_cargo / "bin") + os.pathsep + rust_environment["PATH"]
    rust_environment["RUSTUP_TOOLCHAIN"] = tomllib.loads((repository / "Installer/rust-toolchain.toml").read_text())["toolchain"]["channel"]
    rust_environment["CARGO_TARGET_DIR"] = str(repository / "Installer/target")
    subprocess.run([
        "cargo", "build", "--locked", "--manifest-path",
        str(ROOT / "tests/install-contract-driver/Cargo.toml"),
    ], env=rust_environment, check=True)
    install_driver = Path(rust_environment["CARGO_TARGET_DIR"]) / "debug/install-contract-driver"
    command = [sys.executable, str(ROOT / "scripts/agent_contract_candidate.py"),
               "--java-bin", str(java / "bin/java"),
               "--java-classpath", os.pathsep.join((str(runtime_classes), str(test_classes), str(gson))),
               "--go-driver", str(go_driver), "--native-driver", str(bootstrap_probe),
               "--install-driver", str(install_driver)]
    environment = dict(os.environ, VOYAHTUNE_KEYBOARD_LAYOUT=str(ROOT / "config/voyahtune_skb_qwerty_ru.json"))
    result = subprocess.run([
        sys.executable, str(ROOT / "scripts/agent_contract_runner.py"), "--driver", *command,
    ], env=environment)
    return result.returncode, runtime_classes


def main():
    parser = argparse.ArgumentParser(description="Required behavior gate for the Java-agent runtime")
    parser.add_argument("--gson-jar", default=os.environ.get("VOYAHTUNE_TEST_GSON_JAR"))
    arguments = parser.parse_args()
    (ROOT / "build").mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="java-contract-", dir=ROOT / "build") as temporary:
        code, _ = check_candidate(Path(temporary), arguments.gson_jar)
    raise SystemExit(code)


if __name__ == "__main__":
    main()
