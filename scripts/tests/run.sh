#!/bin/bash
# Tests for the automation itself.
#
# The Kotlin has hundreds of tests; the shell and workflows that gate every release had none — and
# the three regressions introduced while building this machinery were each found by dispatching a
# real bot and reading the failure, i.e. by using production as the test environment.
#
# What is asserted here is the set of contracts that, when broken, break a bot silently:
#   - every check answers in machine-readable form, and only that, on stdout
#   - every check always states has_update, including when the network or a submodule is missing
#   - the dispatcher routes to the right script and refuses nonsense
#   - a vendored fork equals upstream + patches, and the check FAILS when it does not
#
# Deliberately network-tolerant: a check that cannot reach upstream must still emit
# has_update=false rather than nothing, so these pass offline. That is itself one of the contracts.
#
#     ./scripts/vox test
set -uo pipefail

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"
cd "$VOX_ROOT" || exit 1

PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); printf "  ${GREEN}✓${NC} %s\n" "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf "  ${RED}✗${NC} %s\n" "$1"; [ -n "${2:-}" ] && printf "      %s\n" "$2"; }

CHECKS=(vosk newpipe-extractor openwakeword opencv ppocr-sdk whisper docquad)

log_blue "── report contract ─────────────────────────────────────────"
for name in "${CHECKS[@]}"; do
    out=$(./scripts/vox check "$name" --report 2>/dev/null)

    # stdout carries key=value and nothing else. A banner or a stray echo here goes straight into
    # $GITHUB_OUTPUT and fails the step with "Invalid format" — which is exactly what happened.
    strays=$(printf '%s\n' "$out" | grep -vcE '^[a-z_]+=' || true)
    if [ "$strays" -eq 0 ] && [ -n "$out" ]; then
        ok "$name: stdout is key=value only"
    else
        bad "$name: stdout has $strays non key=value line(s)" "$(printf '%s' "$out" | head -2)"
    fi

    # An answer is always required. A silent exit reads as "unknown" to the parent and as an empty
    # output to a workflow, which is how a bot retires without anyone noticing.
    if printf '%s\n' "$out" | grep -qE '^has_update=(true|false)$'; then
        ok "$name: states has_update"
    else
        bad "$name: no has_update in report"
    fi
done

log_blue "── dispatcher ──────────────────────────────────────────────"
expect_ok()   { if "$@" >/dev/null 2>&1; then ok "$DESC"; else bad "$DESC"; fi; }
expect_fail() { if "$@" >/dev/null 2>&1; then bad "$DESC"; else ok "$DESC"; fi; }

DESC="bare invocation prints usage, exit 0";   expect_ok   ./scripts/vox
DESC="unknown flow rejected";                  expect_fail ./scripts/vox nonsense
DESC="incomplete command rejected";            expect_fail ./scripts/vox patches
DESC="unknown check name rejected";            expect_fail ./scripts/vox check nonsense --report

log_blue "── vendored invariant ──────────────────────────────────────"
if ./scripts/vox patches verify >/dev/null 2>&1; then
    ok "every fork is upstream + patches"
else
    bad "a vendored fork differs from upstream + patches"
fi

# The check must FAIL on an unrecorded edit — a verifier that only ever passes proves nothing. This
# is the failure that shipped three adaptations nobody had captured as patches.
CANARY="core/wakeword/src/main/kotlin/com/rementia/openwakeword/lib/ml/MelSpectrogram.kt"
if [ -f "$CANARY" ]; then
    cp "$CANARY" "$CANARY.testbak"
    printf '\n// unrecorded edit — the invariant must notice this\n' >> "$CANARY"
    if ./scripts/vox patches verify wakeword >/dev/null 2>&1; then
        bad "verify PASSED with an unrecorded edit present (it proves nothing)"
    else
        ok "verify fails on an unrecorded edit"
    fi
    mv "$CANARY.testbak" "$CANARY"
    if [ -z "$(git status --porcelain "$CANARY")" ]; then
        ok "canary restored, tree clean"
    else
        bad "canary NOT restored — working tree is dirty"
    fi
fi

log_blue "── schema signing ──────────────────────────────────────────"
if [ -f remote-schemas/manifest.json ] && [ -f remote-schemas/manifest.json.sig ]; then
    if ./scripts/vox schemas verify >/dev/null 2>&1; then
        ok "manifest signature and every schema hash verify"
    else
        bad "schemas do not match the signed manifest — run: ./scripts/vox schemas sign"
    fi

    # A verifier that only ever passes proves nothing: the apps refuse a schema the manifest does
    # not cover, so this check has to notice an edited one.
    VICTIM="remote-schemas/commander/normalization.json"
    cp "$VICTIM" "$VICTIM.testbak"
    printf ' ' >> "$VICTIM"
    if ./scripts/vox schemas verify >/dev/null 2>&1; then
        bad "verify PASSED with an edited schema (it proves nothing)"
    else
        ok "verify fails on an edited schema"
    fi
    mv "$VICTIM.testbak" "$VICTIM"
else
    bad "no signed manifest — the apps will refuse every schema change from this repo"
fi

log_blue "── the published schemas repository ────────────────────────"
# schemas-repo/ is the template; razvan-eduard/VoxApps-schemas is what people fork. The mirror there
# syncs remote-schemas and validate_schemas.py — never its own workflow — so a change made here to
# .github/workflows/ never reaches it, and the two drift with nothing to say so. The live copy named
# the repository by its former name for two days that way, which resolves only through GitHub's
# rename redirect: claim that name and the fork-ready repository starts mirroring somebody else's
# schemas.
#
# Network-tolerant like the checks above: no gh, no auth, no network — no verdict, not a failure.
MIRROR_TEMPLATE="schemas-repo/.github/workflows/mirror.yml"
if [ ! -f "$MIRROR_TEMPLATE" ]; then
    bad "no $MIRROR_TEMPLATE — the fork-ready repository has no mirror to compare against"
elif ! command -v gh >/dev/null 2>&1 || ! gh auth status >/dev/null 2>&1; then
    ok "live mirror workflow: skipped (no authenticated gh)"
else
    # To a file, not a variable: command substitution strips trailing newlines, which shows up as a
    # drift the file does not have.
    LIVE_COPY="$(mktemp)"
    gh api repos/razvan-eduard/VoxApps-schemas/contents/.github/workflows/mirror.yml \
        -q '.content' 2>/dev/null | base64 -d > "$LIVE_COPY" 2>/dev/null
    if [ ! -s "$LIVE_COPY" ]; then
        ok "live mirror workflow: skipped (unreachable)"
    elif diff -q "$LIVE_COPY" "$MIRROR_TEMPLATE" >/dev/null 2>&1; then
        ok "live mirror workflow matches this template"
    else
        bad "razvan-eduard/VoxApps-schemas' mirror.yml has drifted from $MIRROR_TEMPLATE" \
            "$(diff "$LIVE_COPY" "$MIRROR_TEMPLATE" | head -6 | tr '\n' ' ')"
    fi
    rm -f "$LIVE_COPY"
fi

echo
if [ "$FAIL" -eq 0 ]; then
    log_info "✅ $PASS passed."
else
    log_error "❌ $FAIL failed, $PASS passed."
fi
exit $(( FAIL > 0 ? 1 : 0 ))
