# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

set(BOOST_VERSION 1.92.0)
set(BOOST_SHA256 9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d)
if(DEFINED ENV{TRIME_BOOST_ARCHIVE} AND NOT "$ENV{TRIME_BOOST_ARCHIVE}" STREQUAL "")
  file(TO_CMAKE_PATH "$ENV{TRIME_BOOST_ARCHIVE}" BOOST_ARCHIVE)
else()
  get_filename_component(BOOST_ARCHIVE "${CMAKE_SOURCE_DIR}/../../../boost-${BOOST_VERSION}.tar.xz" ABSOLUTE)
endif()
include("${CMAKE_CURRENT_LIST_DIR}/BoostTree.cmake")

if(NOT EXISTS "${BOOST_ARCHIVE}")
  message(STATUS "Downloading Boost ${BOOST_VERSION} ......")
  file(
    DOWNLOAD
    "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
    "${BOOST_ARCHIVE}"
    EXPECTED_HASH
      SHA256=${BOOST_SHA256}
    SHOW_PROGRESS)
endif()

file(SHA256 "${BOOST_ARCHIVE}" boost_archive_sha)
if(NOT boost_archive_sha STREQUAL BOOST_SHA256)
  message(FATAL_ERROR "Boost archive hash mismatch: ${BOOST_ARCHIVE}")
endif()

if(DEFINED ENV{TRIME_OPENCC_PYTHON} AND NOT "$ENV{TRIME_OPENCC_PYTHON}" STREQUAL "")
  file(TO_CMAKE_PATH "$ENV{TRIME_OPENCC_PYTHON}" Python3_EXECUTABLE)
endif()
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(boost_verify_args "${BOOST_ARCHIVE}" "${BOOST_TREE}")
if(NOT EXISTS "${BOOST_TREE}")
  message(STATUS "Extracting and verifying Boost ${BOOST_VERSION} ......")
  list(APPEND boost_verify_args --extract)
else()
  message(STATUS "Verifying cached Boost ${BOOST_VERSION} ......")
endif()
execute_process(
  COMMAND "${Python3_EXECUTABLE}" -B "${CMAKE_CURRENT_LIST_DIR}/verify_boost.py" ${boost_verify_args}
  RESULT_VARIABLE boost_verify_result)
if(NOT boost_verify_result EQUAL 0)
  message(FATAL_ERROR "Boost source verification failed; existing files were not removed")
endif()

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    preprocessor
    range
    regex
    scope_exit
    signals2
    utility
    uuid
    vmd)

add_subdirectory("${BOOST_TREE}" "${CMAKE_CURRENT_BINARY_DIR}/boost" EXCLUDE_FROM_ALL)
