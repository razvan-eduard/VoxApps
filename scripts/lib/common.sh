#!/bin/bash
# Sourced by scripts/vox and by every script under scripts/. Holds what 14 of them were each
# carrying their own copy of: colours, log functions, and the project root.
#
# It is deliberately tiny and dependency-free. These scripts run inside Gradle at build time, so
# anything slow or networked here would be paid on every compile.
#
# Sourcing this does NOT make a script dependent on being launched by `vox` — every script under
# scripts/ must still run correctly when invoked directly. The dispatcher routes and documents; it
# never initialises anything a child needs.

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { printf "${GREEN}%s${NC}\n" "$1"; }
log_warn()  { printf "${YELLOW}%s${NC}\n" "$1"; }
log_error() { printf "${RED}%s${NC}\n" "$1"; }
log_blue()  { printf "${BLUE}%s${NC}\n" "$1"; }

# Resolves from this file's own location, so it is correct whether the caller is scripts/vox,
# a script in scripts/, a Gradle Exec task with its own working directory, or a CI step.
VOX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_ROOT="${PROJECT_ROOT:-$VOX_ROOT}"

# Where key material lives on a developer machine — deliberately outside the repository, since
# `git clean -xfd` deletes gitignored files and losing the schema key costs an app release.
#
# Resolved rather than hardcoded to "$HOME/.voxapps": these scripts run under bash, but bash runs on
# Linux and on Windows (Git Bash / WSL) as well as macOS, and "the home directory" is not the same
# question on each. XDG first because that is the Linux convention, then $HOME, then Windows'
# %USERPROFILE% for a shell that did not set HOME. VOX_KEY_DIR overrides the lot.
if [ -n "${VOX_KEY_DIR:-}" ]; then
    :
elif [ -n "${XDG_CONFIG_HOME:-}" ] && [ -d "${XDG_CONFIG_HOME}/voxapps" ]; then
    VOX_KEY_DIR="${XDG_CONFIG_HOME}/voxapps"
elif [ -n "${HOME:-}" ]; then
    VOX_KEY_DIR="${HOME}/.voxapps"
elif [ -n "${USERPROFILE:-}" ]; then
    VOX_KEY_DIR="${USERPROFILE}/.voxapps"
else
    VOX_KEY_DIR=".voxapps"
fi

# --- Where the Android toolchain lives ---------------------------------------------------------
#
# Resolved rather than assumed. These scripts run on a developer's machine as much as on a runner —
# vox-commander's preBuild calls check_whisper.sh on every build — and the SDK sits somewhere
# different on each platform. Hardcoding one of them means the other two cannot build the app, and
# means a workflow has to fake the expected layout with symlinks before the script will run.
#
# Order: what the environment says, then each platform's default. ANDROID_HOME and ANDROID_SDK_ROOT
# are what Gradle, the SDK manager and android-actions/setup-android all set.

vox_android_sdk() {
    local candidate
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
                     "$HOME/Library/Android/sdk" \
                     "$HOME/Android/Sdk" \
                     "${LOCALAPPDATA:-}/Android/Sdk" \
                     "/usr/local/lib/android/sdk"; do
        [ -n "$candidate" ] && [ -d "$candidate" ] && { printf '%s' "$candidate"; return 0; }
    done
    return 1
}

# The newest installed NDK, or the one the environment names. `find -L`: on a runner the SDK path
# can be a tree of symlinks, and an unfollowed find matches nothing.
vox_android_ndk() {
    local sdk latest candidate
    for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
        [ -n "$candidate" ] && [ -d "$candidate" ] && { printf '%s' "$candidate"; return 0; }
    done
    sdk=$(vox_android_sdk) || return 1
    if [ -d "$sdk/ndk" ]; then
        latest=$(find -L "$sdk/ndk" -maxdepth 1 -mindepth 1 -type d -exec basename {} \; 2>/dev/null \
                 | sort -V | tail -1)
        [ -n "$latest" ] && { printf '%s' "$sdk/ndk/$latest"; return 0; }
    fi
    [ -d "$sdk/ndk-bundle" ] && { printf '%s' "$sdk/ndk-bundle"; return 0; }
    return 1
}

# Where a header-only or tool package is installed. Homebrew is one answer among several, and it is
# absent on the Linux runners that also build this.
vox_prefix_for() {
    local package="$1" binary="${2:-}" prefix
    if prefix=$(brew --prefix "$package" 2>/dev/null) && [ -n "$prefix" ]; then
        printf '%s' "$prefix"; return 0
    fi
    if [ -n "$binary" ] && command -v "$binary" >/dev/null 2>&1; then
        printf '%s' "$(cd "$(dirname "$(command -v "$binary")")/.." && pwd)"; return 0
    fi
    for prefix in /usr/local /usr /opt/homebrew; do
        [ -d "$prefix/include" ] && { printf '%s' "$prefix"; return 0; }
    done
    printf '%s' "/usr/local"
}

# sha256 of a file, on either coreutils or macOS.
vox_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}
