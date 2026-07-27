cmake_minimum_required(VERSION 3.22)
project(rewardsearcher-llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Pin llama.cpp to a stable release tag (b4750) instead of master.
# master moves fast and frequently breaks Android NDK builds; release tags
# are reproducible and the modern sampler/vocab API used in llama-jni.cpp
# has been stable since b3400+.
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b4750
    GIT_SHALLOW    TRUE
)

# Disable everything we do not need to keep the build small and robust.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE         OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENMP         OFF CACHE BOOL "" FORCE)

# ggml options
set(GGML_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)
# Disable the llamafile/sgemm.cpp micro-kernels: they pull in ARMv8.2-A
# FP16 intrinsics that are not available on every toolchain target and
# provide marginal benefit on the small CPU model used here (LFM2.5-230M).
set(GGML_CPU_LLAMAFILE  OFF CACHE BOOL "" FORCE)
# Avoid extra BLAS backends — we want a self-contained CPU-only build.
set(GGML_BLAS            OFF CACHE BOOL "" FORCE)
set(GGML_CUDA            OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL          OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

add_library(llama-jni SHARED llama-jni.cpp)

target_link_libraries(llama-jni
    llama
    ggml
    android
    log
)
