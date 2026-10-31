"""Boost cache validation rejects altered and untracked build inputs."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("verify_boost", ROOT / "app/src/main/jni/cmake/verify_boost.py")
verify_boost = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify_boost)


class BoostCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.archive = root / "boost.tar.xz"
        self.tree = root / "boost"
        self.tree.mkdir()
        content = b"audited source\n"
        with tarfile.open(self.archive, "w:xz") as output:
            entry = tarfile.TarInfo(f"{verify_boost.PREFIX}/include/example.hpp")
            entry.size = len(content)
            output.addfile(entry, io.BytesIO(content))
        target = self.tree / "include/example.hpp"
        target.parent.mkdir()
        target.write_bytes(content)
        previous = verify_boost.ARCHIVE_SHA256
        self.addCleanup(setattr, verify_boost, "ARCHIVE_SHA256", previous)
        verify_boost.ARCHIVE_SHA256 = hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def test_matching_cache_is_accepted(self):
        verify_boost.verify(self.archive, self.tree)

    def test_changed_or_untracked_cache_file_is_rejected(self):
        target = self.tree / "include/example.hpp"
        target.write_bytes(b"changed source\n")
        with self.assertRaisesRegex(ValueError, "altered|Altered"):
            verify_boost.verify(self.archive, self.tree)
        target.write_bytes(b"audited source\n")
        (self.tree / "injected.cmake").write_text("bad", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unexpected Boost files"):
            verify_boost.verify(self.archive, self.tree)

    def test_changed_archive_is_rejected(self):
        with self.archive.open("ab") as output:
            output.write(b"changed")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            verify_boost.verify(self.archive, self.tree)

    def test_extract_publishes_verified_content_and_never_overwrites(self):
        target = self.tree.parent / "new-boost"
        verify_boost.extract(self.archive, target)
        self.assertEqual((target / "include/example.hpp").read_bytes(), b"audited source\n")
        with self.assertRaisesRegex(ValueError, "already exists"):
            verify_boost.extract(self.archive, target)
        self.assertEqual((target / "include/example.hpp").read_bytes(), b"audited source\n")


if __name__ == "__main__":
    unittest.main()
