#!/bin/bash
set -e

# --- COLOR DEFINITIONS ---
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { printf "${GREEN}%s${NC}\n" "$1"; }
log_warn() { printf "${YELLOW}%s${NC}\n" "$1"; }
log_error() { printf "${RED}%s${NC}\n" "$1"; }
log_blue() { printf "${BLUE}%s${NC}\n" "$1"; }

# --- CONFIG ---
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMANDER_TAG="v0.2-beta"
VISION_TAG="vision-v0.1"

# Paths to stripped release libs after a successful build
COMMANDER_SOURCE="$PROJECT_ROOT/vox-commander/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a"
VISION_SOURCE="$PROJECT_ROOT/vox-vision/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a"

COMMANDER_LIBS=(
    "libonnxruntime.so"
    "libllm_inference_engine_jni.so"
    "libvosk.so"
    "libsherpa-onnx-c-api.so"
    "libsherpa-onnx-jni.so"
)

VISION_LIBS=(
    "libonnxruntime.so"
    "libopencv_core.so"
    "libopencv_imgproc.so"
    "libopencv_imgcodecs.so"
    "libopencv_java4.so"
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
    log_warn "Commander source not found. Building..."
    "$PROJECT_ROOT/gradlew" :vox-commander:assembleRelease
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
