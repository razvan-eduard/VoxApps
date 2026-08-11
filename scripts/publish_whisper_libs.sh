#!/bin/bash
set -e

# --- COLOR DEFINITIONS ---
# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --- PATHS ---
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$PROJECT_ROOT/vox-commander/src/main/jniLibs/arm64-v8a"
TAG="whisper-libs"

# --- LIBS TO UPLOAD (in load order) ---
#
# The build links ggml statically into libwhisper.so, so these two are the whole engine. The release
# also holds libggml*.so assets uploaded by an earlier form of this list; they are left in place for
# installs that still ask for them, and are not refreshed.
LIBS=("libomp.so" "libwhisper.so")

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
    LOCAL_SHA=$(vox_sha256 "$JNI_DIR/$lib")

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

# --- PROVENANCE MARKER ---
# Records which whisper.cpp commit these binaries were built from, so a build can tell whether the
# release still serves the runtime its source pins. Without it the two can only be compared by
# rebuilding whisper and hashing the result, which is twenty minutes nobody spends on a hunch.
#
# Uploaded even when the libraries themselves are unchanged: the marker is what a release job reads,
# and an absent or stale one reads as "unknown", which is the state this exists to remove.
PIN=$(git -C "$PROJECT_ROOT" rev-parse "HEAD:vox-commander/src/main/cpp/whisper.cpp")
MARKER_FILE="$(mktemp -d)/built-from.txt"
printf '%s\n' "$PIN" > "$MARKER_FILE"
REMOTE_PIN=$(gh release view "$TAG" --json assets \
    --jq '.assets[] | select(.name == "built-from.txt") | .name' 2>/dev/null || true)
if [ -z "$REMOTE_PIN" ] || [ "$ALL_MATCH" != true ]; then
    log_info "Recording provenance: built from ${PIN:0:12}"
    gh release upload "$TAG" "$MARKER_FILE" --clobber
fi

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
# Named from the same place the app builds its download URLs (core/identity VoxRepo), rather than
# repeating a repository name that has already been changed once.
log_info "   Download URL: https://github.com/razvan-eduard/VoxApps/releases/download/$TAG/<libname>.so"
