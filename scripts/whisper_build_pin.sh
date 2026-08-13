#!/bin/bash
set -e

# The address of the published whisper runtime, printed as a 40-hex pin (tags take its first 12).
#
# Same contract as llama_build_pin.sh, and for the same reason it stopped being the submodule
# commit alone: libwhisper.so's bytes come from the whisper.cpp submodule, the JNI wrapper, the
# CMake build config, and the OpenCL import shim. A pin over the submodule alone cannot represent
# a wrapper or build-config change — the backend switch to OpenCL moved none of the submodule,
# so the old pin would have kept naming a release whose bytes no longer match the APK's recorded
# digests. Hashing the tree state of every input moves the tag whenever any of them moves.
#
# The index, not HEAD: `ls-files -s` equals HEAD's state on any committed checkout and still
# answers on the one checkout HEAD cannot serve — the commit that introduces the submodule.
cd "$(dirname "$0")/.."
git ls-files -s -- \
    vox-commander/src/main/cpp/whisper.cpp \
    vox-commander/src/main/cpp/native-lib.cpp \
    vox-commander/src/main/cpp/CMakeLists.txt \
    vox-commander/src/main/cpp/opencl-shim \
    | awk '{print $2}' | git hash-object --stdin
