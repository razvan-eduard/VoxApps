#!/bin/bash
set -e

# Regenerates every core/wakeword/patches/*.patch from the CURRENT state of core/wakeword/ vs. the
# CURRENTLY pinned vendor/openwakeword-android-kt submodule. Run this any time you intentionally
# change one of these local patches (not for upstream syncs — that's what sync-openwakeword.yml /
# check_openwakeword_version.sh handle). Each patched file gets its own numbered patch so a future
# upstream change conflicting with only one of them doesn't block the other from re-applying cleanly.

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { printf "${GREEN}%s${NC}\n" "$1"; }
log_error() { printf "${RED}%s${NC}\n" "$1"; }
log_blue() { printf "${BLUE}%s${NC}\n" "$1"; }

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# patch_file:rel_path pairs. rel_path is core/wakeword's copy; the pristine counterpart is the same
# path under vendor/openwakeword-android-kt/wakeword/ with the "core/wakeword/" prefix swapped out.
PATCHES=(
    "core/wakeword/patches/0001-rms-silence-gate.patch:core/wakeword/src/main/kotlin/com/rementia/openwakeword/lib/audio/AudioRecorder.kt"
    "core/wakeword/patches/0002-wakeword-engine-params.patch:core/wakeword/src/main/kotlin/com/rementia/openwakeword/lib/WakeWordEngine.kt"
)

FAILED=0

for ENTRY in "${PATCHES[@]}"; do
    PATCH_FILE="${ENTRY%%:*}"
    REL_PATH="${ENTRY##*:}"
    PRISTINE="vendor/openwakeword-android-kt/wakeword/${REL_PATH#core/wakeword/}"

    if [ ! -f "$PRISTINE" ]; then
        log_error "❌ Submodule not initialized: $PRISTINE not found."
        log_error "   Run: git submodule update --init vendor/openwakeword-android-kt"
        exit 1
    fi

    log_blue "🔧 Regenerating $PATCH_FILE from pristine ($PRISTINE) vs. patched ($REL_PATH)..."

    mkdir -p "$(dirname "$PATCH_FILE")"
    diff -u --label "a/$REL_PATH" --label "b/$REL_PATH" "$PRISTINE" "$REL_PATH" > "$PATCH_FILE" || true

    if [ ! -s "$PATCH_FILE" ]; then
        log_error "⚠️ Generated patch is empty — core/wakeword's copy is identical to the pristine submodule."
        log_error "   Did you forget to apply your change to $REL_PATH first?"
        FAILED=1
        continue
    fi

    # Sanity check: overwrite the working copy with pristine, apply the freshly generated patch, and
    # confirm it reproduces the original patched file byte-for-byte — then restore either way. Runs
    # in-repo (git apply needs a git worktree), exactly how sync-openwakeword.yml uses it for real.
    BACKUP="/tmp/oww_patch_regen_backup_$(basename "$REL_PATH").kt"
    cp "$REL_PATH" "$BACKUP"
    cp "$PRISTINE" "$REL_PATH"

    if git apply --check "$PATCH_FILE" 2>/dev/null && git apply "$PATCH_FILE"; then
        if diff -q "$REL_PATH" "$BACKUP" > /dev/null; then
            log_info "✅ Patch regenerated and verified: re-applying it reproduces the exact patched source."
            log_info "Wrote $PATCH_FILE ($(wc -l < "$PATCH_FILE") lines)."
        else
            log_error "❌ Patch applied but result differs from the original patched file. Investigate."
            cp "$BACKUP" "$REL_PATH"
            rm -f "$BACKUP"
            FAILED=1
            continue
        fi
    else
        log_error "❌ Sanity check failed — the regenerated patch does not apply to its own source. Investigate."
        cp "$BACKUP" "$REL_PATH"
        rm -f "$BACKUP"
        FAILED=1
        continue
    fi
    rm -f "$BACKUP"
done

exit $FAILED
