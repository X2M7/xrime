# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# if you want to add some new plugins, add them to librime_jni/rime_jni.cc too
set(RIME_PLUGINS librime-lua librime-octagram librime-predict)

include(RimePluginLinks)

foreach(plugin ${RIME_PLUGINS})
  trime_link_plugin_directory("${CMAKE_SOURCE_DIR}/${plugin}"
    "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}" CMakeLists.txt)
endforeach()

trime_link_plugin_directory("${CMAKE_SOURCE_DIR}/librime-lua-deps"
  "${CMAKE_SOURCE_DIR}/librime/plugins/librime-lua/thirdparty" "lua5.4/lua.h")

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(librime)
include(RimeConfigCompat)
trime_adapt_rime_config()
include(RimeMappedFileCompat)
trime_adapt_rime_mapped_file()
include(RimePrivacyCompat)
trime_adapt_rime_privacy()
include(RimeLuaCompat)
trime_adapt_rime_lua()
include(RimeHeaderCompat)
trime_adapt_rime_headers()
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

target_compile_options(
  rime-octagram-objs PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
