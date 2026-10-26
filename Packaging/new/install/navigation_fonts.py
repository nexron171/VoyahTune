"""Reversible navigation font update for verified H97X/H97Y QNX themes."""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import sys

STATE_DIRECTORY = Path("/var/data/voyahtune-navigation-font")
RESULT_PREFIX = "VOYAHTUNE_NAVIGATION_FONT_RESULT "
PROFILES = [
    {
        "path": "/usr/share/theme/0/gauge_normal.kzb",
        "size": 27382822,
        "originalSha256": "ff51fd8c19a9e17f64a891af2a88cd9c23f51141d27043f07c34ba21bd533c30",
        "patchedSha256": "38285920e14c625b5cd5add22462b5330f4be1e5bfc0a81e17ad24258af243d4",
        "fields": [
            ["TBT.road", 7363522, 7363894],
            ["TBT.unit", 7365530, 7365810],
            ["TBT.unit", 7383668, 7383848],
            ["TBT.road", 7384526, 7384706],
            ["TBTRoam.road", 7392313, 7392685],
        ],
    },
    {
        "path": "/usr/share/theme/1/gauge_classic.kzb",
        "size": 26572879,
        "originalSha256": "17b247fe7fa4b491f633119856bb30d077f25b063290f156cb19347c527f1aab",
        "patchedSha256": "fde5c3d7c8cef13b555e833cba496c432d517ce1557f315c610a062b1a5f2863",
        "fields": [
            ["TBTRoam.road", 7214969, 7215341],
            ["TBT.road", 7230620, 7230992],
            ["TBT.unit", 7232628, 7232908],
            ["TBT.unit", 7250770, 7250950],
            ["TBT.road", 7251628, 7251808],
        ],
    },
    {
        "path": "/usr/share/theme/5/gauge_elegant.kzb",
        "size": 27740646,
        "originalSha256": "b0fee3ef1b4b0cb59e5f51925968d629b6e93a26fe5988e06bd1ffded876d707",
        "patchedSha256": "8e4c3b732a51984bf6e390949a28fee290eb8966e2928f8a71531904840af685",
        "fields": [
            ["TBT.road", 6948703, 6949075],
            ["TBT.unit", 6950767, 6951047],
            ["TBT.unit", 6968909, 6969089],
            ["TBT.road", 6969767, 6969947],
            ["TBTRoam.road", 6976862, 6977234],
        ],
    },
]


