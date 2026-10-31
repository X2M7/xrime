# SPDX-License-Identifier: GPL-3.0-or-later
"""Ensure FindBoost resolves selected modules against the verified source tree."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "app/src/main/jni/cmake/FindBoost.cmake"
TREE_MODULE = ROOT / "app/src/main/jni/cmake/BoostTree.cmake"
CMAKE = os.environ.get("CMAKE_EXECUTABLE", "cmake")


class BoostIncludePathsTest(unittest.TestCase):
    def test_default_tree_is_outside_app_and_override_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            project = root / "project with spaces"
            source = project / "app/src/main/jni"
            source.mkdir(parents=True)
            (source / "CMakeLists.txt").write_text(
                'cmake_minimum_required(VERSION 3.18)\nproject(probe NONE)\n'
                'set(BOOST_VERSION 1.92.0)\n'
                f'include("{TREE_MODULE.as_posix()}")\n'
                'if(NOT BOOST_TREE STREQUAL EXPECTED_TREE)\n'
                '  message(FATAL_ERROR "Unexpected Boost tree: ${BOOST_TREE}")\n'
                'endif()\n', encoding="utf-8")
            for index, override in enumerate((None, root / "verified external tree")):
                expected = override or project / "build/xrime-boost-1.92.0"
                env = os.environ.copy()
                env.pop("TRIME_BOOST_TREE", None)
                if override:
                    env["TRIME_BOOST_TREE"] = str(override)
                result = subprocess.run(
                    [CMAKE, "-S", str(source), "-B", str(root / f"build-{index}"),
                     f"-DEXPECTED_TREE={expected.as_posix()}"],
                    env=env, capture_output=True, text=True, timeout=60)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_external_verified_tree_supplies_selected_module_headers(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "project source"
            build = root / "build"
            boost = root / "verified boost tree"
            source.mkdir()
            for name in ("algorithm", "signals2"):
                (build / "boost/libs" / name).mkdir(parents=True)
                (boost / "libs" / name / "include").mkdir(parents=True)
            (source / "CMakeLists.txt").write_text(
                'cmake_minimum_required(VERSION 3.18)\nproject(probe NONE)\n'
                f'set(BOOST_TREE "{boost.as_posix()}")\n'
                'set(BOOST_INCLUDE_LIBRARIES algorithm signals2)\n'
                f'include("{MODULE.as_posix()}")\n'
                'foreach(name algorithm signals2)\n'
                '  set(expected "${BOOST_TREE}/libs/${name}/include")\n'
                '  if(NOT expected IN_LIST Boost_INCLUDE_DIRS)\n'
                '    message(FATAL_ERROR "Missing verified-tree include: ${expected}")\n'
                '  endif()\n'
                'endforeach()\n'
                'foreach(path IN LISTS Boost_INCLUDE_DIRS)\n'
                '  if(NOT IS_DIRECTORY "${path}")\n'
                '    message(FATAL_ERROR "Missing include directory: ${path}")\n'
                '  endif()\n'
                'endforeach()\n', encoding="utf-8")
            result = subprocess.run([CMAKE, "-S", str(source), "-B", str(build)],
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
