#!/bin/bash

# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --report: key=value on stdout for sync-opencv.yml, human logging on stderr.
# shellcheck source=scripts/lib/upstream_report.sh
source "$(dirname "$0")/lib/upstream_report.sh"

# OpenCV was the one pinned upstream with no local check script — it could only be asked "has this
# moved?" by waiting for the weekly bot. That is also why the annotated-tag bug below survived three
# consecutive red runs: there was no way to reproduce it locally.
#
# vendor/opencv is a plain submodule pinned to a release tag and built from source by
# scripts/build_opencv_android.sh. Three of vendor/ppocr-sdk's four patches exist only to bridge our
# OpenCV major version, so a major bump here is not a routine update — sync-opencv.yml compiles
# :vendor:ppocr-sdk and :vox-vision after bumping precisely to catch that.

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM_URL="https://github.com/opencv/opencv.git"

CURRENT_SHA=$(git -C "$PROJECT_ROOT" ls-tree HEAD vendor/opencv | awk '{print $3}')

log_blue "🔍 Checking OpenCV version (submodule pin vs. upstream release tags)..."
log_info "Current pin: ${CURRENT_SHA:0:9}"

LATEST_TAG=$(git ls-remote --tags --refs "$UPSTREAM_URL" 2>/dev/null \
    | grep -oE "refs/tags/[0-9]+\.[0-9]+\.[0-9]+$" \
    | sed 's#refs/tags/##' \
    | sort -V | tail -1)

# Annotated tags (5.0.0 on opencv/opencv is one) have a tag-object SHA distinct from the commit the
# submodule actually pins. ls-remote's dereferenced "refs/tags/X^{}" entry gives the commit; query
# both refs and take the last line — for an annotated tag that is the peeled entry, for a lightweight
# one there is only a single line either way. Comparing the tag-object SHA against a commit SHA
# never matches, which left this check permanently reporting an update that did not exist.
if [ -n "$LATEST_TAG" ]; then
    LATEST_SHA=$(git ls-remote --tags "$UPSTREAM_URL" "refs/tags/$LATEST_TAG" "refs/tags/$LATEST_TAG^{}" 2>/dev/null \
        | tail -1 | awk '{print $1}')
fi

emit current_sha "$CURRENT_SHA"
emit latest_tag "${LATEST_TAG:-}"
emit latest_sha "${LATEST_SHA:-}"

if [ -z "${LATEST_TAG:-}" ] || [ -z "${LATEST_SHA:-}" ]; then
    emit has_update false
    log_warn "⚠️ Could not reach upstream (network?) — skipping version check."
    exit 0
fi

if [ "$CURRENT_SHA" != "$LATEST_SHA" ]; then
    emit has_update true
    log_warn "🚀 UPDATE AVAILABLE: ${CURRENT_SHA:0:9} -> $LATEST_TAG (${LATEST_SHA:0:9})"
    if [ "$REPORT" != true ]; then
        echo -e "\nOpenCV is ${YELLOW}built from source${NC}, not consumed as a binary. To update:"
        echo "  1. cd vendor/opencv && git fetch --tags --depth 1 origin tag $LATEST_TAG && git checkout $LATEST_TAG && cd -"
        echo "  2. git add vendor/opencv   # re-pin the submodule"
        echo "  3. ./gradlew :vendor:ppocr-sdk:compileDebugKotlin :vox-vision:compileDebugKotlin"
        echo "     (this rebuilds OpenCV from source — expect it to take a while)"
        echo "  4. A MAJOR bump will break vendor/ppocr-sdk's patches 0002 and 0004, which are"
        echo "     written against OpenCV 5's geometry API and libopencv_java5.so — expect to"
        echo -e "     rewrite them and run ./scripts/vox patches regen ppocr-sdk\n"
    fi
else
    emit has_update false
    log_info "✅ OpenCV is up to date ($LATEST_TAG)."
fi
