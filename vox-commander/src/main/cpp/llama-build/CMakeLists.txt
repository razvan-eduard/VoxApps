cmake_minimum_required(VERSION 3.22)
project(llama_android)

set(CMAKE_CXX_STANDARD 17)

# A project of its own, not an add_subdirectory in the whisper CMakeLists: both submodules vendor
# ggml under identical target names (ggml, ggml-base, ggml-cpu), so one configure holding both
# trees collides. Each engine statically links its own ggml copy into its own .so — the property
# scripts/check_native_pairing.py asserts (zero strongly-undefined symbols).
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
