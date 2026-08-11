#!/bin/bash
set -e

# --- COLOR DEFINITIONS ---
# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --- PATHS ---
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$PROJECT_ROOT/vox-commander/src/main/jniLibs/arm64-v8a"

# Named for the whisper.cpp commit these libraries were built from, so the address the app asks for
# identifies a build rather than a shelf. A single reused tag served whatever was published last,
# which is how an APK compiled against one whisper.cpp came to run another.
#
# Scoped to the commit rather than to the app version because releases are pruned: tying it to an app
# release would delete the runtime for installs that stay on an older version, and many app versions
# share one whisper build anyway.
WHISPER_COMMIT=$(git -C "$PROJECT_ROOT" rev-parse "HEAD:vox-commander/src/main/cpp/whisper.cpp")
TAG="whisper-libs-${WHISPER_COMMIT:0:12}"

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
        --title "Whisper Native Libraries (arm64-v8a) — ${WHISPER_COMMIT:0:12}" \
        --notes "Compiled from whisper.cpp $WHISPER_COMMIT for Android arm64-v8a. Downloaded on demand by builds pinning that commit; the tag names the build so an app cannot be served a different one." \
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
