#!/bin/bash
set -e

# Regenerates every core/wakeword/patches/*.patch from the CURRENT state of core/wakeword/ vs. the
# CURRENTLY pinned vendor/openwakeword-android-kt submodule. Run this any time you intentionally
# change one of these local patches (not for upstream syncs — that's what sync-openwakeword.yml /
# check_openwakeword_version.sh handle). Each patched file gets its own numbered patch so a future
# upstream change conflicting with only one of them doesn't block the other from re-applying cleanly.
#
# Which file a patch covers is read out of the patch itself. A hardcoded pairs list here was one more
# thing to remember when adding a patch, and forgetting it is silent: the fork keeps working locally
# and the adaptation disappears at the next re-vendor.
# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

shopt -s nullglob
PATCH_FILES=(core/wakeword/patches/*.patch)
shopt -u nullglob

if [ ${#PATCH_FILES[@]} -eq 0 ]; then
    log_error "❌ No patches found under core/wakeword/patches/."
    exit 1
fi

FAILED=0

for PATCH_FILE in "${PATCH_FILES[@]}"; do
    # The patch is the list. Read it before the file is truncated below.
    REL_PATHS=()
    while read -r path; do
        REL_PATHS+=("$path")
    done < <(git apply --numstat "$PATCH_FILE" | awk '{print $3}')

    if [ ${#REL_PATHS[@]} -eq 0 ]; then
        log_error "❌ Could not read any file paths out of $PATCH_FILE."
        FAILED=1
        continue
    fi

    # The pristine counterpart is the same path under vendor/openwakeword-android-kt/wakeword/ with
    # the "core/wakeword/" prefix swapped out.
    MISSING=false
    for REL_PATH in "${REL_PATHS[@]}"; do
        PRISTINE="vendor/openwakeword-android-kt/wakeword/${REL_PATH#core/wakeword/}"
        if [ ! -f "$PRISTINE" ]; then
            log_error "❌ Submodule not initialized: $PRISTINE not found."
            log_error "   Run: git submodule update --init vendor/openwakeword-android-kt"
            MISSING=true
        fi
    done
    if [ "$MISSING" = true ]; then
        exit 1
    fi

    log_blue "🔧 Regenerating $PATCH_FILE from pristine vs. patched..."

    mkdir -p "$(dirname "$PATCH_FILE")"
    : > "$PATCH_FILE"
    for REL_PATH in "${REL_PATHS[@]}"; do
        PRISTINE="vendor/openwakeword-android-kt/wakeword/${REL_PATH#core/wakeword/}"
        diff -u --label "a/$REL_PATH" --label "b/$REL_PATH" "$PRISTINE" "$REL_PATH" >> "$PATCH_FILE" || true
    done

    if [ ! -s "$PATCH_FILE" ]; then
        log_error "⚠️ Generated patch is empty — core/wakeword's copy is identical to the pristine submodule."
        log_error "   Did you forget to apply your change first?"
        FAILED=1
        continue
    fi

    # Sanity check: overwrite the working copies with pristine, apply the freshly generated patch,
    # and confirm it reproduces the original patched files byte-for-byte — then restore either way.
    # Runs in-repo (git apply needs a git worktree), exactly how sync-openwakeword.yml uses it.
    BACKUP_DIR=$(mktemp -d)
    for REL_PATH in "${REL_PATHS[@]}"; do
        PRISTINE="vendor/openwakeword-android-kt/wakeword/${REL_PATH#core/wakeword/}"
        mkdir -p "$BACKUP_DIR/$(dirname "$REL_PATH")"
        cp "$REL_PATH" "$BACKUP_DIR/$REL_PATH"
        cp "$PRISTINE" "$REL_PATH"
    done

    restore_backup() {
        for REL_PATH in "${REL_PATHS[@]}"; do
            cp "$BACKUP_DIR/$REL_PATH" "$REL_PATH"
        done
        rm -rf "$BACKUP_DIR"
    }

    if git apply --check "$PATCH_FILE" 2>/dev/null && git apply "$PATCH_FILE"; then
        ALL_MATCH=true
        for REL_PATH in "${REL_PATHS[@]}"; do
            if ! diff -q "$REL_PATH" "$BACKUP_DIR/$REL_PATH" > /dev/null; then
                ALL_MATCH=false
            fi
        done
        if [ "$ALL_MATCH" = true ]; then
            log_info "✅ Verified: re-applying it reproduces the exact patched source."
            log_info "Wrote $PATCH_FILE ($(wc -l < "$PATCH_FILE") lines)."
            restore_backup
        else
            log_error "❌ Patch applied but result differs from the original patched file. Investigate."
            restore_backup
            FAILED=1
        fi
    else
        log_error "❌ Sanity check failed — the regenerated patch does not apply to its own source. Investigate."
        restore_backup
        FAILED=1
    fi
done

exit $FAILED
