#!/bin/bash

# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --report: key=value on stdout for sync-llama.yml, human logging on stderr.
# shellcheck source=scripts/lib/upstream_report.sh
source "$(dirname "$0")/lib/upstream_report.sh"

# The version question only — same split as check_whisper_version.sh: scripts/check_llama.sh is
# the *build* script, this is the comparison both the workflow and a human can call.
#
# Resolves everything from the remote ref list rather than from inside the submodule, so it
# answers on a fresh clone where the submodule has never been initialized, and without fetching
# tags into the submodule's ref store as a side effect.

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM_URL="https://github.com/ggml-org/llama.cpp.git"
SUBMODULE_PATH="vox-commander/src/main/cpp/llama.cpp"

CURRENT_SHA=$(git -C "$PROJECT_ROOT" ls-tree HEAD "$SUBMODULE_PATH" | awk '{print $3}')

log_blue "🔍 Checking llama.cpp version (submodule pin vs. upstream release tags)..."

# tag -> commit for every b<number> release tag, peeling annotated tags to the commit the
# submodule can pin. llama.cpp releases are build-number tags, not semver.
TAGS=$(git ls-remote --tags "$UPSTREAM_URL" 2>/dev/null | grep -E "refs/tags/b[0-9]+(\^\{\})?$")

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
        echo -e "\nllama.cpp is ${YELLOW}built from source${NC} and libllama.so ships as a per-commit release asset. To update:"
        echo "  1. Let sync-llama.yml open the PR (monthly), or bump the submodule by hand:"
        echo "     cd $SUBMODULE_PATH && git fetch --tags && git checkout $LATEST_TAG && cd -"
        echo "  2. ./gradlew :vox-commander:assembleDebug   # rebuilds libllama.so"
        echo "  3. After merging, publish the new .so as the per-commit release:"
        echo -e "     ./scripts/vox release publish-llama-libs   (never automatic — the APK downloads it)\n"
    fi
else
    emit has_update false
    log_info "✅ llama.cpp is up to date ($LATEST_TAG)."
fi
