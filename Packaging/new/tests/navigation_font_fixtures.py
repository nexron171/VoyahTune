"""Small KZB containers with real directory, property and binding records."""

from pathlib import Path
import struct

ORIGINAL_FONT = "HuaKangJinGangHei-Regular-2.ttf"
REPLACEMENT_FONT = "Roboto-Regular.ttf"
FONT_DATA = {
    ORIGINAL_FONT: b"test original font",
    REPLACEMENT_FONT: b"test Roboto font",
}


def text(value, endian="<"):
    encoded = value.encode()
    result = struct.pack(endian + "H", len(encoded)) + encoded
    return result + b"\0" * (-len(result) % 4)


def property_record(name, value, kind=2):
    body = text(name) + struct.pack("<II", 0, kind) + value
    return struct.pack("<I", len(body) + 4) + body


def node(stem, binding, patched, extra_properties):
    records = [
        property_record("Node.Height", struct.pack("<f", 40.0), 3)
    ] * extra_properties
    if binding != "TBT.distance":
        font = REPLACEMENT_FONT if patched else ORIGINAL_FONT
        url = ("kzb://" + stem + "/Resource Files/Fonts/" + font).encode()
        value = (struct.pack("<H", len(url)) + url).ljust(76, b"\0")
        records.append(property_record("Node.Font", value, 16))
    records.append(
        property_record("TextBlockConcept.FontSize", struct.pack("<f", 28.0), 3)
    )
    output = struct.pack("<IH", 2, 4231) + text("Kanzi.TextBlock2D")
    output += struct.pack("<II", 0, len(records)) + b"".join(records)
    output += struct.pack("<5I", 0, 0, 1, 1, 1) + text("#gauge") + text(binding)
    output += struct.pack("<4I", 9, 0, 0, 1) + text("TextBlockConcept.Text")
    output += struct.pack("<II", 9, 1) + text("Kanzi.ExpressionBindingProcessor")
    properties = []
    for name in (
        "Argument1",
        "Argument1Field",
        "Argument2",
        "Argument2Field",
        "Argument3",
        "Argument3Field",
        "Operation",
        "Output",
        "OutputField",
    ):
        properties.append(
            property_record("ExpressionBindingProcessor." + name, struct.pack("<I", 9))
        )
    output += struct.pack("<I", len(properties)) + b"".join(properties) + b"\0" * 8
    return output


def build_theme(stem, patched=False, extra_properties=0, padding=0):
    entries = []
    for name, data in FONT_DATA.items():
        entries.append(
            {
                "path": "/Resource Files/Fonts/" + name,
                "type": 2031,
                "data": b"\0" * 8 + data,
                "offset": 0,
            }
        )
    entries.append(
        {
            "path": "/Other/opaque",
            "type": 1000,
            "data": b"other-resource" + b"x" * padding,
            "offset": 0,
        }
    )
    for index, binding in enumerate(
        (
            "TBT.road",
            "TBT.unit",
            "TBT.distance",
            "TBT.road",
            "TBT.unit",
            "TBT.distance",
            "TBTRoam.road",
        )
    ):
        entries.append(
            {
                "path": "/Prefabs/navigation/node" + str(index),
                "type": 180,
                "data": node(stem, binding, patched, extra_properties),
                "offset": 0,
            }
        )
    tree = {"children": {}, "files": []}
    for entry in entries:
        branch = tree
        parts = Path(entry["path"]).parts[1:]
        for part in parts[:-1]:
            branch = branch["children"].setdefault(part, {"children": {}, "files": []})
        branch["files"].append((parts[-1], entry))

    def folder(name, branch):
        body = text(name, ">") + struct.pack(">I", len(branch["children"]))
        body += b"".join(
            folder(label, child) for label, child in branch["children"].items()
        )
        body += struct.pack(">I", len(branch["files"]))
        for label, entry in branch["files"]:
            body += struct.pack(
                ">4I", entry["offset"], len(entry["data"]) - 4, entry["type"], 0
            )
            body += text(label, ">")
        return struct.pack(">I", len(body) + 4) + body

    header = b".KZB" + struct.pack(">3I", 0, 0, 140) + struct.pack(">4H", 3, 6, 18, 47)
    header += (
        struct.pack(">3I", 1, 1, 36)
        + b"0" * 36
        + struct.pack(">I", 40)
        + text(stem, ">")
    )
    root = folder("", tree)
    directory_size = len(root) + 16
    position = len(header) + directory_size
    for entry in entries:
        entry["offset"] = position
        position += len(entry["data"])
    header = bytearray(header)
    struct.pack_into(">II", header, 4, position, len(header))
    directory = (
        struct.pack(">II", directory_size, len(entries)) + folder("", tree) + b"\0" * 8
    )
    return bytes(header) + directory + b"".join(entry["data"] for entry in entries)
