#!/usr/bin/env python3
"""Verify a published immutable payload before atomically adding it to the catalog."""

import argparse
import hashlib
import json
import subprocess
import tempfile
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]


class HTTPSRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlparse(newurl).scheme != "https":
            raise ValueError("Asset redirect must use HTTPS")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def verify_remote(entry):
    asset = entry
    url = asset["url"]
    if urlparse(url).scheme != "https":
        raise ValueError("Asset must use HTTPS")
    digest = hashlib.sha256()
    size = 0
    with urllib.request.build_opener(HTTPSRedirect()).open(url, timeout=30) as response:
        while block := response.read(1024 * 1024):
            size += len(block)
            if size > asset["size"]:
                raise ValueError("Published asset exceeds declared size")
            digest.update(block)
    if size != asset["size"] or digest.hexdigest() != asset["sha256"]:
        raise ValueError("Published asset differs from the locally verified payload")


def verify_remote_head(entry):
    """Check public access and upload metadata, without rehashing remote bytes."""
    if urlparse(entry["url"]).scheme != "https":
        raise ValueError("Asset must use HTTPS")
    request = urllib.request.Request(entry["url"], method="HEAD")

    # Reject redirects: preserve HEAD and never follow a redirect into a GET.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    with urllib.request.build_opener(NoRedirect()).open(
        request, timeout=30
    ) as response:
        if (
            int(response.headers.get("Content-Length", "-1")) != entry["size"]
            or response.headers.get("x-amz-meta-sha256") != entry["sha256"]
        ):
            raise ValueError(
                "Published asset size/SHA-256 upload metadata differs from local entry"
            )


def merge(index, entry):
    required = {"version", "url", "size", "sha256"}
    optional = {"minimumInstallerVersion", "minimumOtaVersion"}
    if not required <= set(entry) or set(entry) - required - optional:
        raise ValueError(
            "Release must contain version, url, size, sha256 and optional minimumInstallerVersion/minimumOtaVersion"
        )
    for old in index["releases"]:
        if old["version"] == entry["version"]:
            if any(old[k] != entry[k] for k in ("size", "sha256")):
                raise ValueError(
                    "Published version is immutable; choose a new release version"
                )
            changed = any(old.get(key) != entry.get(key) for key in {"url"} | optional)
            if changed:
                old.clear()
                old.update(entry)
            return changed
    index["releases"].append(entry)
    return True


def update(index_path, entry_path, builder, verify, verify_head=False):
    if verify and verify_head:
        raise ValueError("Choose either full download or HEAD verification")
    index = json.loads(index_path.read_text())
    if entry_path is not None:
        entry = json.loads(entry_path.read_text())
        changed = merge(index, entry)
    else:
        changed = False
    if index_path.name == "index-v4.json":
        for release in index["releases"]:
            if int(release["version"].split(".")[0]) < 4:
                raise ValueError(
                    "index-v4.json accepts only releases starting with 4.0.0"
                )
            if any(
                not isinstance(release.get(key), str) or not release[key]
                for key in ("minimumInstallerVersion", "minimumOtaVersion")
            ):
                raise ValueError(
                    "index-v4.json requires minimumInstallerVersion and minimumOtaVersion for every release"
                )
    # Core is the single format validator. Validate before network or modifying the index.
    with tempfile.TemporaryDirectory(dir=index_path.parent) as directory:
        staging = Path(directory) / "index.json"
        staging.write_text(json.dumps(index, ensure_ascii=False))
        normalized = subprocess.check_output(
            [str(builder), "verify-catalog", str(staging)], text=True
        )
        if entry_path is not None:
            if verify:
                verify_remote(entry)
            elif verify_head:
                verify_remote_head(entry)
            else:
                raise ValueError(
                    "--verify-remote or --verify-head is required before updating the public catalog"
                )
        if changed:
            staging.write_text(normalized)
            staging.replace(index_path)
    return changed


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--index", type=Path, default=ROOT / "Releases/ota/index.json")
    p.add_argument(
        "--entry", type=Path, help="Generated Releases/dist/payload_VERSION.json"
    )
    p.add_argument(
        "--builder",
        type=Path,
        default=ROOT / "Installer/target/release/installer-build",
    )
    verification = p.add_mutually_exclusive_group()
    verification.add_argument(
        "--verify-remote",
        action="store_true",
        help="Download the payload and verify its size/SHA-256",
    )
    verification.add_argument(
        "--verify-head",
        action="store_true",
        help="Check public HEAD size/upload metadata only; does not verify remote bytes",
    )
    args = p.parse_args()
    changed = update(
        args.index, args.entry, args.builder, args.verify_remote, args.verify_head
    )
    print("Catalog updated" if changed else "Catalog verified; unchanged")


if __name__ == "__main__":
    main()
