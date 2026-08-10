#!/bin/bash

# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --report: key=value on stdout for sync-ppocr-sdk.yml, human logging on stderr.
source "$(dirname "$0")/lib/upstream_report.sh"

# Must match STALENESS_FLOOR_DAYS in .github/workflows/sync-ppocr-sdk.yml. This is the only upstream
# followed by default branch rather than by tag, so nobody upstream decides a commit is ready — the
# wait stands in for that. Reporting the tip here while the bot takes a week-old commit would have
# this script announce updates the bot will not act on.
STALENESS_FLOOR_DAYS=7

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUBMODULE_DIR="$PROJECT_ROOT/vendor/paddleocr-upstream"
SUBTREE_PATH="deploy/ppocr-android/ppocr-sdk"
UPSTREAM_URL="https://github.com/PaddlePaddle/PaddleOCR.git"

# Unlike Vosk (consumed as an unmodified binary artifact), the PaddleOCR Android SDK is vendored as
# source into vendor/ppocr-sdk with a local patch (load models from raw bytes/files, not just APK
# assets — see vendor/ppocr-sdk/NOTICE), maintained as a real diff at
# vendor/ppocr-sdk/patches/0001-load-models-from-bytes.patch. This script checks whether upstream has
# moved past our pinned commit on its default branch, and whether the stored patch would still apply
# cleanly against the newer tree — non-destructively (the working tree is left untouched either way).

# Read the pin out of the repo tree, not out of the submodule working copy: detection has to work
# where the submodule was never checked out — which is exactly how sync-ppocr-sdk.yml runs
# (submodules: false, it clones the sparse upstream itself later). Gating the whole script on the
# submodule made this report empty on the runner and silently retired the bot.
HAVE_SUBMODULE=false
[ -e "$SUBMODULE_DIR/.git" ] && HAVE_SUBMODULE=true

CURRENT_SHA=$(git -C "$PROJECT_ROOT" ls-tree HEAD vendor/paddleocr-upstream | awk '{print $3}')
CURRENT_SHORT=${CURRENT_SHA:0:9}

log_blue "🔍 Checking PaddleOCR ppocr-android SDK version (submodule vs. upstream default branch)..."
log_info "Current: $CURRENT_SHORT"

# Deliberately NOT the tip: the newest commit at least STALENESS_FLOOR_DAYS old, which is what the
# sync workflow will actually take. Unauthenticated GitHub API — 60 requests/hour is ample for a
# manual check.
UNTIL=$(date -u -v-"${STALENESS_FLOOR_DAYS}"d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
        || date -u -d "${STALENESS_FLOOR_DAYS} days ago" +%Y-%m-%dT%H:%M:%SZ)
# Authenticated when a token is around (CI always, a local `gh auth token` if you have one):
# unauthenticated GitHub API calls from Actions runners share heavily rate-limited IPs, so a bare
# curl there is a coin flip. 60/hour unauthenticated is still ample for a manual run.
GH_API_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-$(gh auth token 2>/dev/null || true)}}"
AUTH_HEADER=()
[ -n "$GH_API_TOKEN" ] && AUTH_HEADER=(-H "Authorization: Bearer $GH_API_TOKEN")