class UnsupportedThemeError(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_resource(profile):
    path = Path(profile["path"])
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        raise ValueError(
            "Theme must be a regular file without hard links: " + str(path)
        )
    if metadata.st_size != profile["size"]:
        raise UnsupportedThemeError("Unsupported theme size: " + str(path))
    data = path.read_bytes()
    if len(data) != profile["size"] or digest(data) not in (
        profile["originalSha256"],
        profile["patchedSha256"],
    ):
        raise UnsupportedThemeError(
            "Unsupported or partially modified theme: " + str(path) + " " + digest(data)
        )
    return data, metadata


def transform(profile, data, restore=False):
    current_hash = digest(data)
    if len(data) != profile["size"] or current_hash not in (
        profile["originalSha256"],
        profile["patchedSha256"],
    ):
        raise ValueError("Unsupported theme hash: " + current_hash)
    prefix = ("kzb://" + Path(profile["path"]).stem + "/Resource Files/Fonts/").encode()
    original_font = prefix + b"HuaKangJinGangHei-Regular-2.ttf"
    replacement_font = prefix + b"Roboto-Regular.ttf"

    def font_value(font):
        return struct.pack("<H", len(font)) + font.ljust(len(original_font), b"\0")

    expected_font = (
        original_font if current_hash == profile["originalSha256"] else replacement_font
    )
    result = bytearray(data)
    previous_end = 0
    for binding, offset, binding_offset in profile["fields"]:
        start, end = offset - 2, offset + len(original_font)
        if struct.unpack_from("<I", data, offset - 26)[0] != 100:
            raise ValueError("Unexpected property size")
        if data[offset - 22 : offset - 10] != b"\x09\x00Node.Font\0":
            raise ValueError("Unexpected property name")
        if struct.unpack_from("<II", data, offset - 10) != (0, 16):
            raise ValueError("Unexpected property type")
        if data[start:end] != font_value(expected_font):
            raise ValueError("Unexpected font reference")
        if data[binding_offset : binding_offset + len(binding)] != binding.encode():
            raise ValueError("Unexpected navigation binding")
        if start < previous_end:
            raise ValueError("Overlapping font references")
        result[start:end] = font_value(original_font if restore else replacement_font)
        if result[previous_end:start] != data[previous_end:start]:
            raise ValueError("Changed bytes outside a font reference")
        previous_end = end
    if result[previous_end:] != data[previous_end:]:
        raise ValueError("Changed bytes outside the font references")
    expected = profile["originalSha256"] if restore else profile["patchedSha256"]
    if digest(result) != expected:
        raise ValueError("Unexpected patched theme hash")
    return bytes(result)


def sync_directory(directory):
    descriptor = os.open(str(directory), os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_file(path, data, metadata=None):
    with path.open("xb") as output:
        output.write(data)
        output.flush()
        if metadata is not None:
            os.fchown(output.fileno(), metadata.st_uid, metadata.st_gid)
            os.fchmod(output.fileno(), stat.S_IMODE(metadata.st_mode))
        else:
            os.fchmod(output.fileno(), 0o600)
        os.fsync(output.fileno())
    sync_directory(path.parent)


def write_atomic(path, data):
    temporary = path.with_name(path.name + ".new")
    remove_owned_file(temporary)
    write_file(temporary, data)
    os.replace(str(temporary), str(path))
    sync_directory(path.parent)


def read_only():
    return bool(os.statvfs(PROFILES[0]["path"]).f_flag & os.ST_RDONLY)


def remount(readonly):
    subprocess.run(["/bin/mount", "-ur" if readonly else "-uw", "/"], check=True)
    if read_only() != readonly:
        raise RuntimeError("QNX root mount mode did not change")


def stage_path(profile):
    return Path(profile["path"] + ".voyahtune-font.new")


def before_path(profile):
    return STATE_DIRECTORY / (Path(profile["path"]).name + ".before")


def remove_owned_file(path):
    if path.is_symlink():
        raise ValueError("Unexpected symlink: " + str(path))
    if path.exists():
        if not stat.S_ISREG(path.lstat().st_mode):
            raise ValueError("Unexpected file type: " + str(path))
        path.unlink()


def recover_transaction():
    journal = STATE_DIRECTORY / "transaction.json"
    if not journal.exists():
        return
    record = json.loads(journal.read_text())
    if record.get("schema") != 1 or type(record.get("readOnly")) is not bool:
        raise ValueError("Unsupported font recovery journal")
    entries = record.get("before", {})
    if set(entries) != {profile["path"] for profile in PROFILES}:
        raise ValueError("Unexpected recovery paths")
    for profile in PROFILES:
        read_resource(profile)
        if entries[profile["path"]] not in (
            profile["originalSha256"],
            profile["patchedSha256"],
        ):
            raise ValueError("Unexpected recovery hash")
        if digest(before_path(profile).read_bytes()) != entries[profile["path"]]:
            raise ValueError("Invalid transaction backup")
    try:
        if read_only():
            remount(False)
        for profile in PROFILES:
            current, metadata = read_resource(profile)
            staged = stage_path(profile)
            remove_owned_file(staged)
            if digest(current) != entries[profile["path"]]:
                write_file(staged, before_path(profile).read_bytes(), metadata)
                os.replace(str(staged), profile["path"])
                sync_directory(staged.parent)
    finally:
        if read_only() != record["readOnly"]:
            remount(record["readOnly"])
    journal.unlink()
    sync_directory(STATE_DIRECTORY)


def inspect():
    themes = []
    for profile in PROFILES:
        try:
            data, _ = read_resource(profile)
        except UnsupportedThemeError as error:
            themes.append(
                {"path": profile["path"], "supported": False, "error": str(error)}
            )
            continue
        transform(profile, data)
        themes.append(
            {
                "path": profile["path"],
                "supported": True,
                "sha256": digest(data),
                "fontFields": 5,
            }
        )
    result = {
        "themes": themes,
        "readOnly": read_only(),
        "recoveryRequired": (STATE_DIRECTORY / "transaction.json").exists(),
    }
    if any(not theme["supported"] for theme in themes):
        result.update({"skipped": True, "reason": "unsupported_theme"})
    return result


def update(action):
    current = inspect()
    if current.get("skipped"):
        return current
    if current["recoveryRequired"]:
        recover_transaction()
        current = inspect()
        if current.get("skipped"):
            return current
    target_key = "originalSha256" if action == "restore" else "patchedSha256"
    if all(
        theme["sha256"] == profile[target_key]
        for theme, profile in zip(current["themes"], PROFILES)
    ):
        return current
    initial_read_only = read_only()
    total = sum(profile["size"] for profile in PROFILES)
    space_requirements = [
        (Path(PROFILES[0]["path"]).parent, total),
        (STATE_DIRECTORY, total * 2),
    ]
    for folder, required in space_requirements:
        filesystem = os.statvfs(str(folder))
        if filesystem.f_bavail * filesystem.f_frsize < required + 32 * 1024 * 1024:
            raise RuntimeError(
                "Insufficient space for theme transaction: " + str(folder)
            )
    record = {"schema": 1, "readOnly": initial_read_only, "before": {}}
    for profile in PROFILES:
        data, _ = read_resource(profile)
        original = transform(profile, data, restore=True)
        backup = STATE_DIRECTORY / (Path(profile["path"]).name + ".original")
        if not backup.exists():
            write_atomic(backup, original)
        if digest(backup.read_bytes()) != profile["originalSha256"]:
            raise ValueError("Invalid original backup: " + str(backup))
        previous = before_path(profile)
        remove_owned_file(previous)
        write_file(previous, data)
        record["before"][profile["path"]] = digest(data)
    journal = STATE_DIRECTORY / "transaction.json"
    write_atomic(journal, json.dumps(record).encode())
    try:
        if initial_read_only:
            remount(False)
        for profile in PROFILES:
            data, metadata = read_resource(profile)
            staged = stage_path(profile)
            remove_owned_file(staged)
            result = transform(profile, data, restore=action == "restore")
            write_file(staged, result, metadata)
            if digest(staged.read_bytes()) != digest(result):
                raise RuntimeError("Staged theme verification failed")
        for profile in PROFILES:
            if (
                digest(Path(profile["path"]).read_bytes())
                != record["before"][profile["path"]]
            ):
                raise RuntimeError("Theme changed during staging")
        for profile in PROFILES:
            staged = stage_path(profile)
            os.replace(str(staged), profile["path"])
            sync_directory(staged.parent)
            expected = profile[target_key]
            if digest(Path(profile["path"]).read_bytes()) != expected:
                raise RuntimeError("Installed theme verification failed")
    except BaseException:
        recover_transaction()
        raise
    finally:
        if read_only() != initial_read_only:
            remount(initial_read_only)
    journal.unlink()
    for profile in PROFILES:
        remove_owned_file(before_path(profile))
    sync_directory(STATE_DIRECTORY)
    return inspect()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "apply", "restore"))
    parser.add_argument("--model", required=True, choices=("H97X", "H97Y"))
    arguments = parser.parse_args()
    if os.uname().sysname != "QNX" or os.geteuid() != 0:
        raise RuntimeError("The theme operation requires QNX root")
    if arguments.action == "inspect":
        result = inspect()
    else:
        if STATE_DIRECTORY.is_symlink():
            raise ValueError("Invalid recovery directory")
        STATE_DIRECTORY.mkdir(mode=0o700, exist_ok=True)
        with (STATE_DIRECTORY / "lock").open("a") as lock:
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = update(arguments.action)
    result.update({"action": arguments.action, "model": arguments.model, "ok": True})
    print(RESULT_PREFIX + json.dumps(result), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(
            RESULT_PREFIX + json.dumps({"ok": False, "error": str(error)}), flush=True
        )
        sys.exit(1)
