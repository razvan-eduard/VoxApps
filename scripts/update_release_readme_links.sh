#!/bin/bash
set -euo pipefail

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

# Regenerates the "Build Status" table in README.md (between the LATEST_RELEASES:START/END
# markers) from the repo's actual GitHub Releases — never hand-edit tag numbers or APK sizes there,
# they go stale the moment any app's version is bumped (the APK size in particular used to be a
# hand-maintained snapshot in a separate table and drifted badly, e.g. Vox Vision sitting at a
# stale ~54 MB in README prose for several releases after R8 minification actually brought it down
# to ~4 MB — fetching the real published asset size here removes that whole class of staleness).
# Run manually (`./scripts/update_release_readme_links.sh`) or via
# `.github/workflows/update-readme-releases.yml`, which runs this on every `release: published`
# event so the table can't drift from what's actually published. Requires `gh` (authenticated) and
# `jq`.

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
README_FILE="$PROJECT_ROOT/README.md"
REPO="razvan-eduard/VoxApps"

# app-prefix (tag prefix, also vox-<prefix> module dir) : Display Name : Vox<PascalCase> asset prefix
# Single source of truth for this mapping lives here — a new satellite app needs one new line.
APPS="
commander:Vox Commander:VoxCommander
calendar:Vox Calendar:VoxCalendar
expenses:Vox Expenses:VoxExpenses
notes:Vox Notes:VoxNotes
vision:Vox Vision:VoxVision
hub:Vox Hub:VoxHub
"

log_blue "🔍 Fetching releases from $REPO..."
# --slurp (+ a separate jq, since gh rejects --slurp together with --jq) rather than --paginate
# --jq: with --jq, gh applies the filter to EACH PAGE, so once this repo passed 100 releases the
# command started emitting one array per page instead of a single array. Everything downstream then
# ran twice — and for any prefix whose match lived on the other page, `... | last` yielded null,
# which `.assets[]` promptly failed on with "Cannot iterate over null". --slurp returns an array of
# per-page arrays, hence `.[][]` to flatten. `.assets // []` is belt-and-braces for a release caught
# mid-(re)creation.
ALL_RELEASES_JSON=$(gh api "repos/$REPO/releases" --paginate --slurp \
    | jq '[.[][] | select(.draft==false) | {tag: .tag_name, published_at: .published_at, prerelease: .prerelease, assets: [(.assets // [])[] | {name: .name, size: .size, created_at: .created_at}]}]')

TABLE_ROWS=""
MISSING=""

