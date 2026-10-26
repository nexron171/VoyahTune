"""Reversible structure-aware navigation font updates for H97X/H97Y QNX themes."""

from collections import Counter
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
MAX_THEME_SIZE = 160 * 1024 * 1024
THEME_PATHS = [
    Path("/usr/share/theme/0/gauge_normal.kzb"),
    Path("/usr/share/theme/1/gauge_classic.kzb"),
    Path("/usr/share/theme/5/gauge_elegant.kzb"),
]
TARGETS = Counter({"TBT.road": 2, "TBT.unit": 2, "TBTRoam.road": 1})
ORIGINAL_FONT = "HuaKangJinGangHei-Regular-2.ttf"
REPLACEMENT_FONT = "Roboto-Regular.ttf"
SUPPORTED_FONT_DIGESTS = {
    ORIGINAL_FONT: "b90b145194f1206b3d515e05d2bc2f27f2411aa0b6897f61755d7b812fe083c1",
    REPLACEMENT_FONT: "16466ef65064e6f3885a6d2806b8949ac1ac38b524dd0cf8fc96565eb4cc28e8",
}
# Recognize installations made by the original fixed-profile patcher.
LEGACY_PATCHES = {
    "38285920e14c625b5cd5add22462b5330f4be1e5bfc0a81e17ad24258af243d4": "ff51fd8c19a9e17f64a891af2a88cd9c23f51141d27043f07c34ba21bd533c30",
    "fde5c3d7c8cef13b555e833cba496c432d517ce1557f315c610a062b1a5f2863": "17b247fe7fa4b491f633119856bb30d077f25b063290f156cb19347c527f1aab",
    "8e4c3b732a51984bf6e390949a28fee290eb8966e2928f8a71531904840af685": "b0fee3ef1b4b0cb59e5f51925968d629b6e93a26fe5988e06bd1ffded876d707",
}


class UnsupportedThemeError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise UnsupportedThemeError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Reader:
    def __init__(self, data, position=0, limit=None, endian="<"):
        self.data = data
        self.position = position
        self.limit = len(data) if limit is None else limit
        self.endian = endian
        require(0 <= position <= self.limit <= len(data), "Invalid reader boundaries")

    def take(self, length):
        require(
            0 <= length <= self.limit - self.position, "Record exceeds its resource"
        )
        start = self.position
        self.position += length
        return self.data[start : self.position]

    def number(self, kind="I"):
        length = struct.calcsize(kind)
        return struct.unpack(self.endian + kind, self.take(length))[0]

    def text(self):
        start = self.position
        length = self.number("H")
        text = self.take(length).decode("utf8")
        padding = (-(2 + length)) % 4
        require(self.take(padding) == b"\0" * padding, "Nonzero string padding")
        return text, start + 2


def resource_directory(data):
    require(data[:4] == b".KZB", "Unsupported KZB signature")
    require(struct.unpack_from(">I", data, 4)[0] == len(data), "KZB length mismatch")
    require(
        struct.unpack_from(">4H", data, 16) == (3, 6, 18, 47), "Unsupported KZB version"
    )
    header_end = struct.unpack_from(">I", data, 8)[0]
    root = Reader(data, 24, header_end, ">")
    require([root.number(), root.number()] == [1, 1], "Unsupported KZB header flags")
    identifier_length = root.number()
    require(identifier_length <= 128, "Unexpected project identifier")
    root.take(identifier_length)
    root.number()
    stem, _ = root.text()
    require(root.position == header_end, "Unexpected KZB header layout")
    reader = Reader(data, header_end, endian=">")
    directory_length = reader.number()
    expected_count = reader.number()
    directory_end = header_end + directory_length
    require(
        directory_end <= len(data) and expected_count <= 50000,
        "Invalid resource directory",
    )
    reader.limit = directory_end
    entries = []

    def read_folder(parent="", depth=0):
        require(depth <= 32, "Directory nesting limit exceeded")
        start = reader.position
        length = reader.number()
        label, _ = reader.text()
        path = parent + "/" + label if label else parent
        for _ in range(reader.number()):
            read_folder(path, depth + 1)
        count = reader.number()
        require(count <= expected_count, "Invalid resource count")
        for _ in range(count):
            offset = reader.number()
            size = reader.number()
            kind = reader.number()
            compression = reader.number()
            name, _ = reader.text()
            require(
                directory_end <= offset <= len(data), "Resource starts outside data"
            )
            require(0 <= size <= len(data) - offset, "Resource range exceeds file")
            entries.append(
                {
                    "path": path + "/" + name,
                    "offset": offset,
                    "length": size,
                    "type": kind,
                    "compression": compression,
                }
            )
        require(reader.position == start + length, "Folder length mismatch")

    read_folder()
    require(
        reader.take(directory_end - reader.position) == b"\0" * 8,
        "Unexpected directory trailer",
    )
    require(len(entries) == expected_count, "Directory count mismatch")
    require(
        len({entry["path"] for entry in entries}) == len(entries),
        "Duplicate resource path",
    )
    ordered = sorted(entries, key=lambda entry: entry["offset"])
    for previous, current in zip(ordered, ordered[1:]):
        require(
            previous["offset"] + previous["length"] <= current["offset"],
            "Overlapping resources",
        )
        previous["wireEnd"] = current["offset"]
    ordered[-1]["wireEnd"] = len(data)
    return stem, directory_end, entries


