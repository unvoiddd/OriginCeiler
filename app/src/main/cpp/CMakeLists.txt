cmake_minimum_required(VERSION 3.22.1)
project(originroottoolbox_whisper LANGUAGES C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# MT6991 supports ARM dot-product and i8mm instructions. The generic arm64
# backend used by debug builds makes even the tiny model miss BlueLM's timeout.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm" CACHE STRING "" FORCE)

set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG")

add_subdirectory(whispercpp EXCLUDE_FROM_ALL)

add_library(originwhisper SHARED whisper_jni.cpp)
target_link_libraries(originwhisper PRIVATE whisper android log)
target_compile_options(originwhisper PRIVATE -O3 -fvisibility=hidden)
