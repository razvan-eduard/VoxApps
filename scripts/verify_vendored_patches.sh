#!/bin/bash
set -e

# Asserts that each vendored fork is exactly its pinned upstream plus the patches in its patches/
# folder — nothing more, nothing less.
#
# The failure this exists for: an adaptation made directly in the vendored copy and never captured
# as a patch. It works, it is committed, it passes review — and the next sync copies upstream over
# it and it is gone. Five such edits existed across the two forks when this was written. Only one of
# them would have stopped compiling; the rest would have shipped as an intermittent native crash, an
# OpenCV that never initialises, and wake-word logging noise.
#
# Run it after editing anything under a vendored source tree. If it fails and the change was
# deliberate, capture it: write the diff as a new patches/000N-<name>.patch, then run the module's
# regen script (regen_ppocr_sdk_patch.sh / regen_openwakeword_patch.sh) to normalise and verify it.
#
# Usage: verify_vendored_patches.sh [module ...]   (default: every module below)
# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# name : vendored dir : pristine upstream dir : subdirectory to compare
MODULES=(
    "ppocr-sdk:vendor/ppocr-sdk:vendor/paddleocr-upstream/deploy/ppocr-android/ppocr-sdk:src/main"
    "wakeword:core/wakeword:vendor/openwakeword-android-kt/wakeword:src/main"
)

WANTED=("$@")
FAILED=0

for ENTRY in "${MODULES[@]}"; do
    IFS=':' read -r NAME PATCHED_DIR PRISTINE_DIR SUBDIR <<< "$ENTRY"

    if [ ${#WANTED[@]} -gt 0 ]; then
        SKIP=true
        for w in "${WANTED[@]}"; do [ "$w" = "$NAME" ] && SKIP=false; done
        [ "$SKIP" = true ] && continue
    fi

    if [ ! -d "$PRISTINE_DIR/$SUBDIR" ]; then
        log_error "❌ $NAME: pristine upstream not checked out at $PRISTINE_DIR/$SUBDIR."
        log_error "   Run: git submodule update --init ${PRISTINE_DIR%%/*}/${PRISTINE_DIR#*/}"
        FAILED=1
        continue
    fi

    shopt -s nullglob
    PATCH_FILES=("$PATCHED_DIR"/patches/*.patch)
    shopt -u nullglob

    log_blue "🔍 $NAME: rebuilding $PATCHED_DIR/$SUBDIR from pristine + ${#PATCH_FILES[@]} patch(es)..."

    # The patches carry full repo-relative paths (a/vendor/ppocr-sdk/..., a/core/wakeword/...), so
    # the scratch tree has to be shaped like the repo for `git apply -p1` to land files where the
    # patch expects them.
    SCRATCH=$(mktemp -d)
    mkdir -p "$SCRATCH/$PATCHED_DIR"
    cp -R "$PRISTINE_DIR/${SUBDIR%%/*}" "$SCRATCH/$PATCHED_DIR/"

    MODULE_FAILED=false
    for PATCH_FILE in "${PATCH_FILES[@]}"; do
        if (cd "$SCRATCH" && git apply -p1 "$PROJECT_ROOT/$PATCH_FILE" 2>/dev/null); then
            log_info "  ✅ applied $(basename "$PATCH_FILE")"
        else
            log_error "  ❌ $(basename "$PATCH_FILE") does not apply to the pinned upstream tree."
            MODULE_FAILED=true
        fi
    done

    if [ "$MODULE_FAILED" = true ]; then
        log_error "❌ $NAME: at least one patch no longer applies. Run its regen script."
        rm -rf "$SCRATCH"
        FAILED=1
        continue
    fi

    # .DS_Store is Finder litter, untracked and irrelevant to what the module compiles.
    if DIFF=$(diff -r -x '.DS_Store' "$SCRATCH/$PATCHED_DIR/$SUBDIR" "$PATCHED_DIR/$SUBDIR" 2>&1); then
        log_info "✅ $NAME: $PATCHED_DIR/$SUBDIR is exactly upstream + patches."
    else
        log_error "❌ $NAME: $PATCHED_DIR/$SUBDIR differs from upstream + patches."
        echo
        log_warn "The difference below is a local modification that no patch records. A re-vendor"
        log_warn "will silently discard it — including changes that still compile afterwards, which"
        log_warn "are the ones that reach users."
        echo
        echo "$DIFF" | head -60
        echo
        log_warn "If the change is deliberate: write it as $PATCHED_DIR/patches/000N-<name>.patch,"
        log_warn "then run the module's regen script to normalise and verify it."
        FAILED=1
    fi

    rm -rf "$SCRATCH"
done

exit $FAILED
