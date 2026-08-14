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

CHECKS=(vosk newpipe-extractor openwakeword opencv ppocr-sdk whisper llama docquad)

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

# A gate that has only ever passed is indistinguishable from one that cannot fail, so both
# directions are exercised: the real pin must pass, and a pin nothing was published for must fail.
# --report keeps exit 0 by contract, which is why the gating form is what is tested here.
if ! command -v gh >/dev/null 2>&1 || ! gh auth status >/dev/null 2>&1; then
    ok "whisper-published gate both ways: skipped (no authenticated gh)"
else
    if ./scripts/vox check whisper-published >/dev/null 2>&1; then
        ok "the gate passes on the real pin"
    else
        bad "the gate fails on the real pin — is the runtime published?"
    fi
    FAKE_PIN="0000000000000000000000000000000000000000"
    if VOX_WHISPER_PIN=$FAKE_PIN ./scripts/vox check whisper-published >/dev/null 2>&1; then
        bad "the gate PASSED for a pin with no release (it proves nothing)"
    else
        ok "the gate fails for a pin with no release"
    fi
    rep=$(VOX_WHISPER_PIN=$FAKE_PIN ./scripts/vox check whisper-published --report 2>/dev/null)
    if printf '%s\n' "$rep" | grep -qx 'published=false'; then
        ok "and its report says published=false for that pin"
    else
        bad "the report did not say published=false for a pin with no release" \
            "$(printf '%s' "$rep" | head -3 | tr '\n' ' ')"
    fi
fi

log_blue "── the published llama runtime ─────────────────────────────"
#
# Same shape as the Whisper gate above: AGP excludes libllama.so from every release build and the
# app downloads it from a per-commit release published by hand, so the pin can move without the
# runtime moving with it.
if grep -q "check llama-published" .github/workflows/release-commander.yml; then
    ok "the release gates on the published llama runtime matching the pin"
else
    bad "release-commander.yml no longer checks the published llama runtime"
fi

out=$(./scripts/vox check llama-published --report 2>/dev/null)
if printf '%s\n' "$out" | grep -qE '^published=(true|false)$'; then
    ok "llama-published states published"
else
    bad "llama-published --report gave no verdict" "$(printf '%s' "$out" | head -2)"
fi

pin=$(./scripts/llama_build_pin.sh 2>/dev/null | cut -c1-12)
reported=$(printf '%s\n' "$out" | grep '^tag=' | cut -d= -f2)
if [ -n "$pin" ] && [ "$reported" = "llama-libs-$pin" ]; then
    ok "the published llama runtime is addressed by the build it was compiled from"
else
    bad "llama-libs tag does not follow the build pin" "pin=$pin reported=$reported"
fi

if ! command -v gh >/dev/null 2>&1 || ! gh auth status >/dev/null 2>&1; then
    ok "llama-published gate both ways: skipped (no authenticated gh)"
else
    if ./scripts/vox check llama-published >/dev/null 2>&1; then
        ok "the llama gate passes on the real pin"
    else
        bad "the llama gate fails on the real pin — is the runtime published?"
    fi
    FAKE_PIN="0000000000000000000000000000000000000000"
    if VOX_LLAMA_PIN=$FAKE_PIN ./scripts/vox check llama-published >/dev/null 2>&1; then
        bad "the llama gate PASSED for a pin with no release (it proves nothing)"
    else
        ok "the llama gate fails for a pin with no release"
    fi
    rep=$(VOX_LLAMA_PIN=$FAKE_PIN ./scripts/vox check llama-published --report 2>/dev/null)
    if printf '%s\n' "$rep" | grep -qx 'published=false'; then
        ok "and its report says published=false for that pin"
    else
        bad "the llama report did not say published=false for a pin with no release" \
            "$(printf '%s' "$rep" | head -3 | tr '\n' ' ')"
    fi
fi

