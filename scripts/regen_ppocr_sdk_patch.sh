#!/bin/bash
set -e

# Regenerates vendor/ppocr-sdk/patches/0001-load-models-from-bytes.patch from the CURRENT state of
# vendor/ppocr-sdk/ vs. the CURRENTLY pinned vendor/paddleocr-upstream submodule (sparse-checked-out
# to deploy/ppocr-android/ppocr-sdk). Run this any time you intentionally change the byte-loading
# patch itself (not for upstream syncs — that's what scripts/check_ppocr_sdk_version.sh handles).

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { printf "${GREEN}%s${NC}\n" "$1"; }
log_error() { printf "${RED}%s${NC}\n" "$1"; }
log_blue() { printf "${BLUE}%s${NC}\n" "$1"; }

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

UPSTREAM_SUBTREE="vendor/paddleocr-upstream/deploy/ppocr-android/ppocr-sdk"
PATCHED_DIR="vendor/ppocr-sdk"
PATCH_FILE="vendor/ppocr-sdk/patches/0001-load-models-from-bytes.patch"

# The 4 files this patch touches (see vendor/ppocr-sdk/NOTICE).
REL_PATHS=(
    "src/main/java/com/paddle/ocr/engine/ORTSessionManager.kt"
    "src/main/java/com/paddle/ocr/model/ModelConfig.kt"
    "src/main/java/com/paddle/ocr/engine/OCREngine.kt"
    "src/main/java/com/paddle/ocr/PaddleOCR.kt"
)

if [ ! -d "$UPSTREAM_SUBTREE" ]; then
    log_error "❌ Submodule not initialized/sparse-checked-out: $UPSTREAM_SUBTREE not found."
    log_error "   Run: git submodule update --init vendor/paddleocr-upstream"
    log_error "   Then: git -C vendor/paddleocr-upstream sparse-checkout set deploy/ppocr-android/ppocr-sdk"
    exit 1
fi

log_blue "🔧 Regenerating $PATCH_FILE from pristine ($UPSTREAM_SUBTREE) vs. patched ($PATCHED_DIR)..."

mkdir -p "$(dirname "$PATCH_FILE")"
: > "$PATCH_FILE"
for rel in "${REL_PATHS[@]}"; do
    diff -u --label "a/$PATCHED_DIR/$rel" --label "b/$PATCHED_DIR/$rel" \
        "$UPSTREAM_SUBTREE/$rel" "$PATCHED_DIR/$rel" >> "$PATCH_FILE" || true
done

if [ ! -s "$PATCH_FILE" ]; then
    log_error "⚠️ Generated patch is empty — vendor/ppocr-sdk's copy is identical to the pristine submodule."
    log_error "   Did you forget to apply your change first?"
    exit 1
fi

# Sanity check: overwrite the 4 working files with pristine, apply the freshly generated patch, and
# confirm it reproduces the original patched files byte-for-byte — then restore either way.
BACKUP_DIR=$(mktemp -d)
for rel in "${REL_PATHS[@]}"; do
    mkdir -p "$BACKUP_DIR/$(dirname "$rel")"
    cp "$PATCHED_DIR/$rel" "$BACKUP_DIR/$rel"
    cp "$UPSTREAM_SUBTREE/$rel" "$PATCHED_DIR/$rel"
done

restore_backup() {
    for rel in "${REL_PATHS[@]}"; do
        cp "$BACKUP_DIR/$rel" "$PATCHED_DIR/$rel"
    done
    rm -rf "$BACKUP_DIR"
}

if git apply --check "$PATCH_FILE" 2>/dev/null && git apply "$PATCH_FILE"; then
    ALL_MATCH=true
    for rel in "${REL_PATHS[@]}"; do
        if ! diff -q "$PATCHED_DIR/$rel" "$BACKUP_DIR/$rel" > /dev/null; then
            ALL_MATCH=false
        fi
    done
    if [ "$ALL_MATCH" = true ]; then
        log_info "✅ Patch regenerated and verified: re-applying it reproduces the exact patched source."
        restore_backup
    else
        log_error "❌ Patch applied but result differs from the original patched files. Investigate."
        restore_backup
        exit 1
    fi
else
    log_error "❌ Sanity check failed — the regenerated patch does not apply to its own source. Investigate."
    restore_backup
    exit 1
fi

log_info "Wrote $PATCH_FILE ($(wc -l < "$PATCH_FILE") lines)."
