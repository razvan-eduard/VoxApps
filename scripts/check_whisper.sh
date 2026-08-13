#!/bin/bash
set -e

# --- COLOR DEFINITIONS ---
# Colours, logging and PROJECT_ROOT — shared, not re-declared per script.
# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# --- BASIC PATHS ---
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WHISPER_DIR="$PROJECT_ROOT/vox-commander/src/main/cpp/whisper.cpp"
PROJECT_JNI_DIR="$PROJECT_ROOT/vox-commander/src/main/jniLibs/arm64-v8a"
BACKUP_DIR="$PROJECT_ROOT/scripts/.whisper_backup"
BUILD_DIR="build-android-hybrid"

# --- ARGUMENT PARSING ---
FORCE_REBUILD=false
MANUAL_UPGRADE=false
for arg in "$@"; do
    case "$arg" in
        --force-rebuild) FORCE_REBUILD=true ;;
        --upgrade) MANUAL_UPGRADE=true ;;
    esac
done

# --- OPENCL BUILD INPUTS (repo-pinned, no host packages) ---
# Same contract as check_llama.sh: the headers come from a pinned submodule and the import
# library is the static dlopen shim, built once per build tree and never shipped.
OPENCL_HEADERS_DIR="$PROJECT_ROOT/vendor/OpenCL-Headers"
OPENCL_SHIM_DIR="$PROJECT_ROOT/vox-commander/src/main/cpp/opencl-shim"
OPENCL_STAGE_DIR="$WHISPER_DIR/$BUILD_DIR-opencl-stub"

