"""Plugin link setup preserves existing content and requires real sources."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "app/src/main/jni/cmake/RimePluginLinks.cmake"


class PluginDirectoryLinksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source with spaces"
        self.destination = self.root / "plugin with spaces"
        self.source.mkdir()
        (self.source / "CMakeLists.txt").write_text("plugin", encoding="utf-8")
        self.script = self.root / "test.cmake"
        self.script.write_text(
            f'include("{HELPER.as_posix()}")\n'
            f'trime_link_plugin_directory("{self.source.as_posix()}" '
            f'"{self.destination.as_posix()}" CMakeLists.txt)\n', encoding="utf-8"
        )

    def configure(self):
        return subprocess.run([os.environ.get("CMAKE_EXECUTABLE", "cmake"), "-P", str(self.script)],
                              text=True, capture_output=True, timeout=30)

    def test_link_uses_existing_source_without_copying_it(self):
        result = self.configure()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.destination / "CMakeLists.txt").read_text(encoding="utf-8"), "plugin")
        self.assertEqual(self.configure().returncode, 0)

    def test_incomplete_existing_directory_is_not_removed(self):
        self.destination.mkdir()
        marker = self.destination / "user-data"
        marker.write_text("preserve", encoding="utf-8")
        result = self.configure()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(encoding="utf-8"), "preserve")

    def test_complete_directory_is_not_accepted_or_removed(self):
        self.destination.mkdir()
        marker = self.destination / "CMakeLists.txt"
        marker.write_text("user source", encoding="utf-8")
        result = self.configure()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(encoding="utf-8"), "user source")

    def test_existing_link_to_different_source_is_rejected(self):
        self.assertEqual(self.configure().returncode, 0)
        other = self.root / "other source"
        other.mkdir()
        (other / "CMakeLists.txt").write_text("different", encoding="utf-8")
        self.script.write_text(
            f'include("{HELPER.as_posix()}")\n'
            f'trime_link_plugin_directory("{other.as_posix()}" '
            f'"{self.destination.as_posix()}" CMakeLists.txt)\n', encoding="utf-8"
        )
        self.assertNotEqual(self.configure().returncode, 0)
        self.assertEqual((self.destination / "CMakeLists.txt").read_text(encoding="utf-8"), "plugin")


if __name__ == "__main__":
    unittest.main()
