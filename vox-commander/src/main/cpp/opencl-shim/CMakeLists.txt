# The OpenCL import shim, built as the static archive the engine builds hand to find_package
# as OpenCL_LIBRARY. Static on purpose: linked into the engine's own .so, it leaves no OpenCL
# entry in DT_NEEDED, which is the whole point — see opencl_shim.c.
cmake_minimum_required(VERSION 3.22)
project(vox_opencl_shim C)

if (NOT OPENCL_HEADERS_DIR)
    message(FATAL_ERROR "OPENCL_HEADERS_DIR must point at the OpenCL-Headers checkout")
endif()

add_library(OpenCL STATIC opencl_shim.c)
target_include_directories(OpenCL PRIVATE "${OPENCL_HEADERS_DIR}")
# Hidden visibility so each engine's copy of the cl* symbols stays its own.
target_compile_options(OpenCL PRIVATE -fvisibility=hidden)
