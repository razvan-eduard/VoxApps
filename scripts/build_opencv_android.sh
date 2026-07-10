#!/bin/bash
set -Eeuo pipefail

# Builds OpenCV (core, imgproc, imgcodecs only — all vendor/ppocr-sdk actually uses) from source for
# arm64-v8a, producing libopencv_java4.so, consumed directly by vendor/ppocr-sdk (see its
# build.gradle.kts). Replaces the stale, unmaintained com.quickbirdstudios:opencv:4.5.3 Maven
# dependency (last published 2021-09-15), whose prebuilt native library fails to dlopen on modern
# Android (missing Bionic libc symbol __sfp_handle_exceptions).
#
# Invokes CMake directly (not OpenCV's own platforms/android/build_sdk.py wrapper, whose BUILD_LIST
# restriction interacts badly with the "world" combined-module path it takes for --shared builds).
# Mirrors the approach MakeACopy (github.com/egdels/makeacopy) uses for the same PaddleOCR-on-Android
# problem: BUILD_ANDROID_PROJECTS=ON + BUILD_opencv_java=ON, building `gen_opencv_java_source` first
# so the JNI glue code exists before the full build compiles it.

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OPENCV_DIR="$PROJECT_ROOT/vendor/opencv"
BUILD_DIR="$PROJECT_ROOT/vendor/opencv-android-build"
OUTPUT_DIR="$PROJECT_ROOT/vendor/ppocr-sdk/opencv"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(find "$ANDROID_HOME/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -V | tail -1)}"

if [ ! -e "$OPENCV_DIR/.git" ]; then
    echo "vendor/opencv submodule not initialized — run: git submodule update --init vendor/opencv"
    exit 1
fi

# Stamped with the built commit SHA on success (see bottom of this script) — skipping only on
# "output exists" (no version check) would silently keep serving a stale build after someone bumps
# the vendor/opencv submodule pin, since nothing else would force a rebuild.
BUILT_COMMIT_FILE="$OUTPUT_DIR/.built-commit"
CURRENT_COMMIT="$(git -C "$OPENCV_DIR" rev-parse HEAD)"

if [ -f "$OUTPUT_DIR/libs/arm64-v8a/libopencv_java4.so" ] && [ -d "$OUTPUT_DIR/java/org" ]; then
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

echo "Configuring OpenCV (arm64-v8a, core+imgproc+imgcodecs+java)..."
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
    -DBUILD_opencv_video=OFF -DBUILD_opencv_videoio=OFF -DBUILD_opencv_photo=OFF \
    -DBUILD_opencv_flann=OFF -DBUILD_opencv_calib3d=OFF -DBUILD_opencv_features2d=OFF -DBUILD_opencv_objdetect=OFF \
    -DBUILD_opencv_dnn=OFF -DBUILD_opencv_gapi=OFF -DBUILD_opencv_ml=OFF -DBUILD_opencv_highgui=OFF \
    -DBUILD_opencv_stitching=OFF \
    -DWITH_OPENCL=OFF -DWITH_IPP=OFF \
    -DCMAKE_CXX_STANDARD=11 -DCMAKE_CXX_STANDARD_REQUIRED=ON \
    "$OPENCV_DIR"

echo "Building gen_opencv_java_source (JNI codegen) first..."
ninja -j1 gen_opencv_java_source

echo "Building the rest..."
# BUILD_ANDROID_PROJECTS=ON also builds a bundled internal Gradle sub-project (an Android Studio
# sample/AAR wrapper) that can fail on Kotlin/JDK toolchain mismatches unrelated to what we actually
# need — the real libopencv_java4.so and Java sources are produced by plain ninja targets *before*
# that sub-project even starts, so a failure there is tolerated and verified against explicitly below.
ninja || echo "ninja reported a failure (likely OpenCV's bundled Gradle sub-project, not the native build) — verifying required artifacts below regardless."

JNI_SO="$(find "$BUILD_DIR" -path "*/jni/arm64-v8a/libopencv_java4.so" -print -quit)"
JAVA_SRC_DIR="$BUILD_DIR/modules/java_bindings_generator/gen/java/org"
if [ -z "$JNI_SO" ] || [ ! -d "$JAVA_SRC_DIR" ]; then
    echo "ERROR: libopencv_java4.so or Java bindings source not produced. Check $BUILD_DIR for build logs."
    exit 1
fi

mkdir -p "$OUTPUT_DIR/libs/arm64-v8a"
cp "$JNI_SO" "$OUTPUT_DIR/libs/arm64-v8a/libopencv_java4.so"
# libopencv_java4.so is built with BUILD_SHARED_LIBS=ON, so it dynamically links against the
# per-module shared libraries at runtime instead of having them statically linked in — all of them
# must ship in jniLibs too, or dlopen fails at first use with "library ... not found".
for mod in core imgproc imgcodecs; do
    cp "$BUILD_DIR/lib/arm64-v8a/libopencv_${mod}.so" "$OUTPUT_DIR/libs/arm64-v8a/"
done
STRIP_BIN="$TOOLCHAIN_DIR/bin/llvm-strip"
[ -x "$STRIP_BIN" ] && "$STRIP_BIN" --strip-unneeded "$OUTPUT_DIR/libs/arm64-v8a/"*.so

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
echo "  $OUTPUT_DIR/libs/arm64-v8a/libopencv_java4.so"
echo "  $OUTPUT_DIR/java/org/ (Java bindings source)"