log_blue "── the sync workflows' dedup gate ──────────────────────────"
#
# `gh pr list --json ... -q '.[0] | "\(.number) \(.state)"'` renders an empty result as the literal
# string "null null", which is not empty — so a `[ -n "$EXISTING" ]` test reads "no PR exists" as
# "a PR exists" and the sync skips every single run. Nothing reports it: the job is green, every
# step after the gate is `skipped`, and the only symptom is upstream updates that never arrive.
# `select(.)` drops the null so an absent PR is an empty string.
#
# A workflow with no gate at all is not exempt — it re-proposes a declined bump every cron,
# forever. Skipping it here would also mean losing the gate loses the test.
SYNC_WF_COUNT=0
for wf in .github/workflows/sync-*.yml; do
    name=$(basename "$wf" .yml)
    SYNC_WF_COUNT=$((SYNC_WF_COUNT + 1))
    if ! grep -q 'gh pr list --head' "$wf"; then
        bad "$name: no dedup gate at all — a declined bump is re-proposed every cron"
        continue
    fi
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
#
# Asserted on behaviour, not spelling: `${{ secrets.X || github.token }}` never contains the string
# `secrets.GITHUB_TOKEN`, yet resolves to exactly that token when the secret is unset — so any
# mention of github.token in the PR-opening env is a failure, and the PAT must be named.
for wf in .github/workflows/sync-*.yml; do
    name=$(basename "$wf" .yml)
    token_line=$(awk '/GH_TOKEN/{t=$0} /gh pr create/{print t; exit}' "$wf")
    if printf '%s' "$token_line" | grep -qE 'github\.token|GITHUB_TOKEN'; then
        bad "$name: the PR-opening token can resolve to GITHUB_TOKEN — required checks would never run" \
            "$token_line"
    elif printf '%s' "$token_line" | grep -q 'README_PUSH_TOKEN'; then
        ok "$name: opens its PR with the PAT, so CI runs on it"
    else
        bad "$name: no PR-opening token found" "$token_line"
    fi
done

if [ "$SYNC_WF_COUNT" -eq 7 ]; then
    ok "the dedup and token loops saw all 7 sync workflows"
else
    bad "the sync loops saw $SYNC_WF_COUNT workflows, expected 7 — coverage shrank silently"
fi

log_blue "── the release workflows do the same things ────────────────"
# Six workflows publish with the same stakes, and each gate exists only in the file it was pasted
# into. A gate present in five of six is invisible from inside any one of them: the sixth simply
# publishes green without it. Parity is asserted per file, with the missing pieces named, and the
# loop counts its files so a renamed glob cannot quietly assert nothing.
RELEASE_WF_COUNT=0
for wf in .github/workflows/release-*.yml; do
    name=$(basename "$wf" .yml)
    RELEASE_WF_COUNT=$((RELEASE_WF_COUNT + 1))
    missing=""
    grep -q 'apksigner" verify' "$wf" || missing="$missing apksigner-verify"
    grep -q 'prerelease:' "$wf" || missing="$missing prerelease-flag"
    grep -q 'check pairing' "$wf" || missing="$missing native-pairing"
    grep -q 'release sbom' "$wf" || missing="$missing sbom"
    grep -q 'attest-build-provenance' "$wf" || missing="$missing attestation"
    grep -q 'check smoke' "$wf" || missing="$missing smoke-gate"
    # A test step that swallows its own failure is a gate in name only.
    if grep -E 'gradlew.*[tT]est' "$wf" | grep -qE '\|\| (echo|true)'; then
        missing="$missing softened-tests"
    fi
    if [ -z "$missing" ]; then
        ok "$name: every release gate present"
    else
        bad "$name: missing$missing"
    fi
done
if [ "$RELEASE_WF_COUNT" -eq 6 ]; then
    ok "the parity loop saw all 6 release workflows"
else
    bad "the parity loop saw $RELEASE_WF_COUNT release workflows, expected 6"
fi

