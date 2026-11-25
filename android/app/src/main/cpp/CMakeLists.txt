cmake_minimum_required(VERSION 3.22.1)
project(phase_audio C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

# Bundled MP3 encoder (shine, LGPL-2) for MP3 export.
file(GLOB SHINE_SRC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/shine/*.c)
add_library(shine STATIC ${SHINE_SRC})
target_include_directories(shine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/shine)
target_compile_options(shine PRIVATE -w -O2)

# Portable engine core — no Android dependencies (also built by engine-tests
# on the host and intended for reuse behind an iOS CoreAudio host).
add_library(phase_engine STATIC
    engine/StemEngine.cpp
    engine/PcmSource.cpp
    engine/Recorder.cpp
    engine/AudioWriters.cpp
    engine/Exporter.cpp)
target_include_directories(phase_engine PUBLIC engine)
target_link_libraries(phase_engine PUBLIC shine)

# Android host: Oboe output stream + JNI bridge.
add_library(phaseaudio SHARED
    android/OboeHost.cpp
    android/jni_bridge.cpp)
target_link_libraries(phaseaudio phase_engine oboe::oboe log android)
