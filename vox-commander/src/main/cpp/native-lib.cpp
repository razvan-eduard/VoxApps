#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <android/log.h>
#include "whisper.h"
#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"

#define LOG_TAG "LibWhisper"

// The GPU micro-test that lived here is gone: no Kotlin caller bound it, and the
// isolated probe process answers the same question with a real model on the real
// inference path, which is the only answer worth persisting.

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
