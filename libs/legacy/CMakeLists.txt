add_library(
  logh7_legacy
  STATIC
  src/legacy_data_provider.cpp
)

target_compile_definitions(
  logh7_legacy
  PRIVATE
    LOGH7_TESTING
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

target_link_libraries(logh7_legacy PRIVATE bcrypt)

add_executable(
  LegacyDataProviderTest
  tests/legacy_data_provider_test.cpp
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

target_link_libraries(LegacyDataProviderTest PRIVATE logh7_legacy)

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
  LegacyDataProviderTest LegacyDataProviderProbe
  PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)

add_test(NAME LegacyDataProvider COMMAND LegacyDataProviderTest)
