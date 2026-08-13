cmake_minimum_required(VERSION 3.22)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)

# --- VULKAN & SPIRV, FORCED (same preamble as the whisper CMakeLists) ---
# The shader compiler (glslc) runs on the build host while everything else cross-compiles, so
# program lookup must escape the NDK sysroot. The three -D inputs arrive from check_llama.sh;
# they are forced into the cache here — semantic build inputs live in this file because this
# file is inside the build-fingerprint pin (scripts/llama_build_pin.sh) and the shell script
# is not.
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)

if (VULKAN_HEADERS_DIR AND SPIRV_HEADERS_INC_DIR)
    set(INC_FLAGS "-I${VULKAN_HEADERS_DIR} -I${SPIRV_HEADERS_INC_DIR}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${INC_FLAGS}" CACHE STRING "" FORCE)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${INC_FLAGS}" CACHE STRING "" FORCE)
    set(Vulkan_INCLUDE_DIR "${VULKAN_HEADERS_DIR}" CACHE PATH "" FORCE)
    include_directories("${VULKAN_HEADERS_DIR}" "${SPIRV_HEADERS_INC_DIR}")
endif()

if (SPIRV_HEADERS_CMAKE_DIR)
    set(SPIRV-Headers_DIR "${SPIRV_HEADERS_CMAKE_DIR}" CACHE PATH "" FORCE)
endif()

if (Vulkan_GLSLC_EXECUTABLE)
    set(Vulkan_GLSLC_EXECUTABLE "${Vulkan_GLSLC_EXECUTABLE}" CACHE FILEPATH "" FORCE)
endif()

# A project of its own, not an add_subdirectory in the whisper CMakeLists: both submodules vendor
# ggml under identical target names (ggml, ggml-base, ggml-cpu), so one configure holding both
# trees collides. Each engine statically links its own ggml copy into its own .so — the property
# scripts/check_native_pairing.py asserts (zero strongly-undefined symbols).
#
# Hybrid CPU+Vulkan in the one library, chosen per model load (n_gpu_layers) — whisper's model.
# ggml's umbrella target links and defines compiled-in backends itself, so the link line below
# needs no ggml-vulkan entry.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
# No OpenMP: llama's own threadpool serves, and the single-lib set avoids a cross-release
# dependency on the libomp.so that whisper's tag happens to publish.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# common is wanted for json_schema_to_grammar + chat templating; everything else is not.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)

# --- MACHINE-INDEPENDENT BUILD (no build-host identity in the binary) ---
# ggml asserts everywhere, and every GGML_ASSERT bakes __FILE__ into .rodata as a string the
# running code points at — so the absolute build path survives a strip (it is not debug info).
# -ffile-prefix-map rewrites the prefix the compiler embeds in __FILE__ and debug info to a
# neutral, repo-relative one; --build-id=none drops the linker's per-build hash. Set BEFORE the
# submodule is added so it covers ggml's and llama's own sources, not just the JNI wrapper. This
# lives in the pinned CMakeLists (the build fingerprint), so a change here moves the tag.
get_filename_component(VOX_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${VOX_REPO_ROOT}=.")
add_link_options("-Wl,--build-id=none")

add_subdirectory(../llama.cpp llama.cpp)

add_library(
    llama_final
    SHARED
    ../llama_jni.cpp
)

set_target_properties(llama_final PROPERTIES OUTPUT_NAME "llama")

# Only the JNI surface is exported: two engines each carrying a static ggml must not offer their
# internals to the dynamic linker. The visibility preset covers the wrapper's own objects;
# --exclude-libs,ALL is what keeps the statically-linked llama/ggml archives' symbols out of the
# dynamic table (they are compiled default-visible upstream).
set_target_properties(llama_final PROPERTIES C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden)
target_link_options(llama_final PRIVATE "-Wl,--exclude-libs,ALL")

target_include_directories(
    llama_final
    PRIVATE
    ../llama.cpp/include
    ../llama.cpp/ggml/include
    ../llama.cpp/common
    ../llama.cpp/vendor
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    llama_final
    PRIVATE
    llama-common
    llama
    ggml
    ${log-lib}
    ${android-lib}
)
