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

log_blue "── the dispatcher runs scripts with the shell they need ────"
# Every script here sources scripts/lib/common.sh through ${BASH_SOURCE[0]} and uses `source`, both
# of which dash lacks. macOS's /bin/sh is bash in POSIX mode and runs them anyway, so invoking one
# with `sh` works on a laptop and fails only on an Ubuntu runner — and only in the jobs that reach
# it, which is why a release found this and CI did not.
SH_INVOCATIONS=$(grep -nE '^\s*[a-z-]+\)\s*exec sh ' "$VOX_ROOT/scripts/vox" || true)
if [ -z "$SH_INVOCATIONS" ]; then
    ok "every dispatched script is run with bash"
else
    bad "the dispatcher runs a script with sh; on Ubuntu that is dash" "$(printf '%s' "$SH_INVOCATIONS" | tr '\n' ' ')"
fi

log_blue "── patches survive upstream drift ──────────────────────────"
# The precondition for a three-way apply: without index lines git cannot find the merge base, and
# silently falls back to the all-or-nothing apply this replaced. A regen written with plain `diff -u`
# would pass every other check here and quietly take that away.
NO_INDEX=""
for patch in core/wakeword/patches/*.patch vendor/ppocr-sdk/patches/*.patch; do
    [ -f "$patch" ] || continue
    diffs=$(grep -c '^diff --git' "$patch" || true)
    idx=$(grep -c '^index ' "$patch" || true)
    [ "$diffs" -eq "$idx" ] && [ "$idx" -gt 0 ] || NO_INDEX="$NO_INDEX $(basename "$patch")"
done
if [ -z "$NO_INDEX" ]; then
    ok "every stored patch carries the index lines a three-way apply needs"
else
    bad "patches without index lines — three-way silently degrades to all-or-nothing:$NO_INDEX"
fi

# And that it does what it is for: an upstream release touching a line *near* an adaptation must
# merge, where the old behaviour dropped the adaptation entirely.
# shellcheck source=scripts/lib/patches.sh
source "$VOX_ROOT/scripts/lib/patches.sh"
DRIFT=$(mktemp -d)
(
    cd "$DRIFT" || exit 1
    git init -q . && git config user.email t@t && git config user.name t
    printf 'alpha\nbeta\ngamma\ndelta\nepsilon\n' > pristine.txt
    printf 'alpha\nbeta\nOUR ADAPTATION\ndelta\nepsilon\n' > f.txt
    git add f.txt && git commit -qm vendored
    vox_patch_diff pristine.txt f.txt f.txt > p.patch
    # upstream moves a different line and we re-vendor: working tree and index become pristine+drift
    printf 'alpha\nbeta\ngamma\ndelta\nepsilon CHANGED UPSTREAM\n' > f.txt
    git add f.txt
    git apply --check p.patch 2>/dev/null && echo "PLAIN_APPLIED" || echo "PLAIN_REFUSED"
    vox_patch_apply_3way p.patch pristine.txt
    grep -q 'OUR ADAPTATION' f.txt && grep -q 'CHANGED UPSTREAM' f.txt && echo "BOTH_KEPT"
) > "$DRIFT/out.txt" 2>&1
if grep -q PLAIN_REFUSED "$DRIFT/out.txt" && grep -q '^clean$' "$DRIFT/out.txt" && grep -q BOTH_KEPT "$DRIFT/out.txt"; then
    ok "an upstream change near an adaptation merges (plain apply refuses it)"
else
    bad "three-way apply did not preserve the adaptation across upstream drift" \
        "$(tr '\n' ' ' < "$DRIFT/out.txt")"
fi
rm -rf "$DRIFT"

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

log_blue "── the published Whisper runtime ───────────────────────────"
#
# AGP excludes the Whisper libraries from every release build and the app downloads them from a
# release published by hand, so the pin can move without the runtime moving with it. The gate that
# notices is only useful while the release job still runs it.
if grep -q "check whisper-published" .github/workflows/release-commander.yml; then
    ok "the release gates on the published Whisper runtime matching the pin"
else
    bad "release-commander.yml no longer checks the published Whisper runtime"
fi

# --report is what a workflow would consume; it must answer, not exit silently.
out=$(./scripts/vox check whisper-published --report 2>/dev/null)
if printf '%s\n' "$out" | grep -qE '^published=(true|false)$'; then
    ok "whisper-published states published"
else
    bad "whisper-published --report gave no verdict" "$(printf '%s' "$out" | head -2)"
fi

# The tag the app asks for and the tag the publish script writes are both derived from the submodule
# pin. If they were derived differently the app would 404 on a release that exists, so both are
# checked against the pin rather than against each other.
pin=$(git rev-parse "HEAD:vox-commander/src/main/cpp/whisper.cpp" 2>/dev/null | cut -c1-12)
reported=$(printf '%s\n' "$out" | grep '^tag=' | cut -d= -f2)
if [ -n "$pin" ] && [ "$reported" = "whisper-libs-$pin" ]; then
    ok "the published runtime is addressed by the commit it was built from"
else
    bad "whisper-libs tag does not follow the pin" "pin=$pin reported=$reported"
fi

log_blue "── the sync workflows' dedup gate ──────────────────────────"
#
# `gh pr list --json ... -q '.[0] | "\(.number) \(.state)"'` renders an empty result as the literal
# string "null null", which is not empty — so a `[ -n "$EXISTING" ]` test reads "no PR exists" as
# "a PR exists" and the sync skips every single run. Nothing reports it: the job is green, every
# step after the gate is `skipped`, and the only symptom is upstream updates that never arrive.
# `select(.)` drops the null so an absent PR is an empty string.
for wf in .github/workflows/sync-*.yml; do
    name=$(basename "$wf" .yml)
    grep -q 'gh pr list --head' "$wf" || continue
    if grep -q "select(\.)" "$wf"; then
        ok "$name: dedup distinguishes no-PR from a PR"
    else
        bad "$name: dedup interpolates .[0] without select(.) — an absent PR reads as 'null null'" \
            "$(grep -n 'gh pr list --head' "$wf" | head -1)"
    fi

    # The gate has to stay overridable, or a target declined once can never be reconsidered.
    if grep -q 'inputs.force' "$wf"; then
        ok "$name: a declined target can be re-proposed with force"
    else
        bad "$name: dedup has no force override"
    fi
done

# A PR opened with GITHUB_TOKEN never triggers a workflow, so its required checks are absent rather
# than pending — and a merge blocks on absent exactly as it does on failing. The PR looks fine,
# reports nothing failing, and cannot be merged. Reads may keep GITHUB_TOKEN; opening the PR cannot.
for wf in .github/workflows/sync-*.yml; do
    name=$(basename "$wf" .yml)
    if awk '/- name:/{s=$0} /GH_TOKEN/{t=$0} /gh pr create/{print t; exit}' "$wf" \
         | grep -q 'secrets.GITHUB_TOKEN'; then
        bad "$name: opens its PR with GITHUB_TOKEN — required checks will never run on it"
    else
        ok "$name: opens its PR as an account, so CI runs on it"
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then
    log_info "✅ $PASS passed."
else
    log_error "❌ $FAIL failed, $PASS passed."
fi
exit $(( FAIL > 0 ? 1 : 0 ))