# --- ROLLBACK FUNCTION ---
perform_rollback() {
    log_error "🚨 BUILD FAILED! Initiating automatic rollback..."

    if [ -n "$PREVIOUS_GIT_REV" ]; then
        log_warn "🔄 Rolling back Whisper.cpp source to revision: $PREVIOUS_GIT_REV"
        cd "$WHISPER_DIR" || exit 1
        git checkout "$PREVIOUS_GIT_REV" --quiet
    fi

    if [ -d "$BACKUP_DIR" ] && [ "$(ls -A "$BACKUP_DIR")" ]; then
        log_warn "📦 Restoring previous stable binaries to jniLibs..."
        mkdir -p "$PROJECT_JNI_DIR"
        cp "$BACKUP_DIR"/*.so "$PROJECT_JNI_DIR/"
    fi

    log_info "✅ Rollback complete. Application remains functional at previous stable state."
    exit 1
}

# --- 1. PRE-CHECK & SOURCE SNAPSHOT ---
if [ ! -f "$WHISPER_DIR/CMakeLists.txt" ]; then
    log_blue "🔄 Missing Whisper sources. Initializing submodule..."
    # -C "$PROJECT_ROOT": the pathspec is relative to the repo root, and Gradle runs this script
    # from the module directory — so an unqualified call looked for vox-commander/vox-commander/…
    # and died on "pathspec did not match any file(s)". It never showed until a machine without the
    # submodule ran it, because everywhere else already had the sources.
    git -C "$PROJECT_ROOT" submodule update --init --recursive "vox-commander/src/main/cpp/whisper.cpp"
fi

cd "$WHISPER_DIR" || exit 1
PREVIOUS_GIT_REV=$(git rev-parse HEAD)

# Check for official STABLE releases (Tags)
git fetch --tags > /dev/null 2>&1
LATEST_STABLE_TAG=$(git tag -l "v*" | sort -V | tail -1)
CURRENT_HEAD_TAG=$(git describe --tags --exact-match 2>/dev/null || echo "not-a-tag")

UPGRADE_TRIGGERED=false

if [ "$LATEST_STABLE_TAG" != "$CURRENT_HEAD_TAG" ] && [ "$LATEST_STABLE_TAG" != "" ]; then
    if [ "$MANUAL_UPGRADE" = true ]; then
        log_info "🚀 Manual upgrade requested. Switching to $LATEST_STABLE_TAG..."
        git checkout "$LATEST_STABLE_TAG" --quiet
        UPGRADE_TRIGGERED=true
    elif [ -t 0 ]; then
        # Running in a real terminal, we can ask
        log_warn "🆕 NEW STABLE RELEASE AVAILABLE: $LATEST_STABLE_TAG (You are on: $CURRENT_HEAD_TAG)"
        printf '%s❓ Do you want to upgrade Whisper.cpp and rebuild? (y/n): %s' "$YELLOW" "$NC"
        read -r REPLY
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            log_info "🚀 Upgrading source to stable tag $LATEST_STABLE_TAG..."
            git checkout "$LATEST_STABLE_TAG" --quiet
            UPGRADE_TRIGGERED=true
        fi
    else
        # Running in Android Studio / Non-interactive
        log_warn "🆕 NOTE: New stable Whisper.cpp release $LATEST_STABLE_TAG is available. (You are on: $CURRENT_HEAD_TAG)"
        log_warn "💡 To upgrade, run this script manually from a terminal: ./scripts/check_whisper.sh --upgrade"
    fi
fi

# --- 2. BINARY SNAPSHOT ---
if [ -d "$PROJECT_JNI_DIR" ] && [ "$(ls -A "$PROJECT_JNI_DIR")" ]; then
    log_blue "📸 Creating safety backup of current .so libraries..."
    mkdir -p "$BACKUP_DIR"
    cp "$PROJECT_JNI_DIR"/*.so "$BACKUP_DIR/"
fi

# --- 3. BUILD EXECUTION ---
NDK_PATH=$(vox_android_ndk) || {
    log_error "❌ No Android NDK found."
    log_error "   Set ANDROID_NDK_HOME, or install an NDK under \$ANDROID_HOME/ndk."
    exit 1
}

if [ "$UPGRADE_TRIGGERED" = true ] || [ "$FORCE_REBUILD" = true ]; then
    log_warn "🔥 Cleaning build directory..."
    # :? — refuse to run at all rather than delete from / if either is somehow empty.
    rm -rf "${WHISPER_DIR:?}/${BUILD_DIR:?}"
fi

mkdir -p "$WHISPER_DIR/$BUILD_DIR"
cd "$WHISPER_DIR/$BUILD_DIR" || exit 1

# --- OPENCL IMPORT STUB (once per build tree) ---
OPENCL_STUB_LIB="$OPENCL_STAGE_DIR/libOpenCL.a"
if [ ! -f "$OPENCL_STUB_LIB" ]; then
    log_info "⚙️ Building the OpenCL dlopen shim (static import library, arm64)..."
    # Static, not the real loader: a linked loader lands in DT_NEEDED and a device without the
    # vendor driver then refuses to load the engine library itself. The shim resolves the driver
    # with dlopen at first use and reports zero platforms when there is none.
    cmake -S "$OPENCL_SHIM_DIR" -B "$OPENCL_STAGE_DIR" \
      -DCMAKE_TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-33 \
      -DCMAKE_BUILD_TYPE=Release \
      -DOPENCL_HEADERS_DIR="$OPENCL_HEADERS_DIR"
    cmake --build "$OPENCL_STAGE_DIR" --config Release -j 8
    [ -f "$OPENCL_STUB_LIB" ] || { log_error "❌ shim build did not produce libOpenCL.a"; exit 1; }
fi

if [ ! -f "CMakeCache.txt" ]; then
    log_info "⚙️ Configuring Hybrid Build (GPU/OpenCL support)..."
    if ! cmake ../.. \
      -DCMAKE_TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-33 \
      -DCMAKE_BUILD_TYPE=Release \
      -DOpenCL_INCLUDE_DIR="$OPENCL_HEADERS_DIR" \
      -DOpenCL_LIBRARY="$OPENCL_STUB_LIB"; then
        perform_rollback
    fi
fi

log_info "🚀 Compiling..."
if ! cmake --build . --config Release -j 8; then
    perform_rollback
fi

# --- 4. VERIFICATION & DEPLOYMENT ---
log_blue "🧪 Verifying binary integrity..."
LIB_WHISPER=$(find . -name "libwhisper.so" | head -1)

if [ -f "$LIB_WHISPER" ] && nm -D "$LIB_WHISPER" | grep -q "whisper_init"; then
    log_info "✅ Integrity check passed. Deploying..."
    mkdir -p "$PROJECT_JNI_DIR"
    cp "$LIB_WHISPER" "$PROJECT_JNI_DIR/"
    find . -name "libggml*.so" -exec cp {} "$PROJECT_JNI_DIR/" \;

    OMP_PATH=$(find "$NDK_PATH" -name "libomp.so" | grep "aarch64" | head -n 1)
    if [ -f "$OMP_PATH" ]; then
        cp "$OMP_PATH" "$PROJECT_JNI_DIR/"
    fi
    log_info "🎉 build successful and deployed."

    # --- 5. PUBLISH TO GITHUB RELEASES (DLC) ---
    # Only publish when a rebuild actually happened (upgrade or force-rebuild)
    if [ "$UPGRADE_TRIGGERED" = true ] || [ "$FORCE_REBUILD" = true ]; then
        log_blue "📦 Publishing updated libs to GitHub releases (DLC)..."
        if "$PROJECT_ROOT/scripts/publish_whisper_libs.sh"; then
            log_info "✅ Libs published to GitHub releases."
        else
            log_warn "⚠️ Failed to publish libs to GitHub releases. DLC download may be outdated."
            log_warn "   You can publish manually: ./scripts/vox release publish-libs"
        fi
    fi
else
    log_error "❌ Integrity check FAILED!"
    perform_rollback
fi
