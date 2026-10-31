# SPDX-License-Identifier: GPL-3.0-or-later

if(DEFINED ENV{TRIME_BOOST_TREE} AND NOT "$ENV{TRIME_BOOST_TREE}" STREQUAL "")
  file(TO_CMAKE_PATH "$ENV{TRIME_BOOST_TREE}" BOOST_TREE)
else()
  # Keep the extracted dependency outside app/: Android lint traverses that
  # tree, including native sources and generated outputs, during analysis.
  get_filename_component(
    BOOST_TREE "${CMAKE_SOURCE_DIR}/../../../../build/xrime-boost-${BOOST_VERSION}"
    ABSOLUTE)
endif()
