cmake_minimum_required(VERSION 3.22)
project(whisper_android)

set(CMAKE_CXX_STANDARD 17)

# --- VULKAN & SPIRV, FORCED ---
# Let programs (glslc) be found on the Mac host
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)

if (VULKAN_HEADERS_DIR AND SPIRV_HEADERS_INC_DIR)
    set(INC_FLAGS "-I${VULKAN_HEADERS_DIR} -I${SPIRV_HEADERS_INC_DIR}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${INC_FLAGS}" CACHE STRING "" FORCE)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${INC_FLAGS}" CACHE STRING "" FORCE)
    
    # Standard variables for the sub-modules
    set(Vulkan_INCLUDE_DIR "${VULKAN_HEADERS_DIR}" CACHE PATH "" FORCE)
    include_directories("${VULKAN_HEADERS_DIR}" "${SPIRV_HEADERS_INC_DIR}")
endif()

if (SPIRV_HEADERS_CMAKE_DIR)
    set(SPIRV-Headers_DIR "${SPIRV_HEADERS_CMAKE_DIR}" CACHE PATH "" FORCE)
endif()

# Force the path to the shader compiler
if (Vulkan_GLSLC_EXECUTABLE)
    set(Vulkan_GLSLC_EXECUTABLE "${Vulkan_GLSLC_EXECUTABLE}" CACHE FILEPATH "" FORCE)
endif()

# Whisper configuration
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
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
    ggml-vulkan
    ggml-base
    ${log-lib}
    ${android-lib}
)