# The emulator action runs each line of its `script:` input as a separate `sh -c` — a multi-line
# script there is N independent commands, so an `if` block is a syntax error and a line
# continuation a stray argument, discovered only when the workflow actually runs. Logic belongs
# in scripts/; the action's input names one command.
for wf in .github/workflows/*.yml; do
    grep -q 'android-emulator-runner' "$wf" || continue
    name=$(basename "$wf" .yml)
    if grep -qE '^[[:space:]]*script:[[:space:]]*[|>]' "$wf"; then
        bad "$name: multi-line script in the emulator action — each line runs as its own sh -c"
    else
        ok "$name: the emulator action gets a single-line script"
    fi
done

# A published tag is an address installed APKs still resolve — the DLC downloader builds its URLs
# from the version it was compiled with. The guard that refuses to repoint one must exist in every
# release workflow, or a build at an unbumped version silently replaces the assets behind every
# installed APK's download URL.
guarded=$(grep -l 'Refuse to move a published tag' .github/workflows/release-*.yml | wc -l | tr -d ' ')
if [ "$guarded" -eq 6 ]; then
    ok "every release workflow refuses to move a published tag"
else
    bad "only $guarded of 6 release workflows carry the republish guard"
fi

log_blue "── F-Droid deploys are wired to real workflow names ────────"
# workflow_run triggers match on the display name. Renaming a release workflow silently detaches
# its F-Droid deploy: GitHub does not warn about a trigger that matches nothing — the deploy just
# stops firing for that app.
DEPLOY=.github/workflows/deploy-fdroid.yml
TRIGGERS=$(awk '/workflows:/{f=1;next} f && /^[[:space:]]*-/{line=$0; sub(/^[^-]*- */,"",line); gsub(/"/,"",line); print line; next} f{exit}' "$DEPLOY")
TRIGGER_COUNT=0
UNMATCHED=""
while IFS= read -r wfname; do
    [ -n "$wfname" ] || continue
    TRIGGER_COUNT=$((TRIGGER_COUNT + 1))
    hits=$(grep -l "^name: $wfname\$" .github/workflows/release-*.yml 2>/dev/null | wc -l | tr -d ' ')
    [ "$hits" -eq 1 ] || UNMATCHED="$UNMATCHED '$wfname'"
done <<< "$TRIGGERS"
if [ -z "$UNMATCHED" ] && [ "$TRIGGER_COUNT" -eq "$RELEASE_WF_COUNT" ]; then
    ok "all $TRIGGER_COUNT deploy triggers name an existing release workflow, one each"
else
    bad "deploy-fdroid trigger drift ($TRIGGER_COUNT triggers, $RELEASE_WF_COUNT release workflows)" \
        "unmatched:$UNMATCHED"
fi

log_blue "── writer and reader agree on the library lists ────────────"
# Each list exists in more than one place: what the build excludes, what the app loads, what the
# publish script uploads, what the release gate looks for. Nothing but these assertions ties the
# copies together — the 0.20 incident was exactly a writer and its readers disagreeing.
# The end marker is a line that is only `)` or `),` — a `)` inside a comment must not close the
# range early, and the quoted-name grep keeps commented-out entries and prose out of the list.
so_list() {
    sed -n "/$2/,/^[[:space:]]*),\{0,1\}[[:space:]]*$/p" "$1" \
        | grep -oE '"lib[A-Za-z0-9._-]+\.so"' | tr -d '"' | sort
}

WEM="vox-commander/src/main/java/com/voxapps/commander/data/remote/WhisperEngineManager.kt"
W_KT=$(so_list "$WEM" 'WHISPER_LIBS = listOf(')
W_GRADLE=$(so_list vox-commander/build.gradle.kts 'val whisperLibs = listOf(')
W_PUB=$(grep -m1 '^LIBS=' scripts/publish_whisper_libs.sh | grep -oE '"[^"]+\.so"' | tr -d '"' | sort)
W_CHECK=$(grep -m1 '^LIBS=' scripts/check_whisper_published.sh | grep -oE '"[^"]+\.so"' | tr -d '"' | sort)
w_count=$(printf '%s\n' "$W_KT" | grep -c . || true)
if [ "$w_count" -eq 2 ] && [ "$W_KT" = "$W_GRADLE" ] && [ "$W_KT" = "$W_PUB" ] && [ "$W_KT" = "$W_CHECK" ]; then
    ok "the whisper lib list agrees across its four copies (2 libs)"
else
    bad "whisper lib list drift across engine/build/publish/gate" \
        "kt=[${W_KT//$'\n'/ }] gradle=[${W_GRADLE//$'\n'/ }] publish=[${W_PUB//$'\n'/ }] gate=[${W_CHECK//$'\n'/ }]"
fi

# In the Kotlin copy order is load semantics: the list is walked into System.load() calls and
# libwhisper.so NEEDs libomp.so, so libomp must come first. The other copies only name files.
first=$(sed -n '/WHISPER_LIBS = listOf(/,/)/p' "$WEM" | grep -oE 'lib(omp|whisper)\.so' | head -1)
if [ "$first" = "libomp.so" ]; then
    ok "the engine loads libomp before libwhisper"
else
    bad "WHISPER_LIBS no longer loads libomp first — libwhisper cannot resolve its dependency"
fi

LEM="vox-commander/src/main/java/com/voxapps/commander/data/remote/LlamaEngineManager.kt"
L_KT=$(so_list "$LEM" 'LLAMA_LIBS = listOf(')
L_GRADLE=$(so_list vox-commander/build.gradle.kts 'val llamaLibs = listOf(')
L_PUB=$(grep -m1 '^LIBS=' scripts/publish_llama_libs.sh | grep -oE '"[^"]+\.so"' | tr -d '"' | sort)
L_CHECK=$(grep -m1 '^LIBS=' scripts/check_llama_published.sh | grep -oE '"[^"]+\.so"' | tr -d '"' | sort)
l_count=$(printf '%s\n' "$L_KT" | grep -c . || true)
if [ "$l_count" -eq 1 ] && [ "$L_KT" = "$L_GRADLE" ] && [ "$L_KT" = "$L_PUB" ] && [ "$L_KT" = "$L_CHECK" ]; then
    ok "the llama lib list agrees across its four copies (1 lib)"
else
    bad "llama lib list drift across engine/build/publish/gate" \
        "kt=[${L_KT//$'\n'/ }] gradle=[${L_GRADLE//$'\n'/ }] publish=[${L_PUB//$'\n'/ }] gate=[${L_CHECK//$'\n'/ }]"
fi

C_GRADLE=$(so_list vox-commander/build.gradle.kts 'val dlcLibs = listOf(')
C_KT=$(so_list vox-commander/src/main/java/com/voxapps/commander/data/remote/NativeLibManager.kt 'libs = listOf(')
c_count=$(printf '%s\n' "$C_KT" | grep -c . || true)
if [ "$c_count" -eq 3 ] && [ "$C_GRADLE" = "$C_KT" ]; then
    ok "commander's DLC list agrees between build and loader (3 libs)"
else
    bad "commander dlcLibs and NativeLibManager.libs disagree" \
        "gradle=[${C_GRADLE//$'\n'/ }] kt=[${C_KT//$'\n'/ }]"
fi

V_GRADLE=$(so_list vox-vision/build.gradle.kts 'val dlcLibs = listOf(')
V_KT=$(so_list vox-vision/src/main/java/com/voxapps/vision/data/NativeLibManager.kt 'libs = listOf(')
v_count=$(printf '%s\n' "$V_KT" | grep -c . || true)
if [ "$v_count" -eq 10 ] && [ "$V_GRADLE" = "$V_KT" ]; then
    ok "vision's DLC list agrees between build and loader (10 libs)"
else
    bad "vision dlcLibs and NativeLibManager.libs disagree" \
        "gradle=[${V_GRADLE//$'\n'/ }] kt=[${V_KT//$'\n'/ }]"
fi

log_blue "── every downloadable model declares its hash ──────────────"
# The runtime check refuses a mismatch only where the schema declares a sha256; an entry without
# one downloads unverified, with nothing but a log line saying so. The field is what extends the
# schema signature from "this URL is the maintainer's" to "these bytes are what must arrive there"
# — so a new model landing without it silently reopens the gap for exactly that model. Asserted
# over every registry with downloadable entries, with a floor on the count so an emptied or
# unparseable registry cannot read as fully covered.
MODEL_HASH_REPORT=$(python3 - <<'PY'
import json
missing, total = [], 0

doc = json.load(open("remote-schemas/commander/models.json"))
for engine_key, engine in (doc.get("engines") or {}).items():
    for model in (engine.get("models") or []):
        if (model.get("path") or "").startswith("http"):
            total += 1
            if len(model.get("sha256") or "") != 64:
                missing.append(f"{engine_key}/{model.get('id', '?')}")

ocr = json.load(open("vox-vision/src/main/assets/ocr_models.json"))
for name, entry in ocr.items():
    if isinstance(entry, dict) and (entry.get("url") or "").startswith("http"):
        total += 1
        if len(entry.get("sha256") or "") != 64:
            missing.append(f"ocr/{name}")

print(total)
print(" ".join(missing))
PY
)
MODEL_TOTAL=$(printf '%s\n' "$MODEL_HASH_REPORT" | sed -n 1p)
MODEL_MISSING=$(printf '%s\n' "$MODEL_HASH_REPORT" | sed -n 2p)
if [ -z "$MODEL_MISSING" ] && [ "${MODEL_TOTAL:-0}" -eq 105 ]; then
    ok "all $MODEL_TOTAL downloadable models declare a sha256"
else
    bad "downloadable models without a sha256 (of ${MODEL_TOTAL:-?}) — their downloads are unverified" \
        "$MODEL_MISSING"
fi

log_blue "── the LLM runtime ships inside the default APK ───────────"
# The local LLM is the one engine an install cannot do without, so the default (and F-Droid)
# build compiles it in: no first-run download to fail, and nothing executed that this build did
# not produce. The lean `full` variant still strips it out to a release asset. Asserted as
# placement, not prose: the llama exclusion must live inside the full-mode branch, never beside
# whisper's unconditional one.
llama_excl_line=$(grep -n 'jniLibs.excludes.addAll(llamaLibs' vox-commander/build.gradle.kts | cut -d: -f1)
full_branch_line=$(grep -n 'if (dlcMode == "full")' vox-commander/build.gradle.kts | head -1 | cut -d: -f1)
if [ -n "$llama_excl_line" ] && [ -n "$full_branch_line" ] && [ "$llama_excl_line" -gt "$full_branch_line" ]; then
    ok "libllama.so is excluded only by the lean (full) variant"
else
    bad "the llama exclusion is not inside the full-mode branch — the default APK may ship without its LLM runtime" \
        "exclude=$llama_excl_line full-branch=$full_branch_line"
fi

log_blue "── both engines are hybrid CPU+OpenCL builds ──────────"
# The GPU backend is forced flags in the pinned CMakeLists files (the shell scripts are outside
# the build fingerprints, so a flag living only there would move the bytes without moving the
# tag). OpenCL, never Vulkan: the Adreno Vulkan driver aborts creating compute pipelines at
# backend registration — before any toggle or probe can intervene — which is why the Vulkan
# backend was reverted. Vulkan reappearing in either build config is that crash shipping again.
for cmake in vox-commander/src/main/cpp/llama-build/CMakeLists.txt vox-commander/src/main/cpp/CMakeLists.txt; do
    if grep -qE '^set\(GGML_OPENCL ON CACHE' "$cmake" \
        && grep -qE '^set\(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE' "$cmake" \
        && ! grep -qE '^set\(GGML_VULKAN ON CACHE' "$cmake"; then
        ok "$(basename "$(dirname "$cmake")")/CMakeLists.txt forces OpenCL+Adreno, Vulkan stays off"
    else
        bad "$cmake does not force GGML_OPENCL(+ADRENO_KERNELS) with Vulkan off — the working GPU backend is not compiled in, or the Adreno-fatal one is back"
    fi
done

log_blue "── the WHISPER_VULKAN pseudo-engine stays retired ──────────"
# GPU is a per-engine boolean now, never a fake processor key. The only survivors are the one-shot
# migration that rewrites the retired stored value and the backup-import remap — both name it as a
# string literal on purpose. Any reappearance in engine wiring or the UI is the anti-pattern
# growing back.
stray=$(grep -rn 'WHISPER_VULKAN' vox-commander/src/main --include='*.kt' \
    | grep -v 'migrateWhisperVulkanRetirement' \
    | grep -v 'normalizeEngineKey' \
    | grep -vE '"WHISPER_VULKAN"' \
    | grep -vE ':[[:space:]]*(\*|//)' || true)
if [ -z "$stray" ]; then
    ok "no WHISPER_VULKAN reference outside the migration and the key remap"
else
    bad "WHISPER_VULKAN is referenced as live engine wiring again" "$(printf '%s' "$stray" | head -3)"
fi

log_blue "── report contract without CI's environment ────────────────"
# CI exports GH_TOKEN and friends, so a broken \${VAR:-\$(fallback)} in a check script fails only
# on a developer machine — the environment masks the defect exactly where the report gates a bot.
# The contract is therefore pinned on the no-env path explicitly.
for name in ppocr-sdk whisper llama; do
    out=$(env -u GH_TOKEN -u GITHUB_TOKEN -u GITHUB_REPOSITORY \
              -u VULKAN_HEADERS_BASE -u SPIRV_HEADERS_BASE -u SHADERC_BASE \
              ./scripts/vox check "$name" --report 2>/dev/null)
    if printf '%s\n' "$out" | grep -qE '^has_update=(true|false)$'; then
        ok "$name: report contract holds with no ambient environment"
    else
        bad "$name: report breaks when CI's env vars are absent" "$(printf '%s' "$out" | head -2)"
    fi
done

echo
if [ "$FAIL" -eq 0 ]; then
    log_info "✅ $PASS passed."
else
    log_error "❌ $FAIL failed, $PASS passed."
fi
exit $(( FAIL > 0 ? 1 : 0 ))
