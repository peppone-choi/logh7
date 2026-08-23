add_executable(Logh7Client WIN32 src/main.cpp)

target_compile_definitions(
  Logh7Client
  PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0A00
)

target_compile_options(
  Logh7Client
  PRIVATE
    /EHsc
    /permissive-
    /utf-8
    /W4
    /WX
)

target_link_libraries(Logh7Client PRIVATE user32 gdi32 shell32 ws2_32)
target_link_options(Logh7Client PRIVATE /Brepro)

set_target_properties(
  Logh7Client
  PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)