def properties(reader, count):
    require(count <= 1000, "Unexpected property count")
    records = []
    for _ in range(count):
        start = reader.position
        length = reader.number()
        require(20 <= length <= reader.limit - start, "Invalid property range")
        name, _ = reader.text()
        flags, kind = reader.number(), reader.number()
        value_start = reader.position
        end = start + length
        require(value_start <= end, "Property header exceeds its record")
        records.append(
            {
                "name": name,
                "start": start,
                "end": end,
                "flags": flags,
                "type": kind,
                "valueStart": value_start,
            }
        )
        reader.position = end
    return records


def text_binding(reader):
    require(
        [reader.number() for _ in range(5)] == [0, 0, 1, 1, 1],
        "Unsupported binding structure",
    )
    source, _ = reader.text()
    binding, binding_offset = reader.text()
    require(source == "#gauge", "Unexpected binding source")
    require(
        [reader.number() for _ in range(4)] == [9, 0, 0, 1],
        "Unsupported binding argument structure",
    )
    destination, _ = reader.text()
    require(destination == "TextBlockConcept.Text", "Binding does not drive node text")
    require([reader.number(), reader.number()] == [9, 1], "Ambiguous text binding")
    processor, _ = reader.text()
    require(
        processor == "Kanzi.ExpressionBindingProcessor", "Unexpected text processor"
    )
    records = properties(reader, reader.number())
    names = [record["name"] for record in records]
    require(
        names
        == [
            "ExpressionBindingProcessor.Argument1",
            "ExpressionBindingProcessor.Argument1Field",
            "ExpressionBindingProcessor.Argument2",
            "ExpressionBindingProcessor.Argument2Field",
            "ExpressionBindingProcessor.Argument3",
            "ExpressionBindingProcessor.Argument3Field",
            "ExpressionBindingProcessor.Operation",
            "ExpressionBindingProcessor.Output",
            "ExpressionBindingProcessor.OutputField",
        ],
        "Unsupported expression property set",
    )
    trailing = reader.take(reader.limit - reader.position)
    require(trailing == b"\0" * 8, "Unexpected node trailer")
    return binding, binding_offset


