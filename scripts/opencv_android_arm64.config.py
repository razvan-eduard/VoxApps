# Restricts the OpenCV Android SDK build to arm64-v8a only (the only ABI vox-vision ships), matching
# this repo's minSdk/compileSdk. Used by scripts/build_opencv_android.sh via `--config`.
ANDROID_NATIVE_API_LEVEL = int(os.environ.get('ANDROID_NATIVE_API_LEVEL', 29))
cmake_common_vars = {
    'ANDROID_COMPILE_SDK_VERSION': os.environ.get('ANDROID_COMPILE_SDK_VERSION', 36),
    'ANDROID_TARGET_SDK_VERSION': os.environ.get('ANDROID_TARGET_SDK_VERSION', 36),
    'ANDROID_MIN_SDK_VERSION': os.environ.get('ANDROID_MIN_SDK_VERSION', ANDROID_NATIVE_API_LEVEL),
    'ANDROID_GRADLE_PLUGIN_VERSION': '8.7.3',
    'GRADLE_VERSION': '8.9',
    'KOTLIN_PLUGIN_VERSION': '2.1.0',
}
ABIs = [
    ABI("3", "arm64-v8a", None, ndk_api_level=ANDROID_NATIVE_API_LEVEL, cmake_vars=cmake_common_vars),
]
