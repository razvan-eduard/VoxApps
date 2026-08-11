#!/bin/bash
set -uo pipefail

# Does the published Whisper runtime match the commit this source pins?
#
#     ./scripts/vox check whisper-published
#     ./scripts/vox check whisper-published --report    # key=value, for a workflow
#
# The Whisper libraries are not in the APK. AGP excludes them from every release build, and the app
# downloads them from the `whisper-libs` release at first launch. Exclusion is part of the build and
# always happens; publishing is scripts/publish_whisper_libs.sh, run by a person after checking on a
# device that transcription still sounds right — deliberately, because CI can prove "it compiles"
# and never "it still transcribes".
#
# The cost of that gate is a window where the pin has moved and the release has not, in which the APK
# is built against one whisper.cpp and every install runs another. Nothing in the build can see it:
# the release build compiles the libraries, packaging excludes them, and they are discarded.
#
# This compares the commit recorded by the last publish against the commit HEAD pins. It reads one
# small asset instead of rebuilding whisper, so it costs two API calls and can gate a release.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$VOX_ROOT" || exit 1

REPORT=false
[ "${1:-}" = "--report" ] && REPORT=true

SUBMODULE="vox-commander/src/main/cpp/whisper.cpp"
TAG="whisper-libs"
MARKER="built-from.txt"

PINNED=$(git rev-parse "HEAD:$SUBMODULE" 2>/dev/null)
if [ -z "$PINNED" ]; then
    $REPORT && echo "in_step=unknown"
    log_error "Cannot read the whisper.cpp submodule pin."
    exit 1
fi

URL=$(gh release view "$TAG" --json assets \
    --jq ".assets[] | select(.name == \"$MARKER\") | .url" 2>/dev/null || true)

# No marker: every publish before this check existed left none, so it cannot be told apart from a
# release that was never published. Reported as unknown rather than failed — refusing on it would
# block every release until someone republishes, which is a worse default than saying so.
if [ -z "$URL" ]; then
    if $REPORT; then
        echo "in_step=unknown"
        echo "pinned=$PINNED"
        echo "published="
    else
        log_warn "⚠️  The $TAG release records no $MARKER, so what it was built from is unknown."
        log_warn "   Run ./scripts/vox release publish-libs to publish and record it."
    fi
    exit 0
fi

PUBLISHED=$(gh release download "$TAG" --pattern "$MARKER" --output - 2>/dev/null | tr -d '[:space:]')

if $REPORT; then
    echo "pinned=$PINNED"
    echo "published=$PUBLISHED"
    [ "$PINNED" = "$PUBLISHED" ] && echo "in_step=true" || echo "in_step=false"
    exit 0
fi

if [ "$PINNED" = "$PUBLISHED" ]; then
    log_info "✅ The published Whisper runtime was built from the commit this source pins (${PINNED:0:12})."
    exit 0
fi

log_error "❌ The published Whisper runtime does not match this source."
log_error "   source pins : ${PINNED:0:12}"
log_error "   release has : ${PUBLISHED:0:12}"
log_error ""
log_error "   Every install downloads the release's build, so an APK released now would run a"
log_error "   different whisper.cpp than it was compiled against — and its SBOM would name the pin."
log_error "   Check transcription on a device, then: ./scripts/vox release publish-libs"
exit 1
