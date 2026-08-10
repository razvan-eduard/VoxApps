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

# --report: key=value on stdout for sync-whisper.yml, human logging on stderr.
source "$(dirname "$0")/lib/upstream_report.sh"

# The version question only. scripts/check_whisper.sh is a *build* script — it compiles the .so files
# and mentions a newer tag in passing — and sync-whisper.yml carried its own third copy of the same
# comparison. This is the one both can call.
#
# Resolves everything from the remote ref list rather than from inside the submodule, so it answers
# on a fresh clone where vox-commander/src/main/cpp/whisper.cpp has never been initialized, and
# without fetching tags into the submodule's ref store as a side effect.

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM_URL="https://github.com/ggerganov/whisper.cpp.git"
SUBMODULE_PATH="vox-commander/src/main/cpp/whisper.cpp"

CURRENT_SHA=$(git -C "$PROJECT_ROOT" ls-tree HEAD "$SUBMODULE_PATH" | awk '{print $3}')

log_blue "🔍 Checking whisper.cpp version (submodule pin vs. upstream stable tags)..."

# tag -> commit for every vX.Y.Z tag, peeling annotated tags to the commit the submodule can pin.
TAGS=$(git ls-remote --tags "$UPSTREAM_URL" 2>/dev/null | grep -E "refs/tags/v[0-9]+\.[0-9]+\.[0-9]+(\^\{\})?$")

if [ -z "$TAGS" ]; then
    emit current_sha "$CURRENT_SHA"
    emit has_update false
    log_warn "⚠️ Could not reach upstream (network?) — skipping version check."
    exit 0
fi

# Which tag is our pin? (empty when pinned to a commit that is not a release tag)
CURRENT_TAG=$(echo "$TAGS" | awk -v sha="$CURRENT_SHA" '$1 == sha {print $2}' \
    | sed 's#refs/tags/##; s#\^{}##' | sort -V | tail -1)

LATEST_TAG=$(echo "$TAGS" | sed 's#.*refs/tags/##; s#\^{}##' | sort -V | tail -1)

emit current_sha "$CURRENT_SHA"
emit current_tag "$CURRENT_TAG"
emit latest_tag "$LATEST_TAG"

if [ -n "$CURRENT_TAG" ]; then
    log_info "Current: $CURRENT_TAG (${CURRENT_SHA:0:9})"
else
    log_info "Current: ${CURRENT_SHA:0:9} (not an exact tag match)"
fi

if [ "$CURRENT_TAG" != "$LATEST_TAG" ]; then
    emit has_update true
    log_warn "🚀 UPDATE AVAILABLE: ${CURRENT_TAG:-${CURRENT_SHA:0:9}} -> $LATEST_TAG"
    if [ "$REPORT" != true ]; then
        echo -e "\nwhisper.cpp is ${YELLOW}built from source${NC} and its .so files ship as DLC. To update:"
        echo "  1. Let sync-whisper.yml open the PR (monthly), or bump the submodule by hand:"
        echo "     cd $SUBMODULE_PATH && git fetch --tags && git checkout $LATEST_TAG && cd -"
        echo "  2. ./gradlew :vox-commander:assembleDebug   # rebuilds the native libs"
        echo "  3. After merging, publish the new .so files as the DLC release:"
        echo -e "     ./scripts/publish_whisper_libs.sh   (never automatic — the APK downloads these)\n"
    fi
else
    emit has_update false
    log_info "✅ whisper.cpp is up to date ($LATEST_TAG)."
fi
