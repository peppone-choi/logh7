add_library(
  logh7_legacy
  STATIC
  src/legacy_data_provider.cpp
  src/tga_decoder.cpp
  src/wic_image_decoder.cpp
)

target_compile_definitions(
  logh7_legacy
  PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
)

target_include_directories(
  logh7_legacy
  PUBLIC
    include
)

target_compile_options(
  logh7_legacy
  PRIVATE
    /EHsc
    /permissive-
    /utf-8
    /W4
    /WX
)

target_link_libraries(
  logh7_legacy
  PRIVATE
    bcrypt
    ole32
    shlwapi
    windowscodecs
)

add_executable(
  LegacyImageDecodeTest
  tests/image_decode_test.cpp
)

add_executable(
  LegacyImageProbe
  tools/legacy_image_probe.cpp
)

target_compile_definitions(
  LegacyImageDecodeTest
  PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
)

target_compile_definitions(
  LegacyImageProbe
  PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
)

target_compile_options(
  LegacyImageDecodeTest
  PRIVATE
    /EHsc
    /permissive-
    /utf-8
    /W4
    /WX
)

target_compile_options(
  LegacyImageProbe
  PRIVATE
    /EHsc
    /permissive-
    /utf-8
    /W4
    /WX
)

target_link_libraries(LegacyImageDecodeTest PRIVATE logh7_legacy)
target_link_libraries(LegacyImageProbe PRIVATE logh7_legacy)

add_executable(
  LegacyDataProviderTest
  tests/legacy_data_provider_test.cpp
  src/legacy_data_provider.cpp
)

target_compile_definitions(
  LegacyDataProviderTest
  PRIVATE
    LOGH7_TESTING
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
)

target_compile_options(
  LegacyDataProviderTest
  PRIVATE
    /EHsc
    /permissive-
    /utf-8
    /W4
    /WX
)

target_include_directories(
  LegacyDataProviderTest
  PRIVATE
    include
)

target_link_libraries(LegacyDataProviderTest PRIVATE bcrypt)

add_executable(
  LegacyDataProviderProbe
  tools/legacy_data_provider_probe.cpp
)

target_compile_definitions(
  LegacyDataProviderProbe
  PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
)

target_compile_options(
  LegacyDataProviderProbe
  PRIVATE
    /EHsc
    /permissive-
    /utf-8
    /W4
    /WX
)

target_link_libraries(LegacyDataProviderProbe PRIVATE logh7_legacy)

set_target_properties(
  LegacyDataProviderTest LegacyDataProviderProbe LegacyImageDecodeTest LegacyImageProbe
  PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)

add_test(NAME LegacyDataProvider COMMAND LegacyDataProviderTest)
add_test(NAME LegacyImageDecode COMMAND LegacyImageDecodeTest)
add_test(NAME LegacyImageDecodeMalformed COMMAND LegacyImageDecodeTest --malformed-only)

if(NOT MSVC)
  message(FATAL_ERROR "LegacyDataProviderProductionSurface requires the active MSVC toolchain")
endif()

get_filename_component(logh7_msvc_tool_directory "${CMAKE_CXX_COMPILER}" DIRECTORY)
find_program(
  LOGH7_DUMPBIN_EXECUTABLE
  NAMES dumpbin.exe
  HINTS "${logh7_msvc_tool_directory}"
  NO_DEFAULT_PATH
  NO_CACHE
)
if(NOT LOGH7_DUMPBIN_EXECUTABLE)
  message(FATAL_ERROR "Unable to resolve dumpbin.exe beside the active MSVC compiler")
endif()

add_test(
  NAME LegacyDataProviderProductionSurface
  COMMAND
    "${CMAKE_COMMAND}"
    "-DARCHIVE=$<TARGET_FILE:logh7_legacy>"
    "-DDUMPBIN=${LOGH7_DUMPBIN_EXECUTABLE}"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/production_symbol_audit.cmake"
)