def inspect_layout(data, expected_stem=None):
    stem, directory_end, entries = resource_directory(data)
    require(expected_stem is None or stem == expected_stem, "Unexpected theme name")
    by_path = {entry["path"]: entry for entry in entries}
    for name, expected in SUPPORTED_FONT_DIGESTS.items():
        entry = by_path.get("/Resource Files/Fonts/" + name)
        require(entry is not None, "Required embedded font is absent")
        require(
            entry["compression"] == 0 and entry["type"] == 2031,
            "Unsupported embedded font resource",
        )
        blob = data[entry["offset"] : entry["wireEnd"]]
        require(
            blob[:8] == b"\0" * 8 and digest(blob[8:]) == expected,
            "Embedded font differs from the inspected font",
        )
    fields = []
    numeric = []
    for entry in entries:
        if entry["type"] != 180 or entry["compression"] != 0:
            continue
        start, end = entry["offset"], entry["wireEnd"]
        body = data[start:end]
        if b"Kanzi.TextBlock2D" not in body[:40]:
            continue
        if not any(name.encode() in body for name in (*TARGETS, "TBT.distance")):
            continue
        reader = Reader(data, start, end)
        require(reader.number() == 2, "Unsupported text node version")
        reader.number("H")
        class_name, _ = reader.text()
        require(class_name == "Kanzi.TextBlock2D", "Unexpected node class")
        require(reader.number() == 0, "Unsupported node flags")
        records = properties(reader, reader.number())
        binding, _ = text_binding(reader)
        if binding == "TBT.distance":
            numeric.append((start, end))
            continue
        require(binding in TARGETS, "Unexpected navigation text binding")
        font_records = [record for record in records if record["name"] == "Node.Font"]
        require(len(font_records) == 1, "Ambiguous navigation font")
        record = font_records[0]
        require(
            record["flags"] == 0 and record["type"] == 16,
            "Unexpected font property type",
        )
        value = Reader(data, record["valueStart"], record["end"])
        url, _ = value.text()
        require(
            not any(value.take(value.limit - value.position)),
            "Nonzero bytes follow the font URL",
        )
        prefix = "kzb://" + stem + "/Resource Files/Fonts/"
        require(
            url in (prefix + ORIGINAL_FONT, prefix + REPLACEMENT_FONT),
            "Unexpected font URL",
        )
        fields.append(
            {
                "binding": binding,
                "url": url,
                "start": record["valueStart"],
                "end": record["end"],
            }
        )
    require(
        Counter(field["binding"] for field in fields) == TARGETS,
        "Unexpected navigation node set",
    )
    require(len(numeric) == 2, "Unexpected numeric distance node set")
    states = {field["url"].split("/")[-1] for field in fields}
    require(len(states) == 1, "Partially patched navigation fonts")
    fields.sort(key=lambda field: field["start"])
    for previous, current in zip(fields, fields[1:]):
        require(previous["end"] <= current["start"], "Overlapping font values")
    return {
        "stem": stem,
        "fields": fields,
        "numeric": numeric,
        "state": "original" if states == {ORIGINAL_FONT} else "patched",
    }


def transform(data, restore=False, expected_stem=None):
    layout = inspect_layout(data, expected_stem)
    output = bytearray(data)
    name = ORIGINAL_FONT if restore else REPLACEMENT_FONT
    for field in layout["fields"]:
        encoded = ("kzb://" + layout["stem"] + "/Resource Files/Fonts/" + name).encode()
        value = struct.pack("<H", len(encoded)) + encoded
        capacity = field["end"] - field["start"]
        require(len(value) <= capacity, "Font URL does not fit the property")
        output[field["start"] : field["end"]] = value.ljust(capacity, b"\0")
    result = bytes(output)
    require(len(result) == len(data), "Theme size changed")
    previous_end = 0
    for field in layout["fields"]:
        require(
            data[previous_end : field["start"]]
            == result[previous_end : field["start"]],
            "Changed bytes outside a font value",
        )
        previous_end = field["end"]
    require(data[previous_end:] == result[previous_end:], "Changed trailing bytes")
    checked = inspect_layout(result, expected_stem)
    require(
        checked["state"] == ("original" if restore else "patched"),
        "Font update verification failed",
    )
    return result


def read_file(path, limit=MAX_THEME_SIZE):
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        raise ValueError("Expected a regular file without links: " + str(path))
    require(metadata.st_size <= limit, "File exceeds the theme size limit")
    data = path.read_bytes()
    require(len(data) == metadata.st_size, "File changed while reading")
    return data, metadata


def collect():
    themes = []
    sources = {}
    for path in THEME_PATHS:
        try:
            data, metadata = read_file(path)
            layout = inspect_layout(data, path.stem)
            sources[str(path)] = (data, metadata, layout)
            themes.append(
                {
                    "path": str(path),
                    "supported": True,
                    "sha256": digest(data),
                    "size": len(data),
                    "fontFields": len(layout["fields"]),
                    "state": layout["state"],
                }
            )
        except (UnsupportedThemeError, struct.error, UnicodeError) as error:
            themes.append({"path": str(path), "supported": False, "error": str(error)})
    result = {
        "themes": themes,
        "readOnly": read_only(),
        "recoveryRequired": (STATE_DIRECTORY / "transaction.json").exists(),
    }
    if any(not theme["supported"] for theme in themes):
        result.update({"skipped": True, "reason": "unsupported_theme"})
    return result, sources


