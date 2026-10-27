cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static libs so everything ends up inside the single libwhisper_jni.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# OpenMP needs libomp from the NDK and a matching runtime; ggml's own thread pool is enough.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Never tune for the build host's CPU when cross-compiling.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# 32-bit ARM: NDK defaults to vfpv3-d16 (no NEON). Nearly all armeabi-v7a phones have NEON/VFPv4
# and ggml's NEON kernels are several times faster. Set globally so ggml picks it up too.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon-vfpv4 -mfloat-abi=softfp")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon-vfpv4 -mfloat-abi=softfp")
endif()

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper log android)
target_compile_options(whisper_jni PRIVATE -O3 -fvisibility=hidden)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections)
