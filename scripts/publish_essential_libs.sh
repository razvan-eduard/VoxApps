#!/bin/bash
set -e

# --- COLOR DEFINITIONS ---
# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --- CONFIG ---
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Derive tags from each app's versionName at run time (mirrors release-commander.yml/
# release-vision.yml's own tag computation) instead of hardcoding them — hardcoded tags silently
# went stale after past version bumps (were pointing at v0.2-beta/vision-v0.1 while the actual
# current releases were already several versions ahead).
get_version_name() {
    grep 'versionName' "$1" | sed 's/.*"\(.*\)".*/\1/'
}
COMMANDER_TAG="commander-v$(get_version_name "$PROJECT_ROOT/vox-commander/build.gradle.kts")"
VISION_TAG="vision-v$(get_version_name "$PROJECT_ROOT/vox-vision/build.gradle.kts")"

# Where :vox-commander:collectDlcLibs stages them. Not a build/intermediates path: in `full` these
# libraries are excluded before the native-lib merge runs, so no intermediate ever contains them —
# the task copies them straight from the resolved dependencies instead.
COMMANDER_SOURCE="$PROJECT_ROOT/vox-commander/build/dlc-libs"
# Copied straight from where scripts/build_opencv_android.sh writes them (its VISION_JNI_DIR), not
# from a build/intermediates path — AGP 9.x doesn't reliably propagate arm64-v8a native libs through
# its own merge/packaging pipeline for this module (same issue class as Commander's DLC excludes,
# confirmed via extensive testing), so intermediates may not actually contain them.
VISION_SOURCE="$PROJECT_ROOT/vox-vision/src/main/jniLibs/arm64-v8a"

# Kept out of the `full` APK by AGP's packaging.jniLibs.excludes (see vox-commander/build.gradle.kts's
# androidComponents block). libsherpa-onnx-c-api.so/libsherpa-onnx-cxx-api.so are deliberately
# excluded from this list — they're unused dead weight (only libsherpa-onnx-jni.so is actually
# loaded), dropped from the APK but never uploaded/downloaded.
COMMANDER_LIBS=(
    "libonnxruntime.so"
    "libvosk.so"
    "libsherpa-onnx-jni.so"
)

# geometry/flann/features/ptcloud/stereo are OpenCV 5.0+ additions (see NativeLibManager.kt for the
# full dependency chain, confirmed via `readelf -d`); java4 renamed java5 in the same bump.
VISION_LIBS=(
    "libonnxruntime.so"
    "libopencv_core.so"
    "libopencv_flann.so"
    "libopencv_geometry.so"
    "libopencv_imgproc.so"
    "libopencv_imgcodecs.so"
    "libopencv_features.so"
    "libopencv_ptcloud.so"
    "libopencv_stereo.so"
    "libopencv_java5.so"
)

# --- CHECK PREREQUISITES ---
if ! command -v gh &> /dev/null; then
    log_error "GitHub CLI (gh) is not installed. Please install it first."
    exit 1
fi

if ! gh auth status &> /dev/null; then
    log_error "Not authenticated with GitHub. Run: gh auth login"
    exit 1
fi

# --- PUBLISH COMMANDER LIBS ---
log_blue "📦 Publishing Commander DLC libs to: $COMMANDER_TAG"
if [ ! -d "$COMMANDER_SOURCE" ]; then
    # -PvoxDlc=full, because these libraries only become release assets in that mode — a `minimal`
    # build keeps them inside the APK and stages nothing.
    log_warn "Commander DLC libs not staged. Building..."
    "$PROJECT_ROOT/gradlew" :vox-commander:assembleRelease -PvoxDlc=full
fi

for lib in "${COMMANDER_LIBS[@]}"; do
    log_info "Uploading $lib to $COMMANDER_TAG..."
    gh release upload "$COMMANDER_TAG" "$COMMANDER_SOURCE/$lib" --clobber
done

# --- PUBLISH VISION LIBS ---
log_blue "📦 Publishing Vision DLC libs to: $VISION_TAG"
if [ ! -d "$VISION_SOURCE" ]; then
    log_warn "Vision source not found. Building..."
    "$PROJECT_ROOT/gradlew" :vox-vision:assembleRelease
fi

for lib in "${VISION_LIBS[@]}"; do
    log_info "Uploading $lib to $VISION_TAG..."
    gh release upload "$VISION_TAG" "$VISION_SOURCE/$lib" --clobber
done

log_info "🎉 All essential libs published."
