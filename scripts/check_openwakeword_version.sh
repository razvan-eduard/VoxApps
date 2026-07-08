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
# core/wakeword with a local patch (RMS silence gate — see core/wakeword/NOTICE). So an available
# update can't be a one-line version bump: it needs the new source re-copied and the patch
# re-applied/verified by hand, which is why this only WARNS (same as Vosk) and why the CI sync
# workflow (.github/workflows/sync-openwakeword.yml) opens a PR instead of auto-merging.

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
    echo -e "\nThis is a ${YELLOW}vendored + patched${NC} fork, not a plain version bump. To update:"
    echo "  1. cd vendor/openwakeword-android-kt && git fetch --tags && git checkout $LATEST_TAG && cd -"
    echo "  2. git add vendor/openwakeword-android-kt   # re-pin the submodule"
    echo "  3. Diff vendor/openwakeword-android-kt/wakeword/src/main/kotlin against core/wakeword/src/main/kotlin,"
    echo "     re-copy changed files, and re-apply the RMS gate patch (search for"
    echo "     '${BLUE}VoxCommander patch${NC}' markers in core/wakeword/WakeWordEngine.kt)."
    echo -e "  4. Rebuild + retest before committing.\n"
    echo "(The scheduled sync-openwakeword.yml workflow opens a PR for this automatically — this script"
    echo " is the same check run locally/at build time.)"
else
    log_info "✅ OpenWakeWord fork is up to date (${CURRENT_TAG:-$CURRENT_SHA})."
fi
