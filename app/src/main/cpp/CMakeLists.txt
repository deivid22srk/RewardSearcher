cmake_minimum_required(VERSION 3.22)
project(rewardsearcher-llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# IMPORTANT: pin to a tag that supports the LFM2 architecture used by the
# default model LiquidAI/LFM2.5-230M-Q8_0.gguf.
#
# The previous pin (b4751) was released BEFORE LFM2 support landed in
# llama.cpp (LFM2 was added in b6000, May 2025). With b4751, every call
# to llama_model_load_from_file on an LFM2 GGUF returns NULL after ~200ms
# because the architecture is unknown — the app silently falls back to
# the static SearchTerms pool, which the user correctly identified as
# "simulating" instead of actually running the model.
#
# b9500 (Oct 2025) is recent enough to ship LFM2 + LFM2MoE + the chat
# template improvements, and old enough to be a settled release tag
# rather than a daily master snapshot.
#
# We use the GitHub release tarball (URL mode) instead of GIT_REPOSITORY
# because GIT_SHALLOW=TRUE cannot checkout specific tags.
FetchContent_Declare(
    llama
    URL        https://github.com/ggml-org/llama.cpp/archive/refs/tags/b9500.tar.gz
    URL_HASH   SHA256=ebff6593ce1555c2f01e19b8545d6b47db87d4aed5a8e67c721c45ef708b553b
)

# When consumed via FetchContent, LLAMA_STANDALONE defaults to OFF, so all
# LLAMA_BUILD_* options default to OFF as well. We still force them OFF
# explicitly in case the defaults change in future llama.cpp releases.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE         OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENMP         OFF CACHE BOOL "" FORCE)

# ggml options
set(GGML_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)
# Disable the llamafile/sgemm.cpp micro-kernels: they pull in ARMv8.2-A
# FP16 intrinsics that are not available on every toolchain target.
set(GGML_LLAMAFILE      OFF CACHE BOOL "" FORCE)
# Self-contained CPU-only build, no external compute backends.
set(GGML_BLAS            OFF CACHE BOOL "" FORCE)
set(GGML_CUDA            OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL          OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)
# Avoid the dynamic backend loader — we want a single static-ish .so
# that bundles everything the app needs.
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama)

add_library(llama-jni SHARED llama-jni.cpp)

target_link_libraries(llama-jni
    llama
    ggml
    android
    log
)
