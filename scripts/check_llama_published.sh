#!/bin/bash
set -uo pipefail

# Does the published llama runtime match the build this source pins?
#
#     ./scripts/vox check llama-published
#     ./scripts/vox check llama-published --report    # key=value, for a workflow
#
# libllama.so is not in the APK. AGP excludes it from every release build, and the app downloads
# it from the pinned `llama-libs-<pin12>` release on demand. Exclusion is part of the build
# and always happens; publishing is scripts/publish_llama_libs.sh, run by a person after checking
# on a device that intent parsing still works — deliberately, because CI can prove "it compiles"
# and never "it still answers".
#
# The release is named for the build fingerprint (scripts/llama_build_pin.sh: submodule + JNI
# bridge + CMake config), so this asks whether the runtime this build will ask for exists at
# all. One API call, so it can gate a release.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$VOX_ROOT" || exit 1

REPORT=false
[ "${1:-}" = "--report" ] && REPORT=true

SUBMODULE="vox-commander/src/main/cpp/llama.cpp"
LIBS=("libllama.so")

# Overridable so the suite can prove this gate is able to fail: a gate that has only ever passed
# is indistinguishable from one that cannot fail, so the tests point it at a pin with no release.
# The pin is the build fingerprint from scripts/llama_build_pin.sh — submodule + JNI bridge +
# CMake config — so a bridge-only change demands a fresh runtime release exactly like a
# submodule bump does; the script prints nothing on failure, so the empty-pin guard below fires.
PINNED="${VOX_LLAMA_PIN:-$("$PROJECT_ROOT/scripts/llama_build_pin.sh" 2>/dev/null)}"
if [ -z "$PINNED" ]; then
    $REPORT && echo "published=unknown"
    log_error "Cannot compute the llama build pin."
    exit 1
fi

# The tag names the build, so this asks whether the runtime this build expects exists at all —
# not whether some shared tag happens to hold the right bytes today.
TAG="llama-libs-${PINNED:0:12}"

# A draft satisfies `gh release view` for an authenticated caller, but its assets 404 for the
# anonymous client every install is — a draft is not published.
ASSETS=$(gh release view "$TAG" --json assets,isDraft \
    --jq 'select(.isDraft == false) | .assets[].name' 2>/dev/null || true)

MISSING=()
for lib in "${LIBS[@]}"; do
    printf '%s\n' "$ASSETS" | grep -qx "$lib" || MISSING+=("$lib")
done

if $REPORT; then
    echo "pinned=$PINNED"
    echo "tag=$TAG"
    [ ${#MISSING[@]} -eq 0 ] && echo "published=true" || echo "published=false"
    exit 0
fi

if [ ${#MISSING[@]} -eq 0 ]; then
    log_info "✅ $TAG holds the llama runtime this source pins."
    exit 0
fi

log_error "❌ No published llama runtime for the commit this source pins."
log_error "   pinned : ${PINNED:0:12}"
log_error "   tag    : $TAG"
log_error "   missing: ${MISSING[*]}"
log_error ""
log_error "   Installs of this build would ask that release for their native library and get a 404,"
log_error "   leaving the local LLM unable to load. Check intent parsing on a device, then:"
log_error "     ./scripts/vox release publish-llama-libs"
exit 1
