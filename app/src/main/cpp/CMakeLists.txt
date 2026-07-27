cmake_minimum_required(VERSION 3.22)
project(rewardsearcher-llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Pin llama.cpp to a stable release tag (b4751) via a release tarball.
# Using URL+tarball instead of GIT_REPOSITORY+GIT_TAG because:
#   * GIT_SHALLOW TRUE does not fetch tags (only the default branch),
#     so checking out a tag like b4751 fails with "invalid reference".
#   * The tarball is ~20 MB (vs ~hundreds of MB for a full git clone)
#     and is fully deterministic — the SHA256 below pins the exact bytes.
# The modern sampler / vocab / model API used in llama-jni.cpp has been
# ABI-stable since llama.cpp b3400, so any bNNNN release >= b3400 works.
FetchContent_Declare(
    llama
    URL        https://github.com/ggml-org/llama.cpp/archive/refs/tags/b4751.tar.gz
    URL_HASH   SHA256=4144819a5a8b833ae30445d0c741432472b2dd136fbe63d818ff8cdd125e9b39
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
