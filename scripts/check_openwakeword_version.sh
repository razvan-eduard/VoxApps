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
SUBMODULE_DIR="$PROJECT_ROOT/vendor/openwakeword-android-kt"
UPSTREAM_URL="https://github.com/Re-MENTIA/openwakeword-android-kt.git"

# Unlike Vosk (consumed as an unmodified binary artifact), OpenWakeWord is vendored as source into
# core/wakeword with a local patch (RMS silence gate — see core/wakeword/NOTICE), maintained as a
# real diff at core/wakeword/patches/0001-rms-silence-gate.patch. When a new upstream tag appears,
# the scheduled CI workflow (.github/workflows/sync-openwakeword.yml) re-vendors the sources and
# tries to auto-apply that patch — a PR arrives already merged/tested in the common case, and only
# needs manual work if the patch genuinely conflicts. This script does the same "would it still
# apply?" dry-run locally, non-destructively (the working tree is left untouched either way).

if [ ! -e "$SUBMODULE_DIR/.git" ]; then
    log_warn "⚠️ vendor/openwakeword-android-kt submodule not initialized — skipping check."
    log_warn "   Run: git submodule update --init vendor/openwakeword-android-kt"
    exit 0
fi

CURRENT_TAG=$(git -C "$SUBMODULE_DIR" describe --tags --exact-match 2>/dev/null)
CURRENT_SHA=$(git -C "$SUBMODULE_DIR" rev-parse --short HEAD)

log_blue "🔍 Checking OpenWakeWord version (submodule vs. upstream tags)..."
if [ -n "$CURRENT_TAG" ]; then
    echo "Current: $CURRENT_TAG ($CURRENT_SHA)"
else
    echo "Current: detached at $CURRENT_SHA (no exact tag match)"
fi

# Fetch latest vX.Y.Z tag from upstream (no clone needed — just the ref list)
LATEST_TAG=$(git ls-remote --tags --refs "$UPSTREAM_URL" 2>/dev/null \
    | grep -oE "refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$" \
    | sed 's#refs/tags/##' \
    | sort -V | tail -1)

if [ -z "$LATEST_TAG" ]; then
    log_warn "⚠️ Could not reach upstream (network?) — skipping version check."
    exit 0
fi

if [ "$CURRENT_TAG" != "$LATEST_TAG" ]; then
    log_warn "🚀 UPDATE AVAILABLE: ${CURRENT_TAG:-$CURRENT_SHA} -> $LATEST_TAG"

    # Non-destructive dry-run: fetch the new tag's AudioRecorder.kt content (object database only,
    # no working-tree checkout), swap it in temporarily, try the patch, then always restore.
    REL_PATH="core/wakeword/src/main/kotlin/com/rementia/openwakeword/lib/audio/AudioRecorder.kt"
    PATCH_FILE="$PROJECT_ROOT/core/wakeword/patches/0001-rms-silence-gate.patch"
    NEW_PRISTINE=$(mktemp)

    git -C "$SUBMODULE_DIR" fetch --tags --quiet 2>/dev/null
    UPSTREAM_BLOB=$(git -C "$SUBMODULE_DIR" ls-tree "$LATEST_TAG" -- \
        "wakeword/src/main/kotlin/com/rementia/openwakeword/lib/audio/AudioRecorder.kt" \
        2>/dev/null | awk '{print $3}')
    if [ -n "$UPSTREAM_BLOB" ] && git -C "$SUBMODULE_DIR" cat-file -p "$UPSTREAM_BLOB" > "$NEW_PRISTINE" 2>/dev/null; then

        cp "$PROJECT_ROOT/$REL_PATH" /tmp/oww_check_backup.kt
        cp "$NEW_PRISTINE" "$PROJECT_ROOT/$REL_PATH"

        if (cd "$PROJECT_ROOT" && git apply --check "$PATCH_FILE" 2>/dev/null); then
            log_info "✅ The RMS gate patch would still apply cleanly against $LATEST_TAG."
        else
            log_warn "⚠️ The RMS gate patch would CONFLICT against $LATEST_TAG — manual merge needed."
        fi

        cp /tmp/oww_check_backup.kt "$PROJECT_ROOT/$REL_PATH"
        rm -f /tmp/oww_check_backup.kt
    else
        log_warn "⚠️ Could not fetch AudioRecorder.kt at $LATEST_TAG to dry-run the patch."
    fi
    rm -f "$NEW_PRISTINE"

    echo -e "\nThis is a ${YELLOW}vendored + patched${NC} fork. To update:"
    echo "  1. cd vendor/openwakeword-android-kt && git checkout $LATEST_TAG && cd -"
    echo "  2. git add vendor/openwakeword-android-kt   # re-pin the submodule"
    echo "  3. Re-vendor core/wakeword/src/main/kotlin from the submodule, then re-apply"
    echo "     core/wakeword/patches/0001-rms-silence-gate.patch (git apply it)."
    echo "  4. If it conflicts, resolve by hand, then run ./scripts/regen_openwakeword_patch.sh"
    echo -e "  5. Rebuild + retest before committing.\n"
    echo "(The scheduled sync-openwakeword.yml workflow does all of this automatically and opens a PR —"
    echo " already merged+tested in the common case, or clearly flagged if it needs manual attention.)"
else
    log_info "✅ OpenWakeWord fork is up to date (${CURRENT_TAG:-$CURRENT_SHA})."
fi
