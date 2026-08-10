#!/bin/bash

# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --report: key=value on stdout, human logging on stderr. No sync workflow watches this one — it is
# the only vendored dependency with no bot — so this is what surfaces it in check_upstream.sh.
# shellcheck source=scripts/lib/upstream_report.sh
source "$(dirname "$0")/lib/upstream_report.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUBMODULE_DIR="$PROJECT_ROOT/vendor/makeacopy-upstream"
UPSTREAM_URL="https://github.com/egdels/makeacopy.git"

# Unlike vendor/ppocr-sdk (a straight vendored copy of upstream Java, modified via a stored,
# re-appliable patch), vendor/docquad-sdk is a from-scratch Kotlin PORT of 4 of upstream's Java
# files — see vendor/docquad-sdk/NOTICE. A textual patch can't be dry-run across a language
# rewrite, so this script is informational only: it reports whether upstream has moved past our
# pinned commit, and (best-effort) whether the 4 ported files changed at all since then, so a human
# knows whether a manual re-port is worth doing. It never modifies anything.

if [ ! -e "$SUBMODULE_DIR/.git" ]; then
    # Answer even when we cannot check. Exiting silently leaves a caller with no output at all,
    # which a workflow reads as "no update" and a person reads as nothing — the same guard-before-
    # answering shape that quietly retired the ppocr sync bot.
    emit has_update false
    log_warn "⚠️ vendor/makeacopy-upstream submodule not initialized — skipping check."
    log_warn "   Run: git submodule update --init vendor/makeacopy-upstream"
    log_warn "   Then: git -C vendor/makeacopy-upstream sparse-checkout set --no-cone \\"
    log_warn "           'app/src/main/java/de/schliweb/makeacopy/ml/*' 'app/src/main/assets/docquad/*'"
    exit 0
fi

CURRENT_SHA=$(git -C "$SUBMODULE_DIR" rev-parse HEAD)
CURRENT_SHORT=$(git -C "$SUBMODULE_DIR" rev-parse --short HEAD)

log_blue "🔍 Checking MakeACopy DocQuad ML detector version (submodule vs. upstream default branch)..."
log_info "Current: $CURRENT_SHORT"

LATEST_SHA=$(git ls-remote "$UPSTREAM_URL" HEAD 2>/dev/null | awk '{print $1}')

emit current_sha "$CURRENT_SHA"
emit latest_sha "$LATEST_SHA"

if [ -z "$LATEST_SHA" ]; then
    emit has_update false
    log_warn "⚠️ Could not reach upstream (network?) — skipping version check."
    exit 0
fi

if [ "$CURRENT_SHA" != "$LATEST_SHA" ]; then
    emit has_update true
    LATEST_SHORT=${LATEST_SHA:0:9}
    log_warn "🚀 UPDATE AVAILABLE: $CURRENT_SHORT -> $LATEST_SHORT (upstream default branch)"

    # Best-effort, non-destructive: diff the 4 ported files' pristine upstream content at HEAD vs.
    # at the latest commit, so a human can see at a glance whether anything material changed
    # (working tree is left untouched either way — this only fetches objects, doesn't check out).
    REL_PATHS=(
        "app/src/main/java/de/schliweb/makeacopy/ml/docquad/DocQuadLetterbox.java"
        "app/src/main/java/de/schliweb/makeacopy/ml/docquad/DocQuadOrtRunner.java"
        "app/src/main/java/de/schliweb/makeacopy/ml/docquad/DocQuadPostprocessor.java"
        "app/src/main/java/de/schliweb/makeacopy/ml/corners/DocQuadDetector.java"
    )

    git -C "$SUBMODULE_DIR" fetch --depth 1 origin "$LATEST_SHA" --quiet 2>/dev/null

    ANY_CHANGED=false
    for rel in "${REL_PATHS[@]}"; do
        OLD_BLOB=$(git -C "$SUBMODULE_DIR" ls-tree "$CURRENT_SHA" -- "$rel" 2>/dev/null | awk '{print $3}')
        NEW_BLOB=$(git -C "$SUBMODULE_DIR" ls-tree "$LATEST_SHA" -- "$rel" 2>/dev/null | awk '{print $3}')
        if [ -z "$NEW_BLOB" ]; then
            log_warn "   ⚠️ $rel no longer exists upstream — was it renamed/removed?"
            ANY_CHANGED=true
        elif [ "$OLD_BLOB" != "$NEW_BLOB" ]; then
            log_warn "   📝 $rel changed upstream since our pinned commit."
            ANY_CHANGED=true
        fi
    done

    if [ "$ANY_CHANGED" = true ]; then
        echo -e "\nOne or more of the 4 ${YELLOW}ported${NC} files changed upstream. To review:"
        echo "  1. cd vendor/makeacopy-upstream && git fetch --depth 1 origin $LATEST_SHA && git checkout $LATEST_SHA && cd -"
        echo "  2. git add vendor/makeacopy-upstream   # re-pin the submodule"
        echo "  3. Diff the changed file(s) against vendor/docquad-sdk's Kotlin port by hand and"
        echo "     decide whether the behavior change is worth re-porting (see vendor/docquad-sdk/NOTICE"
        echo "     for exactly what was simplified/dropped versus upstream, so you know what's"
        echo "     intentional vs. what would need updating)."
        echo -e "  4. Rebuild + retest before committing.\n"
    else
        log_info "✅ None of the 4 ported files changed upstream — safe to just re-pin the submodule."
    fi
else
    emit has_update false
    log_info "✅ docquad-sdk vendor is up to date ($CURRENT_SHORT)."
fi
