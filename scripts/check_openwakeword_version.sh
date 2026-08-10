#!/bin/bash

# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --report: key=value on stdout for sync-openwakeword.yml, human logging on stderr.
source "$(dirname "$0")/lib/upstream_report.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUBMODULE_DIR="$PROJECT_ROOT/vendor/openwakeword-android-kt"
UPSTREAM_URL="https://github.com/Re-MENTIA/openwakeword-android-kt.git"

# Unlike Vosk (consumed as an unmodified binary artifact), OpenWakeWord is vendored as source into
# core/wakeword with local patches (RMS silence gate + adaptive noise-floor margin in
# AudioRecorder.kt, constructor-param forwarding in WakeWordEngine.kt — see core/wakeword/NOTICE),
# each maintained as a real diff under core/wakeword/patches/. When a new upstream tag appears, the
# scheduled CI workflow (.github/workflows/sync-openwakeword.yml) re-vendors the sources and tries
# to auto-apply every patch — a PR arrives already merged/tested in the common case, and only needs
# manual work if a patch genuinely conflicts. This script does the same "would it still apply?"
# dry-run locally, non-destructively (the working tree is left untouched either way).

if [ ! -e "$SUBMODULE_DIR/.git" ]; then
    # Answer definitely rather than saying nothing: a silent exit leaves check_upstream.sh reporting
    # "unknown", which reads like a network failure rather than an uninitialised submodule.
    emit has_update false
    log_warn "⚠️ vendor/openwakeword-android-kt submodule not initialized — skipping check."
    log_warn "   Run: git submodule update --init vendor/openwakeword-android-kt"
    exit 0
fi

CURRENT_TAG=$(git -C "$SUBMODULE_DIR" describe --tags --exact-match 2>/dev/null)
CURRENT_SHA=$(git -C "$SUBMODULE_DIR" rev-parse --short HEAD)

log_blue "🔍 Checking OpenWakeWord version (submodule vs. upstream tags)..."
if [ -n "$CURRENT_TAG" ]; then
    log_info "Current: $CURRENT_TAG ($CURRENT_SHA)"
else
    log_info "Current: detached at $CURRENT_SHA (no exact tag match)"
fi

# Fetch latest vX.Y.Z tag from upstream (no clone needed — just the ref list)
LATEST_TAG=$(git ls-remote --tags --refs "$UPSTREAM_URL" 2>/dev/null \
    | grep -oE "refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$" \
    | sed 's#refs/tags/##' \
    | sort -V | tail -1)

emit current_tag "$CURRENT_TAG"
emit current_sha "$CURRENT_SHA"
emit latest_tag "$LATEST_TAG"

if [ -z "$LATEST_TAG" ]; then
    emit has_update false
    log_warn "⚠️ Could not reach upstream (network?) — skipping version check."
    exit 0
fi

