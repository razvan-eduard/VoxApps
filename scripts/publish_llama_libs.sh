#!/bin/bash
set -e

# --- COLOR DEFINITIONS ---
# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --- PATHS ---
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$PROJECT_ROOT/vox-commander/src/main/jniLibs/arm64-v8a"

# Named for the llama.cpp commit the library was built from, so the address the app asks for
# identifies a build rather than a shelf — same rule as whisper's tags. Published releases are
# permanent: a tag's assets are never deleted or replaced, so every address an installed APK
# carries keeps resolving.
LLAMA_COMMIT=$(git -C "$PROJECT_ROOT" rev-parse "HEAD:vox-commander/src/main/cpp/llama.cpp")
TAG="llama-libs-${LLAMA_COMMIT:0:12}"

# --verify: after publishing (or when everything already matches), download each asset over the
# same anonymous URL every install uses and hash it against the local build. The API digest
# comparison below answers "what does GitHub think it stored"; this answers "what does the
# release actually serve" — an interrupted --clobber can make those differ.
VERIFY=false
for arg in "$@"; do
    case "$arg" in
        --verify) VERIFY=true ;;
        *) log_error "Unknown argument: $arg"; exit 1 ;;
    esac
done

verify_remote() {
    local repo base lib tmp rc=0
    repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
    base="https://github.com/$repo/releases/download/$TAG"
    for lib in "${LIBS[@]}"; do
        tmp=$(mktemp)
        if ! curl -sSLf --max-time 900 -o "$tmp" "$base/$lib"; then
            log_error "  $lib: could not download from $base"
            rc=1
        elif [ "$(vox_sha256 "$tmp")" != "$(vox_sha256 "$JNI_DIR/$lib")" ]; then
            log_error "  $lib: served bytes differ from the local build"
            rc=1
        else
            log_info "  $lib: release serves the local build's bytes"
        fi
        rm -f "$tmp"
    done
    return $rc
}

# --- LIBS TO UPLOAD (in load order) ---
#
# One library: ggml is linked statically and OpenMP is compiled out, so libllama.so is the whole
# engine.
LIBS=("libllama.so")

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

log_blue "📦 Publishing llama libs to GitHub release: $TAG"

# --- CREATE RELEASE IF IT DOESN'T EXIST ---
if gh release view "$TAG" &> /dev/null; then
    log_info "Release '$TAG' already exists. Updating assets..."
else
    log_info "Creating release '$TAG'..."
    gh release create "$TAG" \
        --title "llama.cpp Native Library (arm64-v8a) — ${LLAMA_COMMIT:0:12}" \
        --notes "Compiled from llama.cpp $LLAMA_COMMIT for Android arm64-v8a (CPU backend, static ggml). Downloaded on demand by builds pinning that commit; the tag names the build so an app cannot be served a different one." \
        --target main
fi

# --- SHA COMPARISON & UPLOAD ---
# Compare local SHA vs remote SHA for each lib. Only upload if different.
NEEDS_UPLOAD=()
ALL_MATCH=true

for lib in "${LIBS[@]}"; do
    LOCAL_SHA=$(vox_sha256 "$JNI_DIR/$lib")

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
    if $VERIFY; then
        log_blue "🔍 Reading back what the release serves..."
        verify_remote || exit 1
    fi
    exit 0
fi

# --- UPLOAD ONLY CHANGED LIBS ---
for lib in "${NEEDS_UPLOAD[@]}"; do
    log_info "Uploading $lib..."
    # --clobber overwrites existing asset with the same name
    gh release upload "$TAG" "$JNI_DIR/$lib" --clobber
    log_info "  ✅ $lib uploaded ($(du -h "$JNI_DIR/$lib" | cut -f1))"
done

if $VERIFY; then
    log_blue "🔍 Reading back what the release serves..."
    verify_remote || exit 1
fi

log_info "🎉 Published ${#NEEDS_UPLOAD[@]} lib(s) to release '$TAG'"
# Named from the same place the app builds its download URLs (core/identity VoxRepo), rather than
# repeating a repository name that has already been changed once.
log_info "   Download URL: https://github.com/razvan-eduard/VoxApps/releases/download/$TAG/libllama.so"
