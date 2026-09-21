cmake_minimum_required(VERSION 3.22.1)

project("reshift_llama" C CXX)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -Wall")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -Wall -std=c++17")

# Include FetchContent to build llama.cpp from source if needed, or build llama-bridge
include(FetchContent)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b3600
    GIT_SHALLOW    TRUE
)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "disable examples" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "disable tests" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "disable server" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "build static libs" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "disable vulkan" FORCE)

# Enable ARM NEON SIMD optimizations on Android arm64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NEON ON CACHE BOOL "compiler has NEON support" FORCE)
endif()

FetchContent_MakeAvailable(llama_cpp)

add_library(
    reshift_llama
    SHARED
    llama-bridge.cpp
)

find_library(
    log-lib
    log
)

target_include_directories(reshift_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(
    reshift_llama
    llama
    ggml
    ${log-lib}
)
