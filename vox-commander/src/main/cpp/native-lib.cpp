#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <android/log.h>
#include "whisper.h"
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-vulkan.h"

#define LOG_TAG "LibWhisper"

// Runs a tiny matrix multiplication on the ggml-vulkan backend. This exercises the
// actual GPU compute/shader path that whisper inference relies on, catching devices
// where the backend initializes but real GPU workloads crash or produce garbage.
// NOTE: On a broken GPU this may crash the *process* natively (uncatchable), which is
// why callers must run it in an isolated process.
static bool ggml_vulkan_self_test() {
    ggml_backend_t backend = ggml_backend_vk_init(0);
    if (backend == nullptr) {
        return false;
    }

    bool ok = false;
    const int n = 4;

    ggml_init_params ip{};
    ip.mem_size = ggml_tensor_overhead() * 8 + ggml_graph_overhead() + 1024;
    ip.mem_buffer = nullptr;
    ip.no_alloc = true;

    ggml_context* ctx = ggml_init(ip);
    if (ctx != nullptr) {
        ggml_tensor* a = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, n, n);
        ggml_tensor* b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, n, n);
        ggml_tensor* c = ggml_mul_mat(ctx, a, b);

        ggml_cgraph* gf = ggml_new_graph(ctx);
        ggml_build_forward_expand(gf, c);

        ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, backend);
        if (buf != nullptr) {
            std::vector<float> av(n * n, 1.0f);
            std::vector<float> bv(n * n, 2.0f);
            ggml_backend_tensor_set(a, av.data(), 0, av.size() * sizeof(float));
            ggml_backend_tensor_set(b, bv.data(), 0, bv.size() * sizeof(float));

            const ggml_status st = ggml_backend_graph_compute(backend, gf);
            if (st == GGML_STATUS_SUCCESS) {
                std::vector<float> cv(n * n, 0.0f);
                ggml_backend_tensor_get(c, cv.data(), 0, cv.size() * sizeof(float));
                // result[i,j] = sum_k a[k,i]*b[k,j] = n * (1*2) = 8
                const float expected = static_cast<float>(n) * 2.0f;
                ok = std::isfinite(cv[0]) && std::fabs(cv[0] - expected) < 1e-2f;
                __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
                                    "Vulkan self-test: out[0]=%f expected=%f ok=%d", cv[0], expected, ok);
            } else {
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Vulkan self-test: compute status=%d", st);
            }
            ggml_backend_buffer_free(buf);
        }
        ggml_free(ctx);
    }

    ggml_backend_free(backend);
    return ok;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_runVulkanSelfTest(
        JNIEnv* env,
        jobject /* this */) {
    try {
        const bool ok = ggml_vulkan_self_test();
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "runVulkanSelfTest result=%d", ok);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "runVulkanSelfTest exception '%s'", e.what());
        return JNI_FALSE;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "runVulkanSelfTest unknown exception");
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_isVulkanAvailable(
        JNIEnv* env,
        jobject /* this */) {

    // Probe the *ggml-vulkan* backend (exactly what whisper uses de for GPU inference),
    // not just raw Vulkan presence. A device can expose a Vulkan driver while still
    // being unusable by ggml-vulkan (missing features / driver bugs), so we must
    // initialize the real backend to know whether GPU transcription will work.
    try {
        const int deviceCount = ggml_backend_vk_get_device_count();
        if (deviceCount <= 0) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Vulkan probe: ggml device count = 0");
            return JNI_FALSE;
        }

        ggml_backend_t backend = ggml_backend_vk_init(0);
        const bool usable = (backend != nullptr);
        if (backend != nullptr) {
            ggml_backend_free(backend);
        }

        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
                            "Vulkan probe: ggml deviceCount=%d backendInit=%d", deviceCount, usable);
        return usable ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Vulkan probe: exception '%s'", e.what());
        return JNI_FALSE;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Vulkan probe: unknown exception");
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath,
        jboolean useGpu) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = useGpu; // CRITICAL FIX: Respect the flag from Kotlin

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Initializing context with path: %s, useGpu: %d", path, useGpu);

    whisper_context* ctx = whisper_init_from_file_with_params(path, params);

    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx != nullptr) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Context created successfully");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to create context");
    }

    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv* env,
        jobject /* this */,
        jlong contextPtr) {

    whisper_context* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Context freed");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv* env,
        jobject /* this */,
        jlong contextPtr,
        jint numThreads,
        jfloatArray audioData,
        jstring language) {

    whisper_context* ctx = reinterpret_cast<whisper_context*>(contextPtr);

    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Context is null in fullTranscribe");
        return;
    }

    const char* lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
    }

    jfloat* audio = env->GetFloatArrayElements(audioData, nullptr);
    jsize length = env->GetArrayLength(audioData);

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Transcribing with %d threads, lang: %s, audio length: %d",
        numThreads, lang ? lang : "auto", length);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.n_threads = numThreads;
    params.language = lang;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;          // single-pass deterministic, no retry with higher temp
    params.max_len = 64;                     // voice commands are short — 64 tokens is plenty
    params.token_timestamps = false;
    params.single_segment = true;
    params.no_timestamps = true;
    params.suppress_blank = true;            // suppress blank outputs at start/end
    params.suppress_nst = true;              // suppress non-speech tokens

    int result = whisper_full(ctx, params, audio, length);

    env->ReleaseFloatArrayElements(audioData, audio, 0);
    if (language != nullptr) {
        env->ReleaseStringUTFChars(language, lang);
    }

    if (result != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Transcription failed with code: %d", result);
    } else {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Transcription completed successfully");
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv* env,
        jobject /* this */,
        jlong contextPtr) {

    whisper_context* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr) {
        return 0;
    }

    return whisper_full_n_segments(ctx);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv* env,
        jobject /* this */,
        jlong contextPtr,
        jint index) {

    whisper_context* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const char* segment = whisper_full_get_segment_text(ctx, index);
    if (segment == nullptr) {
        return env->NewStringUTF("");
    }

    return env->NewStringUTF(segment);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv* env,
        jobject /* this */,
        jlong contextPtr,
        jint index) {

    whisper_context* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr) {
        return 0;
    }

    return whisper_full_get_segment_t0(ctx, index);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv* env,
        jobject /* this */,
        jlong contextPtr,
        jint index) {

    whisper_context* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr) {
        return 0;
    }

    return whisper_full_get_segment_t1(ctx, index);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv* env,
        jobject /* this */) {

    std::string info = whisper_print_system_info();
    return env->NewStringUTF(info.c_str());
}
