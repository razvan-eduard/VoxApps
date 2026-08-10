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

# --report: key=value on stdout for sync-newpipe-extractor.yml, human logging on stderr.
source "$(dirname "$0")/lib/upstream_report.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOML_FILE="$PROJECT_ROOT/gradle/libs.versions.toml"

# 1. Get current version from TOML file (NewPipeExtractor tags carry a "v" prefix, e.g. "v0.24.8")
CURRENT_VERSION=$(grep "^newpipeExtractor =" "$TOML_FILE" | grep -oE "v[0-9]+\.[0-9]+\.[0-9]+")

if [ -z "$CURRENT_VERSION" ]; then
    log_error "❌ Could not find NewPipeExtractor version in libs.versions.toml"
    exit 1
fi

log_blue "🔍 Checking NewPipeExtractor version (via JitPack)..."
log_info "Current version: $CURRENT_VERSION"

# 2. Fetch latest version from JitPack
# head -1: JitPack's build-info JSON carries both "version" and "latestOk", and an unanchored grep
# matches both — a two-line value that sync-newpipe-extractor.yml already guarded against and this
# script did not. The drift was invisible until the script had to produce machine-readable output.
LATEST_VERSION=$(curl -s --connect-timeout 5 --max-time 10 https://jitpack.io/api/builds/com.github.teamnewpipe/NewPipeExtractor/latest | grep -oE "v?[0-9]+\.[0-9]+\.[0-9]+" | head -1)

if [ -z "$LATEST_VERSION" ]; then
    log_warn "⚠️ Could not reach JitPack API. Checking GitHub tags fallback..."
    # NewPipeExtractor isn't published on Maven Central (unlike Vosk) — fall back to GitHub's own
    # tag list directly.
    LATEST_VERSION=$(git ls-remote --tags --refs https://github.com/TeamNewPipe/NewPipeExtractor.git \
        | grep -oE "refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$" | sed 's#refs/tags/##' | sort -V | tail -1)
fi

# Normalize both to a leading "v" for comparison — JitPack's API sometimes strips it.
[[ "$CURRENT_VERSION" != v* ]] && CURRENT_VERSION="v$CURRENT_VERSION"
[[ -n "$LATEST_VERSION" && "$LATEST_VERSION" != v* ]] && LATEST_VERSION="v$LATEST_VERSION"

# 3. Final comparison
emit current "$CURRENT_VERSION"
emit latest "$LATEST_VERSION"

if [ -n "$LATEST_VERSION" ] && [ "$CURRENT_VERSION" != "$LATEST_VERSION" ]; then
    HIGHER_VERSION=$(printf "%s\n%s" "${CURRENT_VERSION#v}" "${LATEST_VERSION#v}" | sort -V | tail -1)
    HIGHER_VERSION="v$HIGHER_VERSION"

    if [ "$HIGHER_VERSION" == "$LATEST_VERSION" ]; then
        emit has_update true
        log_warn "🚀 UPDATE AVAILABLE: $CURRENT_VERSION -> $LATEST_VERSION"
        if [ "$REPORT" != true ]; then
            echo -e "\nTo update, modify your ${BLUE}gradle/libs.versions.toml${NC}:"
            grep -n "^newpipeExtractor =" "$TOML_FILE" | sed 's/^/Line /'
            echo -e "Change to: ${GREEN}newpipeExtractor = \"$LATEST_VERSION\"${NC}\n"
        fi
    else
        emit has_update false
        log_info "✅ NewPipeExtractor is up to date ($CURRENT_VERSION)."
    fi
else
    emit has_update false
    log_info "✅ NewPipeExtractor is up to date ($CURRENT_VERSION)."
fi
