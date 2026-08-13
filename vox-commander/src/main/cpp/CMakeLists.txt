cmake_minimum_required(VERSION 3.22)
project(whisper_android)

set(CMAKE_CXX_STANDARD 17)

# --- OPENCL INPUTS ---
# Kernel embedding runs python on the build host while everything else cross-compiles, so
# program lookup must escape the NDK sysroot. Headers and the import stub arrive as -D inputs
# from check_whisper.sh, both sourced from repo-pinned submodules.
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)

# Whisper configuration. OpenCL over Vulkan for the same reason as the llama build: the Adreno
# Vulkan driver aborts creating compute pipelines (a real device probe crash), while the OpenCL
# stack is the one Qualcomm maintains for this hardware, with the Adreno-tuned kernels on.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# --- MACHINE-INDEPENDENT BUILD (no build-host identity in the binary) ---
# Same reason as the llama build: ggml's GGML_ASSERT bakes __FILE__ into .rodata, so the absolute
# build path survives a strip. -ffile-prefix-map neutralises the prefix in __FILE__ and debug
# info; --build-id=none drops the linker's per-build hash. Set before the submodule so it covers
# whisper's and ggml's own sources.
get_filename_component(VOX_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${VOX_REPO_ROOT}=.")
add_link_options("-Wl,--build-id=none")

add_subdirectory(whisper.cpp)

add_library(
    whisper_final 
    SHARED
    native-lib.cpp
)

set_target_properties(whisper_final PROPERTIES OUTPUT_NAME "whisper")

target_include_directories(
    whisper_final
    PRIVATE
    whisper.cpp/include
    whisper.cpp/ggml/include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    whisper_final
    PRIVATE
    whisper
    # No per-backend entry: ggml's umbrella target links and defines its compiled-in backends
    # itself, same as the llama build — naming one here breaks the link whenever the backend
    # selection changes, which is exactly how the Vulkan entry died.
    ggml-base
    ${log-lib}
    ${android-lib}
)
