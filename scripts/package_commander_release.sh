#!/bin/bash
set -euo pipefail

# One definition of "the APK Commander actually ships".
#
# `assembleRelease` does not produce it. AGP's packaging.jniLibs.excludes is unreliable on
# arm64-v8a for this dependency set, so the release APK is built fully bundled and the DLC libs are
# stripped out of the built zip afterwards — a plain, deterministic file operation. That stripping
# used to live only in release-commander.yml, which meant a locally built release APK (~40MB, every
# lib bundled) behaved differently from the published one (~16MB, libs downloaded at first launch):
# the DLC download path could not be exercised locally at all, and two bugs in it shipped.
#
# Both the workflow and Gradle's `packageReleaseApk` task call this, so there is one list of libs
# and one procedure rather than two that can drift.
#
# Usage: package_commander_release.sh <input.apk> <output.apk> <keystore> <keystore-password> [key-alias]

if [ "$#" -lt 4 ]; then
    echo "Usage: $0 <input.apk> <output.apk> <keystore> <keystore-password> [key-alias]" >&2
    exit 1
fi

INPUT_APK="$1"
OUTPUT_APK="$2"
KEYSTORE="$3"
KEYSTORE_PASSWORD="$4"
KEY_ALIAS="${5:-vox-apps}"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MERGED_LIBS_DIR="$PROJECT_ROOT/vox-commander/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/arm64-v8a"

if [ ! -f "$INPUT_APK" ]; then
    echo "❌ No release APK at $INPUT_APK — run :vox-commander:assembleRelease first." >&2
    exit 1
fi
if [ ! -f "$KEYSTORE" ]; then
    echo "❌ Keystore not found at $KEYSTORE." >&2
    echo "   Set RELEASE_KEYSTORE_PATH and RELEASE_KEYSTORE_PASSWORD (see docs/BUILD_AND_RELEASE.md)." >&2
    exit 1
fi

# Work on a copy: the input may be the signed APK a local assembleRelease produced, and stripping
# invalidates that signature anyway — this script re-signs at the end.
WORK_APK="$(dirname "$OUTPUT_APK")/.packaging-$(basename "$INPUT_APK")"
mkdir -p "$(dirname "$OUTPUT_APK")"
cp "$INPUT_APK" "$WORK_APK"

# sherpa-onnx ships two alternate native entry points; only sherpa-onnx-jni.so is actually used
# (its only external NEEDED lib is libonnxruntime.so, and the Java bindings load "sherpa-onnx-jni"
# by name). The other two are dead weight — dropped outright rather than published as DLC.
zip -q -d "$WORK_APK" \
    "lib/arm64-v8a/libsherpa-onnx-c-api.so" \
    "lib/arm64-v8a/libsherpa-onnx-cxx-api.so" 2>/dev/null || true

bash "$PROJECT_ROOT/scripts/strip_dlc_libs.sh" \
    "$WORK_APK" \
    "$OUTPUT_APK" \
    "$KEYSTORE" \
    "$KEYSTORE_PASSWORD" \
    "$KEY_ALIAS" \
    dlc-libs \
    "$MERGED_LIBS_DIR" \
    libonnxruntime.so liblitertlm_jni.so libvosk.so libsherpa-onnx-jni.so

rm -f "$WORK_APK"
