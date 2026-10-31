#!/usr/bin/env python3
"""Validate an extracted Boost tree against the exact pinned source archive."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import tarfile
import tempfile

PREFIX = "boost-1.92.0"
SKIPPED_DOC_LINK = f"{PREFIX}/libs/decimal/doc/modules/ROOT/examples"
ARCHIVE_SHA256 = "9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d"


def is_link(path):
    return path.is_symlink() or getattr(path, "is_junction", lambda: False)()


def file_hash(stream):
    digest = hashlib.sha256()
    while block := stream.read(1024 * 1024):
        digest.update(block)
    return digest.digest()


def verify(archive, tree):
    with archive.open("rb") as raw:
        if file_hash(raw).hex() != ARCHIVE_SHA256:
            raise ValueError("Boost archive SHA-256 mismatch")
    if is_link(tree):
        raise ValueError("Boost source tree must not be a symlink")
    root = tree.resolve(strict=True)
    expected = set()
    # Streaming avoids repeatedly seeking and re-decompressing the xz archive.
    with tarfile.open(archive, mode="r|xz") as source:
        for member in source:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != PREFIX:
                raise ValueError(f"Invalid Boost archive path: {member.name}")
            if member.name == PREFIX:
                continue
            relative = PurePosixPath(*path.parts[1:])
            if member.name == SKIPPED_DOC_LINK and member.issym() and member.linkname == "../../../example":
                # The only archive symlink points to documentation examples;
                # it is not needed for the build and can be absent on Windows.
                continue
            if not member.isdir() and not member.isfile():
                raise ValueError(f"Unsupported Boost archive entry: {member.name}")
            if member.isdir():
                continue
            expected.add(relative.as_posix())
            target = root.joinpath(*relative.parts)
            if is_link(target) or not target.is_file() or target.stat().st_size != member.size:
                raise ValueError(f"Missing or altered Boost file: {relative}")
            with source.extractfile(member) as original, target.open("rb") as extracted:
                if file_hash(original) != file_hash(extracted):
                    raise ValueError(f"Altered Boost file: {relative}")
            if len(expected) % 10000 == 0:
                print(f"Verified {len(expected)} Boost files", flush=True)
    actual = set()
    for directory, dirs, files in os.walk(root, followlinks=False):
        base = Path(directory)
        for name in dirs + files:
            item = base / name
            if is_link(item):
                if (item.relative_to(root).as_posix() != SKIPPED_DOC_LINK.split("/", 1)[1]
                        or os.readlink(item).replace("\\", "/") != "../../../example"):
                    raise ValueError(f"Unexpected Boost symlink: {item}")
                continue
            if item.is_file():
                actual.add(item.relative_to(root).as_posix())
    if actual != expected:
        raise ValueError(f"Unexpected Boost files: {sorted(actual - expected)[:5]}")


def extract(archive, tree):
    # Never replace a pre-existing source directory: it may contain user data.
    if tree.exists() or is_link(tree):
        raise ValueError(f"Boost destination already exists: {tree}")
    with archive.open("rb") as raw:
        if file_hash(raw).hex() != ARCHIVE_SHA256:
            raise ValueError("Boost archive SHA-256 mismatch")
    tree.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".trime-boost-", dir=tree.parent) as temporary:
        def safe_entry(member, destination):
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != PREFIX:
                raise ValueError(f"Invalid Boost archive path: {member.name}")
            if member.name == SKIPPED_DOC_LINK and member.issym() and member.linkname == "../../../example":
                return None
            if not member.isdir() and not member.isfile():
                raise ValueError(f"Unsupported Boost archive entry: {member.name}")
            return tarfile.data_filter(member, destination)

        with tarfile.open(archive, mode="r:xz") as source:
            source.extractall(temporary, filter=safe_entry)
        staged = Path(temporary) / PREFIX
        verify(archive, staged)
        if tree.exists() or is_link(tree):
            raise ValueError(f"Boost destination appeared during extraction: {tree}")
        # Publish the verified staging directory with a same-parent rename.
        staged.rename(tree)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("tree", type=Path)
    parser.add_argument("--extract", action="store_true")
    args = parser.parse_args()
    if args.extract:
        extract(args.archive, args.tree)
    else:
        verify(args.archive, args.tree)
    print("Verified Boost source tree against pinned archive", flush=True)
