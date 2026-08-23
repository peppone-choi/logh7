if(NOT DEFINED ARCHIVE OR NOT EXISTS "${ARCHIVE}")
  message(FATAL_ERROR "PRODUCTION_ARCHIVE_MISSING")
endif()

find_program(DUMPBIN_EXECUTABLE dumpbin)
if(NOT DUMPBIN_EXECUTABLE)
  message(FATAL_ERROR "DUMPBIN_MISSING")
endif()

execute_process(
  COMMAND "${DUMPBIN_EXECUTABLE}" /symbols "${ARCHIVE}"
  RESULT_VARIABLE dumpbin_result
  OUTPUT_VARIABLE symbols
  ERROR_VARIABLE dumpbin_error
)

if(NOT dumpbin_result EQUAL 0)
  message(FATAL_ERROR "DUMPBIN_FAILED: ${dumpbin_error}")
endif()

string(FIND "${symbols}" "OpenForTest" test_seam_position)
if(NOT test_seam_position EQUAL -1)
  message(FATAL_ERROR "SHIPPING_TEST_SEAM_EXPORTED: OpenForTest")
endif()

message(STATUS "Production legacy library contains no test manifest override")
