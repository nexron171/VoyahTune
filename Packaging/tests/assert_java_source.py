#!/usr/bin/env python3
"""Check Java source contracts independently of indentation and line wrapping."""

import argparse
from pathlib import Path
import re


def compact(source: str) -> str:
    literal = r'"(?:\\.|[^"\\])*"'
    source = re.sub(
        rf"{literal}(?:\s*\+\s*{literal})+",
        lambda match: '"' + "".join(part[1:-1] for part in re.findall(literal, match.group())) + '"',
        source,
    )
    return re.sub(r"\s+", "", source)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ordered", action="store_true")
    parser.add_argument("--count", type=int)
    parser.add_argument("source", type=Path)
    parser.add_argument("fragments", nargs="+")
    arguments = parser.parse_args()
    source = compact(arguments.source.read_text())
    offset = 0
    for fragment in arguments.fragments:
        expected = compact(fragment)
        if arguments.count is not None and source.count(expected) != arguments.count:
            raise SystemExit(f"{arguments.source}: expected {arguments.count} occurrences: {fragment}")
        position = source.find(expected, offset if arguments.ordered else 0)
        if position < 0:
            qualifier = " in order" if arguments.ordered else ""
            raise SystemExit(f"{arguments.source}: missing{qualifier}: {fragment}")
        if arguments.ordered:
            offset = position + len(expected)


if __name__ == "__main__":
    main()
