#!/bin/bash
set -e

# Builds libllama.so (llama.cpp + JNI wrapper, hybrid CPU+OpenCL, static ggml) and deploys it
# beside the other jniLibs. Same shape as check_whisper.sh. The GPU inputs are repo-pinned
# submodules, not host packages: the Khronos headers compile in, and the ICD loader is
# cross-compiled once per build tree purely as the import library ggml links against — the .so
# it produces is never shipped, the device's own vendor libOpenCL.so resolves at runtime
# (declared via uses-native-library). How much runs on the GPU is decided per model load
# (n_gpu_layers through the JNI), not here.

# shellcheck source=scripts/lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LLAMA_DIR="$PROJECT_ROOT/vox-commander/src/main/cpp/llama.cpp"
WRAPPER_DIR="$PROJECT_ROOT/vox-commander/src/main/cpp/llama-build"
PROJECT_JNI_DIR="$PROJECT_ROOT/vox-commander/src/main/jniLibs/arm64-v8a"
BACKUP_DIR="$PROJECT_ROOT/scripts/.llama_backup"
BUILD_DIR="build-android"

# --- OPENCL BUILD INPUTS (repo-pinned, no host packages) ---
OPENCL_HEADERS_DIR="$PROJECT_ROOT/vendor/OpenCL-Headers"
OPENCL_ICD_DIR="$PROJECT_ROOT/vendor/OpenCL-ICD-Loader"
OPENCL_STAGE_DIR="$WRAPPER_DIR/$BUILD_DIR-opencl-stub"

FORCE_REBUILD=false
MANUAL_UPGRADE=false
for arg in "$@"; do
    case "$arg" in
        --force-rebuild) FORCE_REBUILD=true ;;
        --upgrade) MANUAL_UPGRADE=true ;;
    esac
done

perform_rollback() {
    log_error "🚨 BUILD FAILED! Initiating automatic rollback..."

    if [ -n "$PREVIOUS_GIT_REV" ]; then
        log_warn "🔄 Rolling back llama.cpp source to revision: $PREVIOUS_GIT_REV"
        cd "$LLAMA_DIR" || exit 1
        git checkout "$PREVIOUS_GIT_REV" --quiet
    fi

    # Only this engine's binary: the directory also holds whisper's libraries, which are not ours
    # to restore.
    if [ -f "$BACKUP_DIR/libllama.so" ]; then
        log_warn "📦 Restoring previous stable libllama.so to jniLibs..."
        mkdir -p "$PROJECT_JNI_DIR"
        cp "$BACKUP_DIR/libllama.so" "$PROJECT_JNI_DIR/"
    fi

    log_info "✅ Rollback complete. Application remains functional at previous stable state."
    exit 1
}