LATEST_SHA=$(curl -s --connect-timeout 5 --max-time 10 "${AUTH_HEADER[@]}" \
    "https://api.github.com/repos/PaddlePaddle/PaddleOCR/commits?until=$UNTIL&per_page=1" \
    | grep -m1 -oE '"sha": *"[0-9a-f]{40}"' | grep -oE '[0-9a-f]{40}')

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

    if [ "$HAVE_SUBMODULE" != true ]; then
        log_warn "   (vendor/paddleocr-upstream not checked out — skipping the would-it-still-apply dry-run)"
        log_warn "    git submodule update --init vendor/paddleocr-upstream to enable it."
    else

    # Non-destructive dry-run, once per patch: fetch the files that patch touches at the latest
    # commit (object database only, no working-tree checkout of the whole repo), swap them in
    # temporarily, try the patch, then restore. Which files those are is read out of the patch, so
    # adding a patch to the folder is the only step in adding a patch.
    shopt -s nullglob
    PATCH_FILES=("$PROJECT_ROOT"/vendor/ppocr-sdk/patches/*.patch)
    shopt -u nullglob

    git -C "$SUBMODULE_DIR" fetch --depth 1 origin "$LATEST_SHA" --quiet 2>/dev/null

    # The swap below puts upstream's files into the working tree and copies ours back afterwards.
    # An interrupt in that window would otherwise leave the tree holding upstream's copies, silently
    # reverting our patches — so the restore is bound to EXIT rather than only to the happy path.
    BACKUP_ROOT=$(mktemp -d)
    restore_all() {
        [ -d "$BACKUP_ROOT" ] || return 0
        (cd "$BACKUP_ROOT" && find . -type f -print0 2>/dev/null) | while IFS= read -r -d '' f; do
            cp "$BACKUP_ROOT/${f#./}" "$PROJECT_ROOT/vendor/ppocr-sdk/${f#./}" 2>/dev/null || true
        done
        rm -rf "$BACKUP_ROOT"
    }
    trap 'restore_all' EXIT INT TERM

    for PATCH_FILE in "${PATCH_FILES[@]}"; do
        PATCH_NAME=$(basename "$PATCH_FILE")

        REL_PATHS=()
        while read -r path; do
            REL_PATHS+=("${path#vendor/ppocr-sdk/}")
        done < <(cd "$PROJECT_ROOT" && git apply --numstat "$PATCH_FILE" | awk '{print $3}')

        BACKUP_DIR="$BACKUP_ROOT"
        FETCH_OK=true
        for rel in "${REL_PATHS[@]}"; do
            mkdir -p "$BACKUP_DIR/$(dirname "$rel")"
            cp "$PROJECT_ROOT/vendor/ppocr-sdk/$rel" "$BACKUP_DIR/$rel"
            BLOB=$(git -C "$SUBMODULE_DIR" ls-tree "$LATEST_SHA" -- "$SUBTREE_PATH/$rel" 2>/dev/null | awk '{print $3}')
            if [ -n "$BLOB" ] && git -C "$SUBMODULE_DIR" cat-file -p "$BLOB" > "$PROJECT_ROOT/vendor/ppocr-sdk/$rel" 2>/dev/null; then
                :
            else
                FETCH_OK=false
            fi
        done

        if [ "$FETCH_OK" = true ]; then
            if (cd "$PROJECT_ROOT" && git apply --check "$PATCH_FILE" 2>/dev/null); then
                log_info "✅ $PATCH_NAME would still apply cleanly against upstream's latest."
            else
                log_warn "⚠️ $PATCH_NAME would CONFLICT against upstream's latest — manual merge needed."
            fi
        else
            log_warn "⚠️ Could not fetch one or more of $PATCH_NAME's files at $LATEST_SHORT to dry-run it."
        fi

        for rel in "${REL_PATHS[@]}"; do
            cp "$BACKUP_DIR/$rel" "$PROJECT_ROOT/vendor/ppocr-sdk/$rel"
        done
    done

    restore_all
    trap - EXIT INT TERM
    fi

    if [ "$REPORT" != true ]; then
    echo -e "\nThis is a ${YELLOW}vendored + patched${NC} module. To update:"
    echo "  1. cd vendor/paddleocr-upstream && git fetch --depth 1 origin $LATEST_SHA && git checkout $LATEST_SHA && cd -"
    echo "  2. git add vendor/paddleocr-upstream   # re-pin the submodule"
    echo "  3. Re-vendor vendor/ppocr-sdk/src/main from the submodule's deploy/ppocr-android/ppocr-sdk,"
    echo "     then re-apply every patch under vendor/ppocr-sdk/patches/ (git apply each, in name order)."
    echo "  4. If it conflicts, resolve by hand, then run ./scripts/vox patches regen ppocr-sdk"
    echo -e "  5. Rebuild + retest before committing.\n"
    fi
else
    emit has_update false
    log_info "✅ ppocr-sdk vendor is up to date ($CURRENT_SHORT)."
fi
