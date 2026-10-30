#!/usr/bin/env python3
"""Build versioned payloads independently of the optional GUI installers."""

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def zip_tree(folder, output):
    # Preserve Unix execute bits and .app symlinks; do not dereference symlinks.
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(folder.rglob("*")):
            relative = str(path.relative_to(folder.parent))
            if path.is_symlink():
                info = zipfile.ZipInfo(relative)
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(info, os.readlink(path))
            elif path.is_file():
                archive.write(path, relative)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError(f"Повреждён новый ZIP: {output}")


def publish_outputs(pairs):
    """Replace completed outputs; keep the previous release on a publication error."""
    pairs = [(Path(source), Path(dest)) for source, dest in pairs]
    for source, dest in pairs:
        if not source.is_dir() and not source.is_file():
            raise ValueError(f"Missing completed output: {source}")
        dest.parent.mkdir(parents=True, exist_ok=True)
    backup = Path(tempfile.mkdtemp(prefix=".release-previous-", dir=pairs[0][1].parent))
    saved, published = [], []
    try:
        for index, (source, dest) in enumerate(pairs):
            if dest.exists() or dest.is_symlink():
                old = backup / str(index)
                dest.rename(old)
                saved.append((old, dest))
            source.rename(dest)
            published.append((source, dest))
    except BaseException:
        # Preserve backup on a rollback failure for manual recovery.
        for source, dest in reversed(published):
            dest.rename(source)
        for old, dest in reversed(saved):
            old.rename(dest)
        backup.rmdir()
        raise
    shutil.rmtree(backup)


def zip_payload(folder, output):
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(folder.rglob("*")):
            if path.is_symlink():
                raise ValueError(f"Payload symlink: {path}")
            if path.is_file():
                archive.write(path, path.relative_to(folder).as_posix())
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupted payload ZIP")


def payload_entry(folder, archive, minimum_ota_version):
    metadata = payload_metadata(folder)
    version = metadata["releaseVersion"]
    return {
        "version": version,
        "url": f"https://github.com/nexron171/VoyahTune/releases/download/v{version}/{archive.name}",
        "size": archive.stat().st_size,
        "sha256": sha(archive),
        "minimumInstallerVersion": metadata["minimumInstallerVersion"],
        "minimumOtaVersion": minimum_ota_version,
    }


def payload_metadata(folder):
    with zipfile.ZipFile(folder / "common/restore_mode.apk") as apk:
        return json.loads(apk.read("assets/voyahtune-build.json"))


def payload_identity(folder):
    digest = hashlib.sha256()
    for path in sorted((folder / "common").iterdir()):
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Invalid payload entry: {path}")
        name = path.name.encode()
        digest.update(len(name).to_bytes(8, "little"))
        digest.update(name)
        digest.update(sha(path).encode())
    return digest.hexdigest()


def normalize_version(value):
    version = value.removeprefix("v")
    if not re.fullmatch(
        r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?",
        version,
    ):
        raise ValueError("Нужна SemVer-версия, например 3.22.0")
    base = version.partition("+")[0]
    if any(base.endswith("-" + profile) for profile in ("pi", "od")):
        raise ValueError("Общий релиз не использует суффиксы -pi/-od")
    return version


