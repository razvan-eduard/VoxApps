#!/bin/bash
set -e

# The address of the published llama runtime, printed as a 40-hex pin (tags take its first 12).
#
# The tag must name the *build*, and libllama.so's bytes come from three places: the llama.cpp
# submodule pin, the JNI bridge, and the CMake build config. A pin over the submodule alone
# cannot represent a bridge or build-config change — published releases are immutable, so
# "same tag, different bytes" is not representable at all, and an APK compiled against the new
# bridge would download and verify a runtime whose exported symbols it cannot call. Hashing the
# tree state of all three inputs moves the tag whenever any of them moves.
#
# The index, not HEAD: `ls-files -s` equals HEAD's state on any committed checkout and still
# answers on the one checkout HEAD cannot serve — the commit that introduces the submodule.
# `git hash-object` makes the digest tool-independent: the same git that provided the inputs.
cd "$(dirname "$0")/.."
git ls-files -s -- \
    vox-commander/src/main/cpp/llama.cpp \
    vox-commander/src/main/cpp/llama_jni.cpp \
    vox-commander/src/main/cpp/llama-build \
    | awk '{print $2}' | git hash-object --stdin