if [ "$CURRENT_TAG" != "$LATEST_TAG" ]; then
    emit has_update true
    log_warn "🚀 UPDATE AVAILABLE: ${CURRENT_TAG:-$CURRENT_SHA} -> $LATEST_TAG"

    # Non-destructive dry-run per patched file: fetch the new tag's content (object database only,
    # no working-tree checkout), swap it in temporarily, try that file's patch, then always restore.
    git -C "$SUBMODULE_DIR" fetch --tags --quiet 2>/dev/null

    # Which files a patch covers is read out of the patch, so adding one to the folder is the whole
    # of adding a patch — a hardcoded pairs list here was a second place to remember, and forgetting
    # it fails silently: the adaptation survives locally and vanishes at the next re-vendor.
    shopt -s nullglob
    PATCH_FILES=("$PROJECT_ROOT"/core/wakeword/patches/*.patch)
    shopt -u nullglob

    # This dry-run swaps upstream's files into the working tree and copies ours back afterwards.
    # Without a trap, an interrupt in that window — Ctrl-C, a cancelled build, this script running
    # under autoCheckOpenWakeWord at preBuild — leaves the tree holding pristine upstream files in
    # place of the patched ones. That reverts our adaptations silently, and the wake word one still
    # compiles afterwards: the RMS silence gate would simply be gone.
    BACKUP_ROOT=$(mktemp -d)
    restore_all() {
        [ -d "$BACKUP_ROOT" ] || return 0
        (cd "$BACKUP_ROOT" && find . -type f -print0 2>/dev/null) | while IFS= read -r -d '' f; do
            cp "$BACKUP_ROOT/${f#./}" "$PROJECT_ROOT/${f#./}" 2>/dev/null || true
        done
        rm -rf "$BACKUP_ROOT"
    }
    trap 'restore_all' EXIT INT TERM

    for PATCH_FILE in "${PATCH_FILES[@]}"; do
        REL_PATHS=()
        while read -r path; do
            REL_PATHS+=("$path")
        done < <(cd "$PROJECT_ROOT" && git apply --numstat "$PATCH_FILE" | awk '{print $3}')

        BACKUP_DIR="$BACKUP_ROOT"
        FETCH_OK=true
        for REL_PATH in "${REL_PATHS[@]}"; do
            UPSTREAM_SUBPATH="wakeword/${REL_PATH#core/wakeword/}"
            mkdir -p "$BACKUP_DIR/$(dirname "$REL_PATH")"
            cp "$PROJECT_ROOT/$REL_PATH" "$BACKUP_DIR/$REL_PATH"

            UPSTREAM_BLOB=$(git -C "$SUBMODULE_DIR" ls-tree "$LATEST_TAG" -- "$UPSTREAM_SUBPATH" 2>/dev/null | awk '{print $3}')
            if [ -n "$UPSTREAM_BLOB" ] && git -C "$SUBMODULE_DIR" cat-file -p "$UPSTREAM_BLOB" > "$PROJECT_ROOT/$REL_PATH" 2>/dev/null; then
                :
            else
                FETCH_OK=false
            fi
        done

        if [ "$FETCH_OK" = true ]; then
            if (cd "$PROJECT_ROOT" && git apply --check "$PATCH_FILE" 2>/dev/null); then
                log_info "✅ $(basename "$PATCH_FILE") would still apply cleanly against $LATEST_TAG."
            else
                log_warn "⚠️ $(basename "$PATCH_FILE") would CONFLICT against $LATEST_TAG — manual merge needed."
            fi
        else
            log_warn "⚠️ Could not fetch one or more of $(basename "$PATCH_FILE")'s files at $LATEST_TAG to dry-run it."
        fi

        for REL_PATH in "${REL_PATHS[@]}"; do
            cp "$BACKUP_DIR/$REL_PATH" "$PROJECT_ROOT/$REL_PATH"
        done
    done

    restore_all
    trap - EXIT INT TERM

    if [ "$REPORT" != true ]; then
    echo -e "\nThis is a ${YELLOW}vendored + patched${NC} fork. To update:"
    echo "  1. cd vendor/openwakeword-android-kt && git checkout $LATEST_TAG && cd -"
    echo "  2. git add vendor/openwakeword-android-kt   # re-pin the submodule"
    echo "  3. Re-vendor core/wakeword/src/main/kotlin from the submodule, then re-apply every"
    echo "     patch under core/wakeword/patches/ (git apply each)."
    echo "  4. If any conflicts, resolve by hand, then run ./scripts/vox patches regen wakeword"
    echo -e "  5. Rebuild + retest before committing.\n"
    echo "(The scheduled sync-openwakeword.yml workflow does all of this automatically and opens a PR —"
    echo " already merged+tested in the common case, or clearly flagged if it needs manual attention.)"
    fi
else
    emit has_update false
    log_info "✅ OpenWakeWord fork is up to date (${CURRENT_TAG:-$CURRENT_SHA})."
fi
