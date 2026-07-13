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
TAG="v0.2-beta"
# Path to stripped release libs after a successful build
SOURCE_DIR="$PROJECT_ROOT/vox-commander/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a"

ESSENTIAL_LIBS=(
    "libonnxruntime.so"
    "libllm_inference_engine_jni.so"
    "libvosk.so"
    "libsherpa-onnx-c-api.so"
    "libsherpa-onnx-jni.so"
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

# --- VERIFY SOURCE DIR ---
if [ ! -d "$SOURCE_DIR" ]; then
    log_warn "Source directory not found. Building release APK first..."
    "$PROJECT_ROOT/gradlew" :vox-commander:assembleRelease
fi

# --- VERIFY LIBS EXIST ---
for lib in "${ESSENTIAL_LIBS[@]}"; do
    if [ ! -f "$SOURCE_DIR/$lib" ]; then
        log_error "Missing: $SOURCE_DIR/$lib. Please run ./gradlew :vox-commander:assembleRelease"
        exit 1
    fi
done

log_blue "📦 Publishing Essential DLC libs to GitHub release: $TAG"

# --- ENSURE RELEASE EXISTS ---
if ! gh release view "$TAG" &> /dev/null; then
    log_info "Release '$TAG' doesn't exist. Creating it..."
    gh release create "$TAG" --title "VoxCommander $TAG" --notes "Release $TAG with Essential DLC libraries."
fi

# --- UPLOAD ---
for lib in "${ESSENTIAL_LIBS[@]}"; do
    log_info "Uploading $lib..."
    gh release upload "$TAG" "$SOURCE_DIR/$lib" --clobber
    log_info "  ✅ $lib uploaded"
done

log_info "🎉 All essential libs published to $TAG"
log_info "   Apps will now be able to download them on startup."
