#!/bin/bash
set -Eeuo pipefail

# Builds OpenCV (core, imgproc, imgcodecs, plus their transitive runtime deps — see below) from
# source for arm64-v8a, producing libopencv_java<N>.so (N = OpenCV's major version — 4 for the 4.x
# line, 5 for 5.x; this script doesn't hardcode it, see JNI_SO below), consumed directly by
# vendor/ppocr-sdk (see its build.gradle.kts). Replaces the stale, unmaintained
# com.quickbirdstudios:opencv:4.5.3 Maven dependency (last published 2021-09-15), whose prebuilt
# native library fails to dlopen on modern Android (missing Bionic libc symbol
# __sfp_handle_exceptions).
#
# OpenCV 5.0 split geometric algorithms out of imgproc into a new opencv_geometry module, which
# itself depends on opencv_flann — both must stay enabled (confirmed via `cmake --debug-output`:
# disabling flann cascades to geometry disabled -> imgproc disabled -> java disabled entirely) even
# though nothing here calls flann/geometry APIs directly; they're pure transitive runtime deps now
# (confirmed via `readelf -d`: imgproc's NEEDED includes libopencv_geometry.so, which needs
# libopencv_flann.so). OpenCV 5.0 also requires C++17 for imgproc's warp_kernels.simd.hpp (uses
# `if constexpr`/`std::conditional_t`) — the previous C++11 pin fails to compile it.
#
# Invokes CMake directly (not OpenCV's own platforms/android/build_sdk.py wrapper, whose BUILD_LIST
# restriction interacts badly with the "world" combined-module path it takes for --shared builds).
# Mirrors the approach MakeACopy (github.com/egdels/makeacopy) uses for the same PaddleOCR-on-Android
# problem: BUILD_ANDROID_PROJECTS=ON + BUILD_opencv_java=ON, building `gen_opencv_java_source` first
# so the JNI glue code exists before the full build compiles it.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OPENCV_DIR="$PROJECT_ROOT/vendor/opencv"
BUILD_DIR="$PROJECT_ROOT/vendor/opencv-android-build"
OUTPUT_DIR="$PROJECT_ROOT/vendor/ppocr-sdk/opencv"
# The build writes the compiled .so files to two places, and both are load-bearing:
#
#   vendor/ppocr-sdk/opencv/          the module's own jniLibs.srcDirs() source, and where its
#                                     org.opencv.* Java API is generated from — vox-vision consumes
#                                     that through a normal project() dependency
#   vox-vision/src/main/jniLibs/      vox-vision's own default source set, and the directory
#                                     release-vision.yml uploads the `full`-mode DLC assets from
#
VISION_JNI_DIR="$PROJECT_ROOT/vox-vision/src/main/jniLibs"

ANDROID_HOME="${ANDROID_HOME:-$(vox_android_sdk)}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(vox_android_ndk)}"

# Stamped with the built commit SHA on success (see bottom of this script) — skipping only on
# "output exists" (no version check) would silently keep serving a stale build after someone bumps
# the vendor/opencv submodule pin, since nothing else would force a rebuild.
BUILT_COMMIT_FILE="$OUTPUT_DIR/.built-commit"

have_output() {
    ls "$OUTPUT_DIR"/libs/arm64-v8a/libopencv_java*.so >/dev/null 2>&1 \
        && [ -d "$OUTPUT_DIR/java/org" ] \
        && ls "$VISION_JNI_DIR"/arm64-v8a/libopencv_java*.so >/dev/null 2>&1
}

if [ ! -e "$OPENCV_DIR/.git" ]; then
    # A build restored from a cache rather than made here: the submodule is a few hundred MB that
    # only a rebuild needs, so a CI job that already has the output should not have to fetch it just
    # to read a SHA. Freshness cannot be checked without it — whoever restored the output is
    # responsible for keying the cache on the submodule pin, which is what the CI workflow does.
    if have_output && [ -f "$BUILT_COMMIT_FILE" ]; then
        echo "vendor/opencv not initialized, but a build stamped $(cat "$BUILT_COMMIT_FILE") is present — using it."
        exit 0
    fi
    echo "vendor/opencv submodule not initialized — run: git submodule update --init vendor/opencv"
    exit 1
fi

CURRENT_COMMIT="$(git -C "$OPENCV_DIR" rev-parse HEAD)"

if have_output; then
    if [ -f "$BUILT_COMMIT_FILE" ] && [ "$(cat "$BUILT_COMMIT_FILE")" = "$CURRENT_COMMIT" ]; then
        echo "OpenCV already built at $OUTPUT_DIR for commit $CURRENT_COMMIT — skipping."
        exit 0
    else
        echo "OpenCV build output exists but is stale (built for $(cat "$BUILT_COMMIT_FILE" 2>/dev/null || echo "unknown"), submodule now at $CURRENT_COMMIT) — rebuilding."
    fi