while IFS=':' read -r prefix display asset_prefix; do
    [ -z "$prefix" ] && continue

    # Latest published release whose tag matches "<prefix>-v*" exactly (not a substring match —
    # "commander-v0.6-beta" must not match a hypothetical "commander-vision-v1" prefix collision).
    LATEST=$(echo "$ALL_RELEASES_JSON" | jq -r --arg p "$prefix" '
        [.[] | select(.tag | test("^" + $p + "-v"))] | sort_by(.published_at) | last
    ')

    if [ "$LATEST" = "null" ] || [ -z "$LATEST" ]; then
        MISSING="$MISSING $prefix"
        continue
    fi

    TAG=$(echo "$LATEST" | jq -r '.tag')
    PRERELEASE=$(echo "$LATEST" | jq -r '.prerelease')
    ASSET_NAME="${asset_prefix}-${TAG}.apk"
    ASSET_URL="https://github.com/$REPO/releases/download/$TAG/${ASSET_NAME}"
    RELEASE_URL="https://github.com/$REPO/releases/tag/$TAG"
    BADGE_URL="https://github.com/$REPO/actions/workflows/release-${prefix}.yml/badge.svg"
    WORKFLOW_URL="https://github.com/$REPO/actions/workflows/release-${prefix}.yml"

    LABEL="$TAG"
    if [ "$PRERELEASE" = "true" ]; then
        LABEL="$TAG (pre-release)"
    fi

    # Real published asset size, straight from the GitHub Release — not a hand-maintained snapshot
    # that can silently drift out of date (see the header comment above for why that mattered).
    SIZE_BYTES=$(echo "$LATEST" | jq -r --arg name "$ASSET_NAME" '.assets[] | select(.name == $name) | .size')
    if [ -n "$SIZE_BYTES" ] && [ "$SIZE_BYTES" != "null" ]; then
        SIZE_LABEL="$(awk -v b="$SIZE_BYTES" 'BEGIN { printf "%.1f MB", b / 1048576 }')"
    else
        SIZE_LABEL="—"
    fi

    # When this APK was actually produced: the upload time of the asset itself, not the release's
    # published_at. They usually agree, but a release that gets deleted and recreated for a fresh
    # publish date (which every publish here does) moves published_at while the APK is unchanged,
    # and a dispatched re-publish of an existing version moves it without a new build at all. The
    # asset's own created_at is the one timestamp that tracks the build.
    BUILT_AT=$(echo "$LATEST" | jq -r --arg name "$ASSET_NAME" '.assets[] | select(.name == $name) | .created_at')
    [ -z "$BUILT_AT" ] || [ "$BUILT_AT" = "null" ] && BUILT_AT=$(echo "$LATEST" | jq -r '.published_at')
    if [ -n "$BUILT_AT" ] && [ "$BUILT_AT" != "null" ]; then
        # Sliced rather than passed through `date`: GNU and BSD date parse ISO-8601 with different
        # flags, and this script runs on both a macOS laptop and an Ubuntu runner.
        BUILT_LABEL="${BUILT_AT:0:10} ${BUILT_AT:11:5} UTC"
    else
        BUILT_LABEL="—"
    fi

    TABLE_ROWS="${TABLE_ROWS}| **${display}** | [![Build](${BADGE_URL})](${WORKFLOW_URL}) | [\`${LABEL}\`](${RELEASE_URL}) | ${BUILT_LABEL} | ${SIZE_LABEL} | [Download APK](${ASSET_URL}) |
"
done <<< "$APPS"

if [ -n "$MISSING" ]; then
    log_warn "⚠️  No published release found for:$MISSING (skipped in the table)"
fi

GENERATED_AT=$(date -u +"%Y-%m-%d")
BLOCK_FILE=$(mktemp)
trap 'rm -f "$BLOCK_FILE"' EXIT
cat > "$BLOCK_FILE" <<EOF
<!-- LATEST_RELEASES:START -->
<!-- Auto-generated by scripts/update_release_readme_links.sh — do not hand-edit. Regenerated on
     every GitHub release (see .github/workflows/update-readme-releases.yml). Last updated ${GENERATED_AT}. -->

| App | Build | Latest tag | Built (UTC) | APK size | Install |
|-----|-------|-----------|-------------|----------|---------|
${TABLE_ROWS}
<!-- LATEST_RELEASES:END -->
EOF

if ! grep -q "LATEST_RELEASES:START" "$README_FILE"; then
    log_error "❌ README.md has no <!-- LATEST_RELEASES:START --> marker — add it manually once, this script only replaces what's between the markers."
    exit 1
fi

# Portable in-place replace between markers (a temp-file + getline, not `awk -v` with an embedded
# multi-line string, which several awk implementations — including macOS's — mishandle: works with
# both GNU and BSD/macOS awk, unlike sed -i's differing -i syntax across platforms too).
awk -v blockfile="$BLOCK_FILE" '
    /<!-- LATEST_RELEASES:START -->/ {
        while ((getline line < blockfile) > 0) print line
        skip=1
        next
    }
    /<!-- LATEST_RELEASES:END -->/ { skip=0; next }
    !skip { print }
' "$README_FILE" > "$README_FILE.tmp" && mv "$README_FILE.tmp" "$README_FILE"

log_info "✅ README.md's Build Status table updated."
