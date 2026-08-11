#!/bin/bash
set -uo pipefail

# Installs an APK on whatever device adb sees and asks the one question no APK inspection can:
# does it survive a cold launch?
#
#     ./scripts/vox check smoke <apk> <application-id>
#
# An R8 rule that strips a JNI bridge, a wrong signature, a first-launch crash — each produces an
# APK that builds green and dies in the first seconds of real use. A live process a short while
# after launch, with an empty crash buffer, is the floor below which nothing should publish.
#
# Runs against the first adb device; in CI that is the emulator the workflow booted. An x86_64
# Android 11 emulator executes this repo's arm64-v8a libraries through the system image's ARM
# binary translation — proof of link-and-launch, not a substitute for real hardware.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
cd "$VOX_ROOT" || exit 1

APK="${1:-}"
APP_ID="${2:-}"
if [ -z "$APK" ] || [ -z "$APP_ID" ]; then
    log_error "Usage: ./scripts/vox check smoke <apk> <application-id>"
    exit 1
fi
[ -f "$APK" ] || { log_error "No such APK: $APK"; exit 1; }
command -v adb >/dev/null 2>&1 || { log_error "adb is not on PATH."; exit 1; }

# How long the app must stay alive after the cold launch. Long enough for splash-time work
# (native loads, schema reads) to run; overridable for slower targets.
WAIT_SECONDS="${VOX_SMOKE_WAIT:-30}"

log_blue "📱 Installing $(basename "$APK")..."
adb install -r "$APK" || { log_error "Install failed."; exit 1; }

adb logcat -c 2>/dev/null || true

log_blue "🚀 Launching $APP_ID..."
adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    || { log_error "Launch failed."; exit 1; }

sleep "$WAIT_SECONDS"

# A crashed app has no process — liveness is the primary signal. The crash buffer is the
# diagnosis when it is missing, and a belt for a crash the app visibly survived (a restarted
# process would answer pidof while the buffer still records the death).
if [ -z "$(adb shell pidof "$APP_ID" 2>/dev/null || true)" ]; then
    log_error "$APP_ID is no longer running ${WAIT_SECONDS}s after launch."
    adb logcat -d -b crash | tail -40
    exit 1
fi
if adb logcat -d -b crash 2>/dev/null | grep -q "$APP_ID"; then
    log_error "A crash was recorded for $APP_ID despite a live process."
    adb logcat -d -b crash | tail -40
    exit 1
fi

log_info "✅ $APP_ID is up ${WAIT_SECONDS}s after a cold launch, no crashes recorded."
