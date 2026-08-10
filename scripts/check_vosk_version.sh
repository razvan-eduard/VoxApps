#!/bin/bash

# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --report: key=value on stdout for sync-vosk.yml, human logging on stderr. See the header there.
source "$(dirname "$0")/lib/upstream_report.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOML_FILE="$PROJECT_ROOT/gradle/libs.versions.toml"

# 1. Get current version from TOML file
CURRENT_VERSION=$(grep "^vosk =" "$TOML_FILE" | grep -oE "[0-9]+\.[0-9]+\.[0-9]+")

if [ -z "$CURRENT_VERSION" ]; then
    log_error "❌ Could not find Vosk version in libs.versions.toml"
    exit 1
fi

log_blue "🔍 Checking Vosk version (via JitPack)..."
log_info "Current version: $CURRENT_VERSION"

# 2. Fetch latest version from JitPack (More up-to-date for Vosk)
# JitPack API returns versions for a GitHub repo
# head -1: JitPack's build-info JSON carries more than one version-shaped field, so an unanchored
# grep yields several lines. sync-vosk.yml already guarded this; the script had not.
LATEST_VERSION=$(curl -s --connect-timeout 5 --max-time 10 https://jitpack.io/api/builds/com.github.alphacep/vosk-android/latest | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | head -1)

if [ -z "$LATEST_VERSION" ]; then
    log_warn "⚠️ Could not reach JitPack API. Checking Maven fallback..."
    # Fallback to a wider search if JitPack fails
    LATEST_VERSION=$(curl -s --connect-timeout 5 --max-time 10 "https://search.maven.org/solrsearch/select?q=g:com.alphacephei+AND+a:vosk-android&rows=50&wt=json" \
        | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | sort -V | tail -1)
fi

# 3. Final Comparison
emit current "$CURRENT_VERSION"
emit latest "$LATEST_VERSION"

if [ -n "$LATEST_VERSION" ] && [ "$CURRENT_VERSION" != "$LATEST_VERSION" ]; then
    HIGHER_VERSION=$(printf "%s\n%s" "$CURRENT_VERSION" "$LATEST_VERSION" | sort -V | tail -1)

    if [ "$HIGHER_VERSION" == "$LATEST_VERSION" ]; then
        emit has_update true
        log_warn "🚀 UPDATE AVAILABLE: $CURRENT_VERSION -> $LATEST_VERSION"
        if [ "$REPORT" != true ]; then
            echo -e "\nTo update, modify your ${BLUE}gradle/libs.versions.toml${NC}:"
            grep -n "^vosk =" "$TOML_FILE" | sed 's/^/Line /'
            echo -e "Change to: ${GREEN}vosk = \"$LATEST_VERSION\"${NC}\n"
        fi
    else
        emit has_update false
        log_info "✅ Vosk is up to date ($CURRENT_VERSION)."
    fi
else
    emit has_update false
    log_info "✅ Vosk is up to date ($CURRENT_VERSION)."
fi