def inspect():
    return collect()[0]


def sync_directory(directory):
    descriptor = os.open(str(directory), os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def remove_owned_file(path):
    if path.is_symlink():
        raise ValueError("Unexpected symlink: " + str(path))
    if path.exists():
        if not stat.S_ISREG(path.lstat().st_mode):
            raise ValueError("Unexpected file type: " + str(path))
        path.unlink()


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
    return bool(os.statvfs(THEME_PATHS[0]).f_flag & os.ST_RDONLY)


def remount(readonly):
    subprocess.run(["/bin/mount", "-ur" if readonly else "-uw", "/"], check=True)
    if read_only() != readonly:
        raise RuntimeError("QNX root mount mode did not change")


def stage_path(path):
    return path.with_name(path.name + ".voyahtune-font.new")


def before_path(path):
    return STATE_DIRECTORY / (path.name + ".before")


def valid_digest(value):
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(c in "0123456789abcdef" for c in value)
    )


def backup_path(path, source_hash):
    if not valid_digest(source_hash):
        raise ValueError("Invalid original backup hash")
    return STATE_DIRECTORY / (path.name + "." + source_hash + ".original")


def receipts():
    path = STATE_DIRECTORY / "originals.json"
    if not path.exists() and not path.is_symlink():
        return {"schema": 2, "themes": {}}
    record = json.loads(read_file(path, 65536)[0])
    if record.get("schema") != 2 or not isinstance(record.get("themes"), dict):
        raise ValueError("Invalid original theme index")
    if not set(record["themes"]).issubset({str(path) for path in THEME_PATHS}):
        raise ValueError("Unexpected original theme path")
    for entry in record["themes"].values():
        if not isinstance(entry, dict) or not all(
            valid_digest(entry.get(key)) for key in ("originalSha256", "patchedSha256")
        ):
            raise ValueError("Invalid original theme hashes")
    return record


def original_data(path, data, layout, record):
    if layout["state"] == "original":
        return data
    entry = record["themes"].get(str(path))
    if entry is not None and digest(data) == entry["patchedSha256"]:
        original = read_file(backup_path(path, entry["originalSha256"]))[0]
        if (
            digest(original) != entry["originalSha256"]
            or transform(original, expected_stem=path.stem) != data
        ):
            raise ValueError("Invalid original backup: " + str(path))
        return original
    expected_original = LEGACY_PATCHES.get(digest(data))
    if expected_original is not None:
        original = transform(data, restore=True, expected_stem=path.stem)
        if digest(original) != expected_original:
            raise ValueError("Legacy original theme verification failed")
        legacy = STATE_DIRECTORY / (path.name + ".original")
        if legacy.exists() or legacy.is_symlink():
            if read_file(legacy)[0] != original:
                raise ValueError("Invalid legacy original backup")
        return original
    return None


def recover_transaction():
    journal = STATE_DIRECTORY / "transaction.json"
    if not journal.exists() and not journal.is_symlink():
        return
    record = json.loads(read_file(journal, 65536)[0])
    if record.get("schema") not in (1, 2) or type(record.get("readOnly")) is not bool:
        raise ValueError("Unsupported font recovery journal")
    before = record.get("before", {})
    if set(before) != {str(path) for path in THEME_PATHS}:
        raise ValueError("Unexpected recovery paths")
    after = record.get("after", {})
    if record["schema"] == 2 and set(after) != set(before):
        raise ValueError("Unexpected recovery target paths")
    prepared = []
    for path in THEME_PATHS:
        if not valid_digest(before[str(path)]):
            raise ValueError("Invalid transaction hash")
        previous = read_file(before_path(path))[0]
        if digest(previous) != before[str(path)]:
            raise ValueError("Invalid transaction backup")
        inspect_layout(previous, path.stem)
        current, metadata = read_file(path)
        allowed = {before[str(path)]}
        if record["schema"] == 2:
            if not valid_digest(after[str(path)]):
                raise ValueError("Invalid transaction target hash")
            allowed.add(after[str(path)])
        else:
            allowed.update(
                digest(transform(previous, restore=restore, expected_stem=path.stem))
                for restore in (False, True)
            )
        require(
            digest(current) in allowed,
            "Theme changed after the interrupted transaction",
        )
        prepared.append((path, previous, metadata, digest(current)))
    try:
        if read_only() and any(
            digest(data) != current for _, data, _, current in prepared
        ):
            remount(False)
        for path, previous, metadata, current in prepared:
            staged = stage_path(path)
            remove_owned_file(staged)
            if digest(previous) != current:
                write_file(staged, previous, metadata)
                if digest(path.read_bytes()) != current:
                    raise RuntimeError("Theme changed during recovery")
                os.replace(str(staged), str(path))
                sync_directory(path.parent)
                if digest(path.read_bytes()) != digest(previous):
                    raise RuntimeError("Recovered theme verification failed")
    finally:
        if read_only() != record["readOnly"]:
            remount(record["readOnly"])
    journal.unlink()
    sync_directory(STATE_DIRECTORY)


