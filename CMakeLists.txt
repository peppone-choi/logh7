cmake_minimum_required(VERSION 4.4.2)

project(Logh7Greenfield VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(CTest)

add_subdirectory(libs/legacy)
add_subdirectory(apps/client)
