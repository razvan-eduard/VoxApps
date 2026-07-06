#!/bin/bash
set -e

# --- COLOR DEFINITIONS ---
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m'

log_info() { printf "${GREEN}%s${NC}\n" "$1"; }
log_warn() { printf "${YELLOW}%s${NC}\n" "$1"; }
log_error() { printf "${RED}%s${NC}\n" "$1"; }
log_blue() { printf "${BLUE}%s${NC}\n" "$1"; }

# --- PATHS ---
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$PROJECT_ROOT/vox-commander/src/main/jniLibs/arm64-v8a"
TAG="whisper-libs"

# --- LIBS TO UPLOAD (in load order) ---
LIBS=("libomp.so" "libggml.so" "libggml-base.so" "libggml-cpu.so" "libggml-vulkan.so" "libwhisper.so")

# --- CHECK PREREQUISITES ---
if ! command -v gh &> /dev/null; then
    log_warn "GitHub CLI (gh) is not installed. Installing via brew..."
    brew install gh
fi

if ! gh auth status &> /dev/null; then
    log_error "Not authenticated with GitHub. Run: gh auth login"
    exit 1
fi

# --- VERIFY LIBS EXIST ---
for lib in "${LIBS[@]}"; do
    if [ ! -f "$JNI_DIR/$lib" ]; then
        log_error "Missing: $JNI_DIR/$lib"
        exit 1
    fi
done

log_blue "📦 Publishing Whisper libs to GitHub release: $TAG"

# --- CREATE RELEASE IF IT DOESN'T EXIST ---
if gh release view "$TAG" &> /dev/null; then
    log_info "Release '$TAG' already exists. Updating assets..."
else
    log_info "Creating release '$TAG'..."
    gh release create "$TAG" \
        --title "Whisper Native Libraries (arm64-v8a)" \
        --notes "Compiled Whisper.cpp native libraries for Android arm64-v8a. These are downloaded on-demand by the app (DLC)." \
        --target main
fi

# --- SHA COMPARISON & UPLOAD ---
# Compare local SHA vs remote SHA for each lib. Only upload if different.
NEEDS_UPLOAD=()
ALL_MATCH=true

for lib in "${LIBS[@]}"; do
    LOCAL_SHA=$(shasum -a 256 "$JNI_DIR/$lib" | awk '{print $1}')

    # Try to get the remote asset's SHA via the GitHub API
    REMOTE_SHA=$(gh release view "$TAG" --json assets --jq ".assets[] | select(.name == \"$lib\") | .digest" 2>/dev/null | head -1)

    # GitHub API returns digest as "sha256:<hex>" — strip the prefix
    REMOTE_SHA="${REMOTE_SHA#sha256:}"

    if [ -z "$REMOTE_SHA" ]; then
        log_warn "  $lib: not found in release. Will upload."
        NEEDS_UPLOAD+=("$lib")
        ALL_MATCH=false
    elif [ "$LOCAL_SHA" = "$REMOTE_SHA" ]; then
        log_info "  $lib: SHA matches remote. Skipping."
    else
        log_warn "  $lib: SHA differs (local=$LOCAL_SHA vs remote=$REMOTE_SHA). Will upload."
        NEEDS_UPLOAD+=("$lib")
        ALL_MATCH=false
    fi
done

if [ "$ALL_MATCH" = true ]; then
    log_info "✅ All libs are identical to release assets. Nothing to publish."
    exit 0
fi

# --- UPLOAD ONLY CHANGED LIBS ---
for lib in "${NEEDS_UPLOAD[@]}"; do
    log_info "Uploading $lib..."
    # --clobber overwrites existing asset with the same name
    gh release upload "$TAG" "$JNI_DIR/$lib" --clobber
    log_info "  ✅ $lib uploaded ($(du -h "$JNI_DIR/$lib" | cut -f1))"
done

log_info "🎉 Published ${#NEEDS_UPLOAD[@]} lib(s) to release '$TAG'"
log_info "   Download URL: https://github.com/razvan-eduard/VoxCommander/releases/download/$TAG/<libname>.so"
