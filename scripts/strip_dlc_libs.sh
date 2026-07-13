#!/bin/bash
set -e

# Strips a set of native libraries out of an already-built, unsigned APK's zip directly, instead
# of relying on AGP's packaging.jniLibs.excludes — which is unreliable on arm64-v8a for Commander's
# dependency set (confirmed across AGP 9.0.0-9.2.1, see build.gradle.kts's release packaging
# comment / docs/BUILD_TIME_DEPENDENCIES.md). Plain zip removal sidesteps that bug: the libs
# themselves always build correctly, it's specifically AGP's exclude-at-merge-time step that isn't
# trustworthy.
#
# Usage: strip_dlc_libs.sh <unsigned.apk> <output-signed.apk> <keystore> <keystore-password> <key-alias> <dlc-libs-staging-dir> <native-libs-source-dir> lib1.so [lib2.so ...]
#
# native-libs-source-dir is where the *un-excluded* .so files are copied FROM for upload (they must
# still exist there since nothing tells AGP to drop them from the merge/build intermediates — only
# from the final APK zip, which is what this script does).

if [ "$#" -lt 8 ]; then
    echo "Usage: $0 <unsigned.apk> <output-signed.apk> <keystore> <keystore-password> <key-alias> <dlc-libs-staging-dir> <native-libs-source-dir> lib1.so [lib2.so ...]" >&2
    exit 1
fi

UNSIGNED_APK="$1"; shift
OUTPUT_APK="$1"; shift
KEYSTORE="$1"; shift
KEYSTORE_PASSWORD="$1"; shift
KEY_ALIAS="$1"; shift
DLC_LIBS_DIR="$1"; shift
NATIVE_LIBS_SOURCE="$1"; shift
LIBS=("$@")

BUILD_TOOLS="$(dirname "$(command -v aapt2 2>/dev/null || find "${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools" -maxdepth 1 -type d | sort -V | tail -1)")"
if [ ! -x "$BUILD_TOOLS/zipalign" ]; then
    # aapt2 lookup failed (not on PATH) — fall back to the newest installed build-tools directory.
    BUILD_TOOLS="$(find "${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools" -maxdepth 1 -type d | sort -V | tail -1)"
fi

mkdir -p "$DLC_LIBS_DIR"
for lib in "${LIBS[@]}"; do
    src="$NATIVE_LIBS_SOURCE/$lib"
    if [ ! -f "$src" ]; then
        echo "ERROR: expected DLC lib not found at $src" >&2
        exit 1
    fi
    cp "$src" "$DLC_LIBS_DIR/"
done
echo "Staged for upload:"
ls -lh "$DLC_LIBS_DIR/"

STRIPPED_APK="${OUTPUT_APK%.apk}-stripped-unsigned.apk"
ALIGNED_APK="${OUTPUT_APK%.apk}-aligned-unsigned.apk"
cp "$UNSIGNED_APK" "$STRIPPED_APK"

STRIP_PATHS=()
for lib in "${LIBS[@]}"; do
    STRIP_PATHS+=("lib/arm64-v8a/$lib")
done
zip -d "$STRIPPED_APK" "${STRIP_PATHS[@]}"

"$BUILD_TOOLS/zipalign" -p 4 "$STRIPPED_APK" "$ALIGNED_APK"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" \
    --key-pass "pass:$KEYSTORE_PASSWORD" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$OUTPUT_APK" \
    "$ALIGNED_APK"

"$BUILD_TOOLS/apksigner" verify "$OUTPUT_APK"
echo "Stripped + signed APK: $OUTPUT_APK ($(du -h "$OUTPUT_APK" | cut -f1))"

rm -f "$STRIPPED_APK" "$ALIGNED_APK"