fi

echo "NDK: $ANDROID_NDK_HOME"
PREBUILT_BASE="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
TOOLCHAIN_DIR="$(find "$PREBUILT_BASE" -maxdepth 1 -type d -name "darwin-*" -o -maxdepth 1 -type d -name "linux-*" 2>/dev/null | head -1)"
[ -n "$TOOLCHAIN_DIR" ] || { echo "ERROR: llvm toolchain dir not found under $PREBUILT_BASE"; exit 1; }
AR_BIN="$TOOLCHAIN_DIR/bin/llvm-ar"
RANLIB_BIN="$TOOLCHAIN_DIR/bin/llvm-ranlib"
PY3_BIN="$(command -v python3)"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# -ffile-prefix-map + --build-id=none keep the build host out of the shipped libraries — the
# absolute source path is otherwise baked into every OpenCV assert string (.rodata, survives
# a strip), same as the whisper/llama builds.
echo "Configuring OpenCV (arm64-v8a, core+imgproc+imgcodecs+java, +geometry+flann as transitive deps)..."
cmake -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_NATIVE_API_LEVEL=29 \
    -DCMAKE_AR="$AR_BIN" -DCMAKE_RANLIB="$RANLIB_BIN" \
    -DPython3_EXECUTABLE="$PY3_BIN" \
    -DBUILD_opencv_python3=OFF -DBUILD_opencv_python_bindings_generator=OFF \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_ANDROID_PROJECTS=ON \
    -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF \
    -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF -DBUILD_ANDROID_EXAMPLES=OFF \
    -DBUILD_JAVA=ON -DBUILD_opencv_java=ON \
    -DBUILD_opencv_imgproc=ON -DBUILD_opencv_imgcodecs=ON \
    -DBUILD_opencv_flann=ON \
    -DBUILD_opencv_video=OFF -DBUILD_opencv_videoio=OFF -DBUILD_opencv_photo=OFF \
    -DBUILD_opencv_calib3d=OFF -DBUILD_opencv_features2d=OFF -DBUILD_opencv_objdetect=OFF \
    -DBUILD_opencv_dnn=OFF -DBUILD_opencv_gapi=OFF -DBUILD_opencv_ml=OFF -DBUILD_opencv_highgui=OFF \
    -DBUILD_opencv_stitching=OFF \
    -DWITH_OPENCL=OFF -DWITH_IPP=OFF \
    -DCMAKE_CXX_STANDARD=17 -DCMAKE_CXX_STANDARD_REQUIRED=ON \
    -DCMAKE_C_FLAGS="-ffile-prefix-map=$PROJECT_ROOT=." \
    -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$PROJECT_ROOT=." \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none" \
    "$OPENCV_DIR"

echo "Building gen_opencv_java_source (JNI codegen) first..."
ninja -j1 gen_opencv_java_source

echo "Building the rest..."
# BUILD_ANDROID_PROJECTS=ON also builds a bundled internal Gradle sub-project (an Android Studio
# sample/AAR wrapper) that can fail on Kotlin/JDK toolchain mismatches unrelated to what we actually
# need — the real libopencv_java<N>.so and Java sources are produced by plain ninja targets *before*
# that sub-project even starts, so a failure there is tolerated and verified against explicitly below.
ninja || echo "ninja reported a failure (likely OpenCV's bundled Gradle sub-project, not the native build) — verifying required artifacts below regardless."

# Filename encodes the OpenCV major version (libopencv_java4.so for 4.x, libopencv_java5.so for
# 5.x) — found dynamically rather than hardcoded so a future submodule bump within the same major
# version doesn't need this script edited again.
JNI_SO="$(find "$BUILD_DIR" -path "*/jni/arm64-v8a/libopencv_java*.so" -print -quit)"
JAVA_SRC_DIR="$BUILD_DIR/modules/java_bindings_generator/gen/java/org"
if [ -z "$JNI_SO" ] || [ ! -d "$JAVA_SRC_DIR" ]; then
    echo "ERROR: libopencv_java*.so or Java bindings source not produced. Check $BUILD_DIR for build logs."
    exit 1
fi
JNI_SO_NAME="$(basename "$JNI_SO")"
echo "Built: $JNI_SO_NAME"

