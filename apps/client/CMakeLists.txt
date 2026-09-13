add_executable(Logh7Client WIN32 src/main.cpp)

include(CTest)

add_library(
  logh7_strategy_shell
  STATIC
    src/strategy_shell_layout.cpp
    src/strategy_shell_text.cpp
)

target_include_directories(logh7_strategy_shell PUBLIC src)
target_compile_definitions(
  logh7_strategy_shell
  PUBLIC
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)
target_compile_options(
  logh7_strategy_shell
  PRIVATE
    /EHsc
    /permissive-
    /utf-8
    /W4
    /WX
)

add_executable(
  StrategyShellLayoutProbe
  tools/strategy_shell_layout_probe.cpp
)
target_link_libraries(StrategyShellLayoutProbe PRIVATE logh7_strategy_shell)
target_compile_options(
  StrategyShellLayoutProbe
  PRIVATE
    /EHsc
    /permissive-
    /utf-8
    /W4
    /WX
)

if(BUILD_TESTING)
  add_executable(
    StrategyShellLayoutTest
    tests/strategy_shell_layout_test.cpp
  )

  target_include_directories(StrategyShellLayoutTest PRIVATE src)
  target_link_libraries(StrategyShellLayoutTest PRIVATE logh7_strategy_shell)
  target_compile_definitions(
    StrategyShellLayoutTest
    PRIVATE
      UNICODE
      _UNICODE
      WIN32_LEAN_AND_MEAN
      NOMINMAX
  )
  target_compile_options(
    StrategyShellLayoutTest
    PRIVATE
      /EHsc
      /permissive-
      /utf-8
      /W4
      /WX
  )

  add_test(NAME StrategyShellLayout COMMAND StrategyShellLayoutTest)
endif()

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

target_link_libraries(Logh7Client PRIVATE logh7_strategy_shell user32 gdi32 shell32 ws2_32)
target_link_options(Logh7Client PRIVATE /Brepro)

set_target_properties(
  Logh7Client StrategyShellLayoutProbe
  PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
)