# --- 1. PRE-CHECK & SOURCE SNAPSHOT ---
if [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
    log_blue "🔄 Missing llama.cpp sources. Initializing submodule..."
    # -C "$PROJECT_ROOT": the pathspec is relative to the repo root and Gradle runs this script
    # from the module directory (same trap check_whisper.sh documents).
    git -C "$PROJECT_ROOT" submodule update --init --recursive "vox-commander/src/main/cpp/llama.cpp"
fi

cd "$LLAMA_DIR" || exit 1
PREVIOUS_GIT_REV=$(git rev-parse HEAD)

git fetch --tags > /dev/null 2>&1
# llama.cpp releases are b-number tags, not semver.
LATEST_STABLE_TAG=$(git tag -l "b[0-9]*" | sort -V | tail -1)
CURRENT_HEAD_TAG=$(git describe --tags --exact-match 2>/dev/null || echo "not-a-tag")

UPGRADE_TRIGGERED=false

if [ "$LATEST_STABLE_TAG" != "$CURRENT_HEAD_TAG" ] && [ "$LATEST_STABLE_TAG" != "" ]; then
    if [ "$MANUAL_UPGRADE" = true ]; then
        log_info "🚀 Manual upgrade requested. Switching to $LATEST_STABLE_TAG..."
        git checkout "$LATEST_STABLE_TAG" --quiet
        UPGRADE_TRIGGERED=true
    elif [ -t 0 ]; then
        log_warn "🆕 NEW RELEASE AVAILABLE: $LATEST_STABLE_TAG (You are on: $CURRENT_HEAD_TAG)"
        printf '%s❓ Do you want to upgrade llama.cpp and rebuild? (y/n): %s' "$YELLOW" "$NC"
        read -r REPLY
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            log_info "🚀 Upgrading source to tag $LATEST_STABLE_TAG..."
            git checkout "$LATEST_STABLE_TAG" --quiet
            UPGRADE_TRIGGERED=true
        fi
    else
        log_warn "🆕 NOTE: New llama.cpp release $LATEST_STABLE_TAG is available. (You are on: $CURRENT_HEAD_TAG)"
        log_warn "💡 To upgrade, run this script manually from a terminal: ./scripts/check_llama.sh --upgrade"
    fi
fi

# --- 2. BINARY SNAPSHOT ---
if [ -f "$PROJECT_JNI_DIR/libllama.so" ]; then
    log_blue "📸 Creating safety backup of current libllama.so..."
    mkdir -p "$BACKUP_DIR"
    cp "$PROJECT_JNI_DIR/libllama.so" "$BACKUP_DIR/"
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
    rm -rf "${WRAPPER_DIR:?}/${BUILD_DIR:?}"
fi

mkdir -p "$WRAPPER_DIR/$BUILD_DIR"
cd "$WRAPPER_DIR/$BUILD_DIR" || exit 1

# --- 3a. OPENCL IMPORT STUB (once per build tree) ---
# find_package(OpenCL) inside ggml wants a library file to exist at configure time, so the ICD
# loader is cross-compiled first into its own tree. It is an import library only: never deployed,
# never hashed, never shipped — the device's vendor driver is what actually answers at runtime.
OPENCL_STUB_LIB="$OPENCL_STAGE_DIR/libOpenCL.so"
if [ ! -f "$OPENCL_STUB_LIB" ]; then
    log_info "⚙️ Building the OpenCL ICD loader (link stub, arm64)..."
    cmake -S "$OPENCL_ICD_DIR" -B "$OPENCL_STAGE_DIR" \
      -DCMAKE_TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-33 \
      -DCMAKE_BUILD_TYPE=Release \
      -DOPENCL_ICD_LOADER_HEADERS_DIR="$OPENCL_HEADERS_DIR" \
      -DBUILD_TESTING=OFF
    cmake --build "$OPENCL_STAGE_DIR" --config Release -j 8
    [ -f "$OPENCL_STUB_LIB" ] || { log_error "❌ ICD loader stub did not produce libOpenCL.so"; exit 1; }
fi

if [ ! -f "CMakeCache.txt" ]; then
    log_info "⚙️ Configuring hybrid build (CPU + OpenCL/Adreno, static ggml, no OpenMP)..."
    if ! cmake .. \
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
LIB_LLAMA=$(find . -name "libllama.so" | head -1)

# The JNI export is the check: the wrapper is built with hidden visibility, so Java_* symbols are
# exactly what must survive — an .so that lost them loads and then does nothing.
if [ -f "$LIB_LLAMA" ] && nm -D "$LIB_LLAMA" | grep -q "Java_com_voxapps_llamacpp"; then
    log_info "✅ Integrity check passed. Deploying..."
    mkdir -p "$PROJECT_JNI_DIR"
    cp "$LIB_LLAMA" "$PROJECT_JNI_DIR/"
    # Stripped at deploy time: this library ships as a release asset, so it never passes through
    # AGP's strip task — unstripped it is ~60 MB of debug symbols around ~8 MB of code. The dynamic
    # (Java_*) symbols survive; the unstripped original stays in the build directory for
    # symbolication.
    STRIP_TOOL=$(find "$NDK_PATH/toolchains/llvm/prebuilt" -name "llvm-strip" | head -1)
    if [ -n "$STRIP_TOOL" ]; then
        "$STRIP_TOOL" --strip-unneeded "$PROJECT_JNI_DIR/libllama.so"
    fi
    log_info "🎉 build successful and deployed ($(du -h "$PROJECT_JNI_DIR/libllama.so" | cut -f1) stripped)."

    # --- 5. PUBLISH TO GITHUB RELEASES (DLC) ---
    # Only publish when a rebuild actually happened (upgrade or force-rebuild)
    if [ "$UPGRADE_TRIGGERED" = true ] || [ "$FORCE_REBUILD" = true ]; then
        log_blue "📦 Publishing updated libs to GitHub releases (DLC)..."
        if "$PROJECT_ROOT/scripts/publish_llama_libs.sh"; then
            log_info "✅ Libs published to GitHub releases."
        else
            log_warn "⚠️ Failed to publish libs to GitHub releases. DLC download may be outdated."
            log_warn "   You can publish manually: ./scripts/vox release publish-llama-libs"
        fi
    fi
else
    log_error "❌ Integrity check FAILED!"
    perform_rollback
fi