mkdir -p "$OUTPUT_DIR/libs/arm64-v8a"
rm -f "$OUTPUT_DIR/libs/arm64-v8a/libopencv_java"*.so
cp "$JNI_SO" "$OUTPUT_DIR/libs/arm64-v8a/$JNI_SO_NAME"
# libopencv_java<N>.so is built with BUILD_SHARED_LIBS=ON, so it dynamically links against the
# per-module shared libraries at runtime instead of having them statically linked in — all of them
# must ship in jniLibs too, or dlopen fails at first use with "library ... not found". geometry,
# flann, features, ptcloud, and stereo are OpenCV 5.0+ additions (confirmed via `readelf -d
# libopencv_java5.so`: NEEDED includes all of these, even with calib3d/features2d disabled above —
# OpenCV 5's java bindings link them unconditionally; absent in OpenCV 4.x, where this loop's set
# was sufficient) — `cp -f` with a glob so a build against an older OpenCV commit that doesn't
# produce them doesn't fail the whole script.
for mod in core imgproc imgcodecs geometry flann features ptcloud stereo; do
    cp -f "$BUILD_DIR/lib/arm64-v8a/libopencv_${mod}.so" "$OUTPUT_DIR/libs/arm64-v8a/" 2>/dev/null || true
done
STRIP_BIN="$TOOLCHAIN_DIR/bin/llvm-strip"
[ -x "$STRIP_BIN" ] && "$STRIP_BIN" --strip-unneeded "$OUTPUT_DIR/libs/arm64-v8a/"*.so

# Also copy directly into vox-vision's own jniLibs source set — see the VISION_JNI_DIR comment near
# the top of this script for why this duplication exists (AGP doesn't reliably propagate a local
# library module's jniLibs.srcDirs() through to a consuming app module here).
mkdir -p "$VISION_JNI_DIR/arm64-v8a"
rm -f "$VISION_JNI_DIR/arm64-v8a/libopencv_"*.so
cp "$OUTPUT_DIR/libs/arm64-v8a/"libopencv_*.so "$VISION_JNI_DIR/arm64-v8a/"

# libonnxruntime.so hits the same arm64-v8a AGP packaging bug (see the VISION_JNI_DIR comment) even
# though it comes from a normal Maven AAR, not jniLibs.srcDirs() — extracted directly from the AAR
# already sitting in Gradle's module cache (resolved as part of vendor/ppocr-sdk's own dependency
# graph) rather than hardcoding a path, since the cache's per-artifact hash directory isn't stable.
ONNXRUNTIME_VERSION="$(grep '^onnxruntime ' "$PROJECT_ROOT/gradle/libs.versions.toml" | sed 's/.*"\(.*\)".*/\1/')"
ONNXRUNTIME_AAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime-android/$ONNXRUNTIME_VERSION" \
    -iname "onnxruntime-android-*.aar" -print -quit 2>/dev/null)"
if [ -n "$ONNXRUNTIME_AAR" ]; then
    rm -f "$VISION_JNI_DIR/arm64-v8a/libonnxruntime.so"
    unzip -o -j "$ONNXRUNTIME_AAR" "jni/arm64-v8a/libonnxruntime.so" -d "$VISION_JNI_DIR/arm64-v8a" >/dev/null
else
    echo "WARNING: onnxruntime-android AAR not found in Gradle cache for version $ONNXRUNTIME_VERSION — run a build that resolves vendor/ppocr-sdk's dependencies first, then re-run this script." >&2
fi

rm -rf "$OUTPUT_DIR/java"
mkdir -p "$OUTPUT_DIR/java"
cp -r "$JAVA_SRC_DIR" "$OUTPUT_DIR/java/"
# The org.opencv.android package (Utils, OpenCVLoader, ...) is generated into a separate "gen/android"
# tree, not "gen/java" — vendor/ppocr-sdk's BitmapUtils.kt needs org.opencv.android.Utils. Only copy
# the plain-Java helper classes; JavaCameraView/CameraBridgeViewBase/CameraActivity need an Android
# app module's R/BuildConfig (camera-preview UI we don't use — Vision does its own capture via
# CameraX) and would fail to compile in this plain library module.
ANDROID_PKG_DIR="$BUILD_DIR/modules/java_bindings_generator/gen/android/java/org/opencv/android"
if [ -d "$ANDROID_PKG_DIR" ]; then
    mkdir -p "$OUTPUT_DIR/java/org/opencv/android"
    for f in Utils.java OpenCVLoader.java StaticHelper.java FpsMeter.java; do
        [ -f "$ANDROID_PKG_DIR/$f" ] && cp "$ANDROID_PKG_DIR/$f" "$OUTPUT_DIR/java/org/opencv/android/"
    done
fi

echo "$CURRENT_COMMIT" > "$BUILT_COMMIT_FILE"

echo "OpenCV build complete (commit $CURRENT_COMMIT):"
echo "  $OUTPUT_DIR/libs/arm64-v8a/$JNI_SO_NAME"
echo "  $OUTPUT_DIR/java/org/ (Java bindings source)"
echo "  $VISION_JNI_DIR/arm64-v8a/ (same .so files, direct copy for vox-vision)"