def update(action):
    current, sources = collect()
    if current.get("skipped"):
        return current
    try:
        if current["recoveryRequired"]:
            recover_transaction()
            current, sources = collect()
            if current.get("skipped"):
                return current
    except UnsupportedThemeError as error:
        current.update(
            {"skipped": True, "reason": "unsupported_theme", "error": str(error)}
        )
        return current
    record = receipts()
    targets = {}
    originals = {}
    for path in THEME_PATHS:
        data, _, layout = sources[str(path)]
        original = original_data(path, data, layout, record)
        if action == "restore":
            targets[str(path)] = data if original is None else original
        else:
            targets[str(path)] = transform(data, expected_stem=path.stem)
            if original is not None:
                originals[str(path)] = original
    changed = [
        path for path in THEME_PATHS if targets[str(path)] != sources[str(path)][0]
    ]
    if not changed:
        return current
    total = sum(len(data) for data, _, _ in sources.values())
    for folder, required in (
        (THEME_PATHS[0].parent, total),
        (STATE_DIRECTORY, total * 2),
    ):
        filesystem = os.statvfs(str(folder))
        if filesystem.f_bavail * filesystem.f_frsize < required + 32 * 1024 * 1024:
            raise RuntimeError(
                "Insufficient space for theme transaction: " + str(folder)
            )
    if action == "apply":
        for path in THEME_PATHS:
            original = originals.get(str(path))
            if original is None:
                continue
            source_hash = digest(original)
            backup = backup_path(path, source_hash)
            if backup.exists() or backup.is_symlink():
                if read_file(backup)[0] != original:
                    raise ValueError("Invalid original backup: " + str(backup))
            else:
                write_atomic(backup, original)
            record["themes"][str(path)] = {
                "originalSha256": source_hash,
                "patchedSha256": digest(targets[str(path)]),
            }
        write_atomic(STATE_DIRECTORY / "originals.json", json.dumps(record).encode())
    initial_read_only = read_only()
    journal_record = {
        "schema": 2,
        "readOnly": initial_read_only,
        "before": {},
        "after": {},
    }
    for path in THEME_PATHS:
        data = sources[str(path)][0]
        remove_owned_file(before_path(path))
        write_file(before_path(path), data)
        journal_record["before"][str(path)] = digest(data)
        journal_record["after"][str(path)] = digest(targets[str(path)])
    journal = STATE_DIRECTORY / "transaction.json"
    write_atomic(journal, json.dumps(journal_record).encode())
    try:
        if initial_read_only:
            remount(False)
        for path in changed:
            staged = stage_path(path)
            remove_owned_file(staged)
            write_file(staged, targets[str(path)], sources[str(path)][1])
            if read_file(staged)[0] != targets[str(path)]:
                raise RuntimeError("Staged theme verification failed")
        for path in THEME_PATHS:
            if digest(read_file(path)[0]) != journal_record["before"][str(path)]:
                raise RuntimeError("Theme changed during staging")
        for path in changed:
            os.replace(str(stage_path(path)), str(path))
            sync_directory(path.parent)
            if digest(read_file(path)[0]) != journal_record["after"][str(path)]:
                raise RuntimeError("Installed theme verification failed")
        result = inspect()
        if result.get("skipped"):
            raise RuntimeError("Installed theme structure verification failed")
    except BaseException:
        recover_transaction()
        raise
    finally:
        if read_only() != initial_read_only:
            remount(initial_read_only)
    journal.unlink()
    for path in THEME_PATHS:
        remove_owned_file(before_path(path))
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