def main():
    import tomllib

    parser = argparse.ArgumentParser(
        description="Build a single VoyahTune payload; optionally build GUI installers separately."
    )
    parser.add_argument("version")
    parser.add_argument(
        "--catalog-url",
        help="Override the shared HTTPS release catalog embedded in the updater and optional GUI",
    )
    parser.add_argument(
        "--minimum-ota-version",
        required=True,
        help="Required for unified payloads: minimum VoyahTune version with a compatible root service",
    )
    parser.add_argument(
        "--minimum-installer-version",
        required=True,
        help="Minimum desktop Installer version required by the payload (major.minor.patch)",
    )
    parser.add_argument(
        "--windows-arch",
        choices=["x64", "x86", "arm64"],
        help="Build one Windows architecture; defaults to x64, x86 and ARM64",
    )
    parser.add_argument(
        "--offline-bundle",
        action="store_true",
        help="Embed the selected payload in the GUI installer",
    )
    parser.add_argument(
        "--payload",
        action="store_true",
        help="Build only the shared payload ZIP; no desktop tools or containers",
    )
    parser.add_argument(
        "--installers",
        action="store_true",
        help="Build macOS Universal, Windows x64/x86/ARM64 and Linux amd64 installers",
    )
    for flag in ["mac", "windows", "linux"]:
        parser.add_argument(
            "--" + flag,
            action="store_true",
            help="Build only selected installer platforms (flags may be combined)",
        )
    parser.add_argument(
        "--no-build",
        action="store_true",
        help="Reuse matching Android APKs; desktop installers are still rebuilt",
    )
    parser.add_argument(
        "--no-zip",
        action="store_true",
        help="Only build and verify the internal payload",
    )
    parser.add_argument(
        "--revision", help="Defaults to Git HEAD plus -dirty if modified"
    )
    args = parser.parse_args()
    for flag, minimum in (
        ("--minimum-installer-version", args.minimum_installer_version),
        ("--minimum-ota-version", args.minimum_ota_version),
    ):
        if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", minimum):
            parser.error(f"{flag} must have the format major.minor.patch")
    if args.windows_arch:
        args.windows = True
    if args.payload and (args.installers or args.mac or args.windows or args.linux):
        parser.error("--payload cannot be combined with desktop platform flags")
    if args.installers:
        args.mac = True
        args.windows = True
        args.linux = True
    desktop_build = not args.payload and not args.no_zip
    selected = [
        name
        for name, enabled in [
            ("macos", args.mac),
            ("windows", args.windows),
            ("linux", args.linux),
        ]
        if enabled
    ] or ["macos", "windows"]
    windows_architectures = (
        [args.windows_arch] if args.windows_arch else ["x64", "x86", "arm64"]
    )
    expected_artifacts = {
        artifact
        for name in selected
        for artifact in (
            [f"windows-{architecture}" for architecture in windows_architectures]
            if name == "windows"
            else [name]
        )
    }
    try:
        version = normalize_version(args.version)
    except ValueError as error:
        parser.error(str(error))
    if args.catalog_url:
        from urllib.parse import urlsplit

        catalog = urlsplit(args.catalog_url)
        if (
            catalog.scheme != "https"
            or not catalog.hostname
            or catalog.username
            or catalog.password
            or catalog.fragment
        ):
            parser.error(
                "--catalog-url must be an HTTPS URL without credentials or fragment"
            )
    if not re.fullmatch(
        r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?",
        version,
    ):
        parser.error("Нужна SemVer-версия, например 3.3.0")
    if desktop_build and platform.system() != "Darwin":
        parser.error(
            "Сборка релиза установщиков выполняется из macOS. Нативная сборка одной ОС: Installer/scripts/build.mjs --payload DIRECTORY."
        )
    build = ROOT / "Releases/build"
    build.mkdir(parents=True, exist_ok=True)
    installer_version = tomllib.loads((ROOT / "Installer/Cargo.toml").read_text())[
        "workspace"
    ]["package"]["version"]
    destination = ROOT / "Releases/dist" / f"VoyahTune-Installer-{installer_version}"
    if "windows" in selected and args.windows_arch:
        destination = destination.with_name(
            destination.name + f"-windows-{args.windows_arch}"
        )
    payload_output = build / f"installer-payload-{version}"
    revision = args.revision or subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip() + (
        "-dirty"
        if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)
        else ""
    )
    env = os.environ.copy()
    env["VOYAH_CATALOG_URL"] = args.catalog_url or ""
    cached = ROOT / "Releases/cache/cargo"
    if not env.get("CARGO_HOME") and cached.exists():
        env.update(
            CARGO_HOME=str(cached), RUSTUP_HOME=str(ROOT / "Releases/cache/rustup")
        )
        env["PATH"] = str(cached / "bin") + os.pathsep + env["PATH"]
    env["RUSTUP_TOOLCHAIN"] = tomllib.loads(
        (ROOT / "Installer/rust-toolchain.toml").read_text()
    )["toolchain"]["channel"]
    target = Path(env.get("CARGO_TARGET_DIR", ROOT / "Installer/target")).resolve()
    builder = (
        target
        / "release"
        / ("installer-build.exe" if os.name == "nt" else "installer-build")
    )
    # Android and Rust output directories are shared across builds.
    lock = build / ".release.lock"
    lock.mkdir()
    try:
        with tempfile.TemporaryDirectory(prefix=".release-", dir=build) as temporary:
            work = Path(temporary)
            run(["sh", ROOT / "Packaging/new/check.sh"])
            run(
                [
                    "cargo",
                    "build",
                    "--locked",
                    "--release",
                    "--manifest-path",
                    ROOT / "Installer/Cargo.toml",
                    "-p",
                    "installer-build",
                ],
                env=env,
                cwd=ROOT,
            )
            payload = work / "payload"
            run(
                [
                    builder,
                    "build",
                    "--root",
                    ROOT,
                    "--version",
                    version,
                    "--minimum-installer-version",
                    args.minimum_installer_version,
                    "--revision",
                    revision,
                    "--output",
                    payload,
                    *(["--skip-android"] if args.no_build else []),
                ],
                env=env,
            )
            actual_minimum = payload_metadata(payload)["minimumInstallerVersion"]
            if actual_minimum != args.minimum_installer_version:
                raise ValueError(
                    f"APK minimumInstallerVersion is {actual_minimum}, "
                    f"expected {args.minimum_installer_version}; rebuild the Android APKs"
                )
            packages = work / "packages"
            packages.mkdir()
            if desktop_build:
                compiled = work / "compiled"
                run(
                    [
                        ROOT / "Installer/scripts/build-all-macos.sh",
                        "--output",
                        compiled,
                        *(
                            ["--catalog-url", args.catalog_url]
                            if args.catalog_url
                            else []
                        ),
                        *(["--payload", payload] if args.offline_bundle else []),
                        *(
                            ["--windows-arch", args.windows_arch]
                            if "windows" in selected and args.windows_arch
                            else []
                        ),
                        *[
                            "--" + ("mac" if name == "macos" else name)
                            for name in selected
                        ],
                    ],
                    env=env,
                )
                record = json.loads((compiled / "build-info.json").read_text())
                if (
                    record["installerVersion"] != installer_version
                    or bool(record.get("embeddedPayload")) != args.offline_bundle
                ):
                    raise ValueError(
                        "Неверная версия или обязательный встроенный payload установщика"
                    )
                if set(record["platforms"]) != expected_artifacts:
                    raise ValueError(
                        "Список собранных платформ и архитектур не совпадает с выбранным"
                    )
                for platform_name, entry in record["platforms"].items():
                    tool = compiled / entry["file"]
                    if sha(tool) != entry["sha256"]:
                        raise ValueError(f"Повреждён установщик {platform_name}")
                    folder = (
                        work
                        / f"VoyahTune-Installer-{installer_version}-{platform_name}"
                    )
                    folder.mkdir()
                    if platform_name == "macos":
                        with tarfile.open(tool) as archive:
                            archive.extractall(folder, filter="data")
                    else:
                        filename = (
                            "VoyahTune-Installer.exe"
                            if platform_name.startswith("windows-")
                            else "VoyahTune-Installer.run"
                        )
                        shutil.copy2(tool, folder / filename)
                    (folder / "README.txt").write_text(
                        "Распакуйте ZIP и запустите GUI. Выберите версию VoyahTune из каталога или откройте локальный payload ZIP. ADB и ресурсы удаления входят в установщик.\nLinux: chmod +x VoyahTune-Installer.run; ./VoyahTune-Installer.run\n"
                    )
                    zip_tree(folder, packages / f"{folder.name}.zip")
                (packages / "SHA256SUMS").write_text(
                    "".join(
                        f"{sha(p)}  {p.name}\n" for p in sorted(packages.glob("*.zip"))
                    )
                )
                (packages / "release.json").write_text(
                    json.dumps(record, ensure_ascii=False, indent=2) + "\n"
                )
            destination.parent.mkdir(parents=True, exist_ok=True)
            outputs = [(payload, payload_output)]
            if desktop_build:
                outputs.append((packages, destination))
            if not args.no_zip:
                archive = work / f"payload_{version}.zip"
                zip_payload(payload, archive)
                entry = payload_entry(payload, archive, args.minimum_ota_version)
                entry_file = work / f"payload_{version}.json"
                entry_file.write_text(
                    json.dumps(entry, ensure_ascii=False, indent=2) + "\n"
                )
                outputs.extend(
                    [
                        (archive, ROOT / "Releases/dist" / archive.name),
                        (entry_file, ROOT / "Releases/dist" / entry_file.name),
                    ]
                )
            publish_outputs(outputs)
            print(f"Payload: {payload_output}")
            if not args.no_zip:
                print(f'ZIP: {ROOT / "Releases/dist" / archive.name}')
            if desktop_build:
                print(f'GUI: {destination}; платформы: {", ".join(selected)}')
    finally:
        lock.rmdir()


if __name__ == "__main__":
    main()
