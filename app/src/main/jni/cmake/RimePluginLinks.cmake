# SPDX-License-Identifier: GPL-3.0-or-later

# Windows junctions do not need the symlink privilege. Reject incomplete
# existing paths instead of replacing a directory the user may have supplied.
function(trime_link_plugin_directory source destination marker)
  if(NOT EXISTS "${source}/${marker}")
    message(FATAL_ERROR "Plugin source is incomplete: ${source}/${marker}")
  endif()
  if(CMAKE_HOST_WIN32)
    execute_process(
      COMMAND powershell.exe -NoProfile -NonInteractive -File
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/create_junction.ps1"
        -LinkPath "${destination}" -TargetPath "${source}"
      RESULT_VARIABLE link_result)
    if(NOT link_result STREQUAL "0")
      message(FATAL_ERROR "Cannot create or verify plugin link: ${destination}: ${link_result}")
    endif()
  elseif(NOT EXISTS "${destination}")
    if(IS_SYMLINK "${destination}")
      message(FATAL_ERROR "Plugin link is broken: ${destination}")
    endif()
    file(CREATE_LINK "${source}" "${destination}" SYMBOLIC RESULT link_result)
    if(NOT link_result STREQUAL "0")
      message(FATAL_ERROR "Cannot create plugin link: ${destination}: ${link_result}")
    endif()
  endif()
  if(NOT CMAKE_HOST_WIN32)
    if(NOT IS_SYMLINK "${destination}")
      message(FATAL_ERROR "Refusing a plugin path that is not a link: ${destination}")
    endif()
    file(REAL_PATH "${source}" source_real)
    file(REAL_PATH "${destination}" destination_real)
    if(NOT source_real STREQUAL destination_real)
      message(FATAL_ERROR "Plugin link points to a different target: ${destination}")
    endif()
  endif()
  if(NOT IS_DIRECTORY "${destination}" OR NOT EXISTS "${destination}/${marker}")
    message(FATAL_ERROR "Existing plugin directory is incomplete; preserve and inspect it: ${destination}")
  endif()
endfunction()
