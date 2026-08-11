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
# The release is named for the whisper.cpp commit, so this asks whether the runtime this build will
# ask for exists at all. One API call, so it can gate a release.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$VOX_ROOT" || exit 1

REPORT=false
[ "${1:-}" = "--report" ] && REPORT=true

SUBMODULE="vox-commander/src/main/cpp/whisper.cpp"
LIBS=("libomp.so" "libwhisper.so")

# Overridable so the suite can prove this gate is able to fail: a gate that has only ever passed
# is indistinguishable from one that cannot fail, so the tests point it at a pin with no release.
PINNED="${VOX_WHISPER_PIN:-$(git rev-parse "HEAD:$SUBMODULE" 2>/dev/null)}"
if [ -z "$PINNED" ]; then
    $REPORT && echo "published=unknown"
    log_error "Cannot read the whisper.cpp submodule pin."
    exit 1
fi

# The tag names the commit, so this asks whether the runtime this build expects exists at all —
# not whether some shared tag happens to hold the right bytes today.
TAG="whisper-libs-${PINNED:0:12}"

# A draft satisfies `gh release view` for an authenticated caller, but its assets 404 for the
# anonymous client every install is — a draft is not published.
ASSETS=$(gh release view "$TAG" --json assets,isDraft \
    --jq 'select(.isDraft == false) | .assets[].name' 2>/dev/null || true)

MISSING=()
for lib in "${LIBS[@]}"; do
    printf '%s\n' "$ASSETS" | grep -qx "$lib" || MISSING+=("$lib")
done

# ggml is linked statically into libwhisper.so; separate libggml*.so assets are leftovers from an
# earlier library layout, kept for installs that still ask for them. Named so nobody mistakes
# their presence for the current design.
STALE=$(printf '%s\n' "$ASSETS" | grep '^libggml' | paste -sd, - || true)

if $REPORT; then
    echo "pinned=$PINNED"
    echo "tag=$TAG"
    echo "stale_assets=$STALE"
    [ ${#MISSING[@]} -eq 0 ] && echo "published=true" || echo "published=false"
    exit 0
fi

if [ ${#MISSING[@]} -eq 0 ]; then
    log_info "✅ $TAG holds the Whisper runtime this source pins."
    [ -n "$STALE" ] && log_info "   (superseded assets still on the release: $STALE)"
    exit 0
fi

log_error "❌ No published Whisper runtime for the commit this source pins."
log_error "   pinned : ${PINNED:0:12}"
log_error "   tag    : $TAG"
log_error "   missing: ${MISSING[*]}"
log_error ""
log_error "   Installs of this build would ask that release for their native libraries and get a 404,"
log_error "   leaving Whisper unable to load. Check transcription on a device, then:"
log_error "     ./scripts/vox release publish-libs"
exit 1
