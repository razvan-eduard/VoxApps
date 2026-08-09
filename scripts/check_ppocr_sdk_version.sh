#!/bin/bash

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m'

log_info() { printf "${GREEN}%s${NC}\n" "$1"; }
log_warn() { printf "${YELLOW}%s${NC}\n" "$1"; }
log_error() { printf "${RED}%s${NC}\n" "$1"; }
log_blue() { printf "${BLUE}%s${NC}\n" "$1"; }

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUBMODULE_DIR="$PROJECT_ROOT/vendor/paddleocr-upstream"
SUBTREE_PATH="deploy/ppocr-android/ppocr-sdk"
UPSTREAM_URL="https://github.com/PaddlePaddle/PaddleOCR.git"

# Unlike Vosk (consumed as an unmodified binary artifact), the PaddleOCR Android SDK is vendored as
# source into vendor/ppocr-sdk with a local patch (load models from raw bytes/files, not just APK
# assets — see vendor/ppocr-sdk/NOTICE), maintained as a real diff at
# vendor/ppocr-sdk/patches/0001-load-models-from-bytes.patch. This script checks whether upstream has
# moved past our pinned commit on its default branch, and whether the stored patch would still apply
# cleanly against the newer tree — non-destructively (the working tree is left untouched either way).

if [ ! -e "$SUBMODULE_DIR/.git" ]; then
    log_warn "⚠️ vendor/paddleocr-upstream submodule not initialized — skipping check."
    log_warn "   Run: git submodule update --init vendor/paddleocr-upstream"
    log_warn "   Then: git -C vendor/paddleocr-upstream sparse-checkout set $SUBTREE_PATH"
    exit 0
fi

CURRENT_SHA=$(git -C "$SUBMODULE_DIR" rev-parse HEAD)
CURRENT_SHORT=$(git -C "$SUBMODULE_DIR" rev-parse --short HEAD)

log_blue "🔍 Checking PaddleOCR ppocr-android SDK version (submodule vs. upstream default branch)..."
echo "Current: $CURRENT_SHORT"

# Fetch latest default-branch tip (no full clone needed — just the ref).
LATEST_SHA=$(git ls-remote "$UPSTREAM_URL" HEAD 2>/dev/null | awk '{print $1}')

if [ -z "$LATEST_SHA" ]; then
    log_warn "⚠️ Could not reach upstream (network?) — skipping version check."
    exit 0
fi

if [ "$CURRENT_SHA" != "$LATEST_SHA" ]; then
    LATEST_SHORT=${LATEST_SHA:0:9}
    log_warn "🚀 UPDATE AVAILABLE: $CURRENT_SHORT -> $LATEST_SHORT (upstream default branch)"

    # Non-destructive dry-run, once per patch: fetch the files that patch touches at the latest
    # commit (object database only, no working-tree checkout of the whole repo), swap them in
    # temporarily, try the patch, then restore. Which files those are is read out of the patch, so
    # adding a patch to the folder is the only step in adding a patch.
    shopt -s nullglob
    PATCH_FILES=("$PROJECT_ROOT"/vendor/ppocr-sdk/patches/*.patch)
    shopt -u nullglob

    git -C "$SUBMODULE_DIR" fetch --depth 1 origin "$LATEST_SHA" --quiet 2>/dev/null

    # The swap below puts upstream's files into the working tree and copies ours back afterwards.
    # An interrupt in that window would otherwise leave the tree holding upstream's copies, silently
    # reverting our patches — so the restore is bound to EXIT rather than only to the happy path.
    BACKUP_ROOT=$(mktemp -d)
    restore_all() {
        [ -d "$BACKUP_ROOT" ] || return 0
        (cd "$BACKUP_ROOT" && find . -type f -print0 2>/dev/null) | while IFS= read -r -d '' f; do
            cp "$BACKUP_ROOT/${f#./}" "$PROJECT_ROOT/vendor/ppocr-sdk/${f#./}" 2>/dev/null || true
        done
        rm -rf "$BACKUP_ROOT"
    }
    trap 'restore_all' EXIT INT TERM

    for PATCH_FILE in "${PATCH_FILES[@]}"; do
        PATCH_NAME=$(basename "$PATCH_FILE")

        REL_PATHS=()
        while read -r path; do
            REL_PATHS+=("${path#vendor/ppocr-sdk/}")
        done < <(cd "$PROJECT_ROOT" && git apply --numstat "$PATCH_FILE" | awk '{print $3}')

        BACKUP_DIR="$BACKUP_ROOT"
        FETCH_OK=true
        for rel in "${REL_PATHS[@]}"; do
            mkdir -p "$BACKUP_DIR/$(dirname "$rel")"
            cp "$PROJECT_ROOT/vendor/ppocr-sdk/$rel" "$BACKUP_DIR/$rel"
            BLOB=$(git -C "$SUBMODULE_DIR" ls-tree "$LATEST_SHA" -- "$SUBTREE_PATH/$rel" 2>/dev/null | awk '{print $3}')
            if [ -n "$BLOB" ] && git -C "$SUBMODULE_DIR" cat-file -p "$BLOB" > "$PROJECT_ROOT/vendor/ppocr-sdk/$rel" 2>/dev/null; then
                :
            else
                FETCH_OK=false
            fi
        done

        if [ "$FETCH_OK" = true ]; then
            if (cd "$PROJECT_ROOT" && git apply --check "$PATCH_FILE" 2>/dev/null); then
                log_info "✅ $PATCH_NAME would still apply cleanly against upstream's latest."
            else
                log_warn "⚠️ $PATCH_NAME would CONFLICT against upstream's latest — manual merge needed."
            fi
        else
            log_warn "⚠️ Could not fetch one or more of $PATCH_NAME's files at $LATEST_SHORT to dry-run it."
        fi

        for rel in "${REL_PATHS[@]}"; do
            cp "$BACKUP_DIR/$rel" "$PROJECT_ROOT/vendor/ppocr-sdk/$rel"
        done
    done

    restore_all
    trap - EXIT INT TERM

    echo -e "\nThis is a ${YELLOW}vendored + patched${NC} module. To update:"
    echo "  1. cd vendor/paddleocr-upstream && git fetch --depth 1 origin $LATEST_SHA && git checkout $LATEST_SHA && cd -"
    echo "  2. git add vendor/paddleocr-upstream   # re-pin the submodule"
    echo "  3. Re-vendor vendor/ppocr-sdk/src/main from the submodule's deploy/ppocr-android/ppocr-sdk,"
    echo "     then re-apply every patch under vendor/ppocr-sdk/patches/ (git apply each, in name order)."
    echo "  4. If it conflicts, resolve by hand, then run ./scripts/regen_ppocr_sdk_patch.sh"
    echo -e "  5. Rebuild + retest before committing.\n"
else
    log_info "✅ ppocr-sdk vendor is up to date ($CURRENT_SHORT)."
fi
