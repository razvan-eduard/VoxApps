#!/bin/bash
set -uo pipefail

# One command to ask "has anything upstream moved?" — the parent of the per-dependency
# check_*_version.sh scripts.
#
#     ./scripts/check_upstream.sh              # everything
#     ./scripts/check_upstream.sh vosk opencv  # just these
#
# Deliberately NOT wired into any build. Three of these checks used to run in `preBuild`, which
# meant every compile made network calls, behaved differently offline, and — in OpenWakeWord's case
# — ran a check that swaps upstream files into the working tree to dry-run a patch. Asking about
# upstream is a maintenance question; the answer belongs in a weekly PR (the sync-*.yml bots) or in
# this command when you want it, not attached to a compile.
#
# Each check script also answers in machine-readable form (`--report`), which is how the sync
# workflows consume the same detection rather than carrying their own copy of it.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; BLUE='\033[0;34m'; NC='\033[0m'

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT" || exit 1

# name : script : what a "version" means for it
CHECKS=(
    "vosk:check_vosk_version.sh:JitPack coordinate"
    "newpipe-extractor:check_newpipe_extractor_version.sh:JitPack coordinate"
    "openwakeword:check_openwakeword_version.sh:submodule tag (fork + 3 patches)"
    "opencv:check_opencv_version.sh:submodule tag (built from source)"
    "ppocr-sdk:check_ppocr_sdk_version.sh:submodule commit (fork + 4 patches)"
    "whisper:check_whisper_version.sh:submodule tag (built from source)"
    "llama:check_llama_version.sh:submodule tag (built from source)"
    "docquad:check_docquad_sdk_version.sh:vendored SDK"
)

# --report passes the named check's machine-readable output straight through, instead of printing
# the human table. That is what `vox check <name> --report` resolves to, and it keeps the
# name -> script mapping in this one file rather than duplicating it in the dispatcher.
REPORT=false
WANTED=()
for arg in "$@"; do
    case "$arg" in
        --report) REPORT=true ;;
        *) WANTED+=("$arg") ;;
    esac
done

if [ "$REPORT" = true ]; then
    [ ${#WANTED[@]} -gt 0 ] || { log_error "--report needs a name: e.g. vox check vosk --report"; exit 1; }
    for entry in "${CHECKS[@]}"; do
        IFS=':' read -r NAME SCRIPT _KIND <<< "$entry"
        for w in "${WANTED[@]}"; do
            [ "$w" = "$NAME" ] && exec bash "scripts/$SCRIPT" --report
        done
    done
    log_error "Unknown check: ${WANTED[*]}"
    exit 1
fi

printf "${BLUE}%s${NC}\n\n" "🔍 Checking every vendored/pinned upstream…"

UPDATES=()
FAILED=()

for entry in "${CHECKS[@]}"; do
    IFS=':' read -r NAME SCRIPT _KIND <<< "$entry"

    if [ ${#WANTED[@]} -gt 0 ]; then
        skip=true
        for w in "${WANTED[@]}"; do [ "$w" = "$NAME" ] && skip=false; done
        [ "$skip" = true ] && continue
    fi

    if [ ! -x "scripts/$SCRIPT" ] && [ ! -f "scripts/$SCRIPT" ]; then
        printf "  %-20s ${YELLOW}%s${NC}\n" "$NAME" "no check script"
        continue
    fi

    # stdout is the report; the script's human commentary goes to stderr and is hidden here.
    OUT=$(bash "scripts/$SCRIPT" --report 2>/dev/null) || { FAILED+=("$NAME"); }

    HAS=$(echo "$OUT" | grep -E '^has_update=' | cut -d= -f2)
    CUR=$(echo "$OUT" | grep -E '^current(_tag|_sha|_version)?=' | head -1 | cut -d= -f2)
    LAT=$(echo "$OUT" | grep -E '^latest(_tag|_sha|_version)?=' | head -1 | cut -d= -f2)

    case "$HAS" in
        true)
            printf "  %-20s ${YELLOW}%-14s${NC} %s → %s\n" "$NAME" "UPDATE" "${CUR:-?}" "${LAT:-?}"
            UPDATES+=("$NAME")
            ;;
        false)
            printf "  %-20s ${GREEN}%-14s${NC} %s\n" "$NAME" "up to date" "${CUR:-}"
            ;;
        *)
            printf "  %-20s ${RED}%-14s${NC} %s\n" "$NAME" "unknown" "(no has_update in report — network?)"
            FAILED+=("$NAME")
            ;;
    esac
done

echo
if [ ${#UPDATES[@]} -gt 0 ]; then
    printf "${YELLOW}%s${NC}\n" "⬆️  Updates available: ${UPDATES[*]}"
    echo "   The weekly sync-*.yml bots open a PR for these on their own schedule —"
    echo "   see docs/BUILD_TIME_DEPENDENCIES.md for which day each one runs."
else
    printf "${GREEN}%s${NC}\n" "✅ Everything is on its pinned version."
fi
[ ${#FAILED[@]} -gt 0 ] && printf "${RED}%s${NC}\n" "⚠️  Could not determine: ${FAILED[*]}"
exit 0
