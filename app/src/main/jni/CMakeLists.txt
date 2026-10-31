# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.18.0)

project(trime VERSION 3.0.0)

# Keep symbols and assertions while avoiding unoptimized IME/deployment latency.
# Native single-step debugging can opt out without changing release builds.
option(TRIME_OPTIMIZE_DEBUG_NATIVE "Optimize debug native engine" ON)
if(TRIME_OPTIMIZE_DEBUG_NATIVE)
  add_compile_options("$<$<CONFIG:Debug>:-O2>")
endif()

# AGP invokes Ninja directly, bypassing cmake --build's parallelism setting.
if("$ENV{CMAKE_BUILD_PARALLEL_LEVEL}" MATCHES "^[1-9][0-9]*$")
  set(CMAKE_JOB_POOLS
      "trime_compile=$ENV{CMAKE_BUILD_PARALLEL_LEVEL};trime_link=1")
  set(CMAKE_JOB_POOL_COMPILE trime_compile)
  set(CMAKE_JOB_POOL_LINK trime_link)
endif()

# for reproducible build
add_link_options("LINKER:--hash-style=both,--build-id=none")

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# general options
option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

include(Boost)
include(ThirdPartyCmake)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
# NDK 30 provides libunwind.h but no target libunwind library. With any value
# other than none, glog's FindUnwind resolves the host x86_64 libunwind.so and
# fails when linking for other ABIs, so disable its unwind driver entirely.
set(WITH_UNWIND none CACHE STRING "unwind driver" FORCE)
add_subdirectory(librime/deps/glog)
target_compile_options(
  glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)
set_property(TARGET yaml-cpp PROPERTY CXX_STANDARD 17)

# enable compress for leveldb
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
trime_add_dependency(snappy snappy 3.10)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
trime_add_dependency(librime/deps/leveldb librime/deps/leveldb 3.9)

add_subdirectory(librime/deps/marisa-trie)
target_compile_options(
  marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
trime_add_dependency(librime/deps/opencc OpenCC 3.12)
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

include(Rime)

add_subdirectory(librime_jni)

# Standalone, C-only sound-file opener. It remains usable before Rime starts.
add_library(xrime_sound_file SHARED librime_jni/sound_access/sound_jni_c.c)
target_compile_options(xrime_sound_file PRIVATE -Wall -Wextra -Werror)
