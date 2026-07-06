cmake_minimum_required(VERSION 3.22)
project(whisper_android)

set(CMAKE_CXX_STANDARD 17)

# --- CONFIGURARE VULKAN & SPIRV FORȚATĂ ---
# Permitem găsirea programelor (glslc) pe host-ul Mac
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)

if (VULKAN_HEADERS_DIR AND SPIRV_HEADERS_INC_DIR)
    set(INC_FLAGS "-I${VULKAN_HEADERS_DIR} -I${SPIRV_HEADERS_INC_DIR}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${INC_FLAGS}" CACHE STRING "" FORCE)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${INC_FLAGS}" CACHE STRING "" FORCE)
    
    # Variabile standard pentru sub-module
    set(Vulkan_INCLUDE_DIR "${VULKAN_HEADERS_DIR}" CACHE PATH "" FORCE)
    include_directories("${VULKAN_HEADERS_DIR}" "${SPIRV_HEADERS_INC_DIR}")
endif()

if (SPIRV_HEADERS_CMAKE_DIR)
    set(SPIRV-Headers_DIR "${SPIRV_HEADERS_CMAKE_DIR}" CACHE PATH "" FORCE)
endif()

# Forțăm calea către compilatorul de shadere
if (Vulkan_GLSLC_EXECUTABLE)
    set(Vulkan_GLSLC_EXECUTABLE "${Vulkan_GLSLC_EXECUTABLE}" CACHE FILEPATH "" FORCE)
endif()

# Configurații Whisper
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

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
