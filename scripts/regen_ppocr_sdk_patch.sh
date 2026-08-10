#!/bin/bash
set -e

# Regenerates every patch under vendor/ppocr-sdk/patches/ from the CURRENT state of
# vendor/ppocr-sdk/ vs. the CURRENTLY pinned vendor/paddleocr-upstream submodule (sparse-checked-out
# to deploy/ppocr-android/ppocr-sdk). Run this any time you intentionally change one of the patches
# (not for upstream syncs — that's what scripts/check_ppocr_sdk_version.sh handles).
#
# Which files belong to which patch is read out of the patch itself. A hardcoded list here was one
# more thing to remember when adding a patch, and forgetting it is silent: the fork keeps working
# locally and the adaptation disappears at the next re-vendor.
# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

UPSTREAM_SUBTREE="vendor/paddleocr-upstream/deploy/ppocr-android/ppocr-sdk"
PATCHED_DIR="vendor/ppocr-sdk"

if [ ! -d "$UPSTREAM_SUBTREE" ]; then
    log_error "❌ Submodule not initialized/sparse-checked-out: $UPSTREAM_SUBTREE not found."
    log_error "   Run: git submodule update --init vendor/paddleocr-upstream"
    log_error "   Then: git -C vendor/paddleocr-upstream sparse-checkout set deploy/ppocr-android/ppocr-sdk"
    exit 1
fi

shopt -s nullglob
PATCH_FILES=("$PATCHED_DIR"/patches/*.patch)
shopt -u nullglob

if [ ${#PATCH_FILES[@]} -eq 0 ]; then
    log_error "❌ No patches found under $PATCHED_DIR/patches/."
    exit 1
fi

for PATCH_FILE in "${PATCH_FILES[@]}"; do
    log_blue "🔧 Regenerating $PATCH_FILE from pristine ($UPSTREAM_SUBTREE) vs. patched ($PATCHED_DIR)..."

    # Read the file list before the patch is truncated below — the patch is the list.
    REL_PATHS=()
    while read -r path; do
        REL_PATHS+=("${path#"$PATCHED_DIR"/}")
    done < <(git apply --numstat "$PATCH_FILE" | awk '{print $3}')

    if [ ${#REL_PATHS[@]} -eq 0 ]; then
        log_error "❌ Could not read any file paths out of $PATCH_FILE."
        exit 1
    fi

    : > "$PATCH_FILE"
    for rel in "${REL_PATHS[@]}"; do
        diff -u --label "a/$PATCHED_DIR/$rel" --label "b/$PATCHED_DIR/$rel" \
            "$UPSTREAM_SUBTREE/$rel" "$PATCHED_DIR/$rel" >> "$PATCH_FILE" || true
    done

    if [ ! -s "$PATCH_FILE" ]; then
        log_error "⚠️ Generated patch is empty — vendor/ppocr-sdk's copy of those files is identical"
        log_error "   to the pristine submodule. Did you forget to apply your change first?"
        exit 1
    fi

    # Sanity check: overwrite this patch's files with pristine, apply the freshly generated patch,
    # and confirm it reproduces the original patched files byte-for-byte — then restore either way.
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
            log_info "✅ Verified: re-applying it reproduces the exact patched source."
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
done
