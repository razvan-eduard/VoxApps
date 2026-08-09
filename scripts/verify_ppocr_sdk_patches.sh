#!/bin/bash
set -e

# Asserts that vendor/ppocr-sdk/src/main is exactly the pinned upstream subtree plus the patches in
# vendor/ppocr-sdk/patches/ — nothing more, nothing less.
#
# The failure this exists for: an adaptation made directly in the vendored copy and never captured
# as a patch. It works, it is committed, it passes review — and the next sync copies upstream over
# it and it is gone. That happened twice here without anyone noticing. Once it was the OpenCV 5
# geometry API, which at least stopped compiling; the other two were the manual resize (a workaround
# for a native SIGSEGV) and the opencv_java5 library name, and neither of those would have failed a
# build at all. They would have shipped as an intermittent on-device crash and a failure to
# initialise OpenCV.
#
# Run it after editing anything under vendor/ppocr-sdk/src. If it fails and the change was
# deliberate, capture it: write the diff as a new patches/000N-*.patch, then run
# scripts/regen_ppocr_sdk_patch.sh to normalise and verify it.

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { printf "${GREEN}%s${NC}\n" "$1"; }
log_warn() { printf "${YELLOW}%s${NC}\n" "$1"; }
log_error() { printf "${RED}%s${NC}\n" "$1"; }
log_blue() { printf "${BLUE}%s${NC}\n" "$1"; }

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

UPSTREAM_SUBTREE="vendor/paddleocr-upstream/deploy/ppocr-android/ppocr-sdk"
PATCHED_DIR="vendor/ppocr-sdk"

if [ ! -d "$UPSTREAM_SUBTREE/src/main" ]; then
    log_error "❌ Submodule not initialized/sparse-checked-out: $UPSTREAM_SUBTREE not found."
    log_error "   Run: git submodule update --init vendor/paddleocr-upstream"
    log_error "   Then: git -C vendor/paddleocr-upstream sparse-checkout set deploy/ppocr-android/ppocr-sdk"
    exit 1
fi

shopt -s nullglob
PATCH_FILES=("$PATCHED_DIR"/patches/*.patch)
shopt -u nullglob

log_blue "🔍 Rebuilding $PATCHED_DIR/src/main from pristine + ${#PATCH_FILES[@]} patch(es) and comparing..."

# The patches carry full repo-relative paths (a/vendor/ppocr-sdk/...), so the scratch tree has to be
# shaped like the repo for `git apply -p1` to land the files where the patch expects them.
SCRATCH=$(mktemp -d)
trap 'rm -rf "$SCRATCH"' EXIT
mkdir -p "$SCRATCH/$PATCHED_DIR"
cp -R "$UPSTREAM_SUBTREE/src" "$SCRATCH/$PATCHED_DIR/"

FAILED=false
for PATCH_FILE in "${PATCH_FILES[@]}"; do
    if (cd "$SCRATCH" && git apply -p1 "$PROJECT_ROOT/$PATCH_FILE" 2>/dev/null); then
        log_info "  ✅ applied $(basename "$PATCH_FILE")"
    else
        log_error "  ❌ $(basename "$PATCH_FILE") does not apply to the pinned upstream tree."
        FAILED=true
    fi
done

if [ "$FAILED" = true ]; then
    log_error "❌ At least one patch no longer applies. Run scripts/regen_ppocr_sdk_patch.sh."
    exit 1
fi

# .DS_Store is Finder litter, untracked and irrelevant to what the module compiles.
if DIFF=$(diff -r -x '.DS_Store' "$SCRATCH/$PATCHED_DIR/src/main" "$PATCHED_DIR/src/main" 2>&1); then
    log_info "✅ vendor/ppocr-sdk/src/main is exactly upstream + patches."
else
    log_error "❌ vendor/ppocr-sdk/src/main differs from upstream + patches."
    echo
    log_warn "The difference below is a local modification that no patch records. A re-vendor will"
    log_warn "silently discard it — including changes that still compile afterwards, which are the"
    log_warn "ones that reach users."
    echo
    echo "$DIFF" | head -60
    echo
    log_warn "If the change is deliberate: write it as vendor/ppocr-sdk/patches/000N-<name>.patch,"
    log_warn "then run scripts/regen_ppocr_sdk_patch.sh to normalise and verify it."
    exit 1
fi
