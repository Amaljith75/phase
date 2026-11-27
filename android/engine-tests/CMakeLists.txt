cmake_minimum_required(VERSION 3.18)
project(phase_engine_tests CXX C)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)

add_executable(engine_test
  engine_test.cpp
  ${CPP}/engine/StemEngine.cpp
  ${CPP}/engine/PcmSource.cpp
  ${CPP}/engine/Recorder.cpp
  ${CPP}/engine/AudioWriters.cpp
  ${CPP}/engine/Exporter.cpp)
target_include_directories(engine_test PRIVATE ${CPP}/engine)
target_compile_options(engine_test PRIVATE -Wall -Wextra -O2)
target_link_libraries(engine_test pthread m)
