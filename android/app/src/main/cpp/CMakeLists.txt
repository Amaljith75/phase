cmake_minimum_required(VERSION 3.22.1)
project(phase_audio C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

# Portable engine core — no Android dependencies (also built by engine-tests
# on the host and intended for reuse behind an iOS CoreAudio host).
add_library(phase_engine STATIC
    engine/StemEngine.cpp
    engine/PcmSource.cpp
    engine/Recorder.cpp
    engine/AudioWriters.cpp
    engine/Exporter.cpp)
target_include_directories(phase_engine PUBLIC engine)

# Android host: Oboe output stream + JNI bridge.
add_library(phaseaudio SHARED
    android/OboeHost.cpp
    android/jni_bridge.cpp)
target_link_libraries(phaseaudio phase_engine oboe::oboe log android)
# Strip symbols and hide internals: smaller, harder to reverse-engineer.
target_compile_options(phase_engine PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_compile_options(phaseaudio PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(phaseaudio PRIVATE -Wl,--gc-sections -s)
