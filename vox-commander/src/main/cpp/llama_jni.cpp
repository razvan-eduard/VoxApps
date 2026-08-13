// JNI bridge for the local LLM engine (llama.cpp, CPU backend, static ggml).
//
// Threading contract: LocalLlmInterpreter's Kotlin-side Mutex serializes the calls that touch a
// handle. Native locking covers only what that Mutex cannot: the model is also released on memory
// pressure, which arrives whenever the platform decides and not through the call path the Mutex
// guards. A handle therefore carries a retirement gate — free marks it retiring, asks any running
// generation to abort, and waits for it to leave before tearing the context down.
//
// Cancellation: cancel() flips a per-handle atomic that both the per-token loop and ggml's abort
// callback observe, so a cancel lands mid-graph-eval, not just between tokens. A cancelled
// completion returns null (the Kotlin side maps that to cancellation); real failures throw.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "common.h"
#include "json-schema-to-grammar.h"
#include <nlohmann/json.hpp>

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Two KV sequences per context: grammar-constrained NLU calls own one, free-text raw-prompt
// calls (satellite hooks) own the other. Their prompts share no prefix, so under a single
// sequence each kind of call evicted the other's resident prompt and repaid its full prefill on
// every alternation; separate sequences make the longest-common-prefix reuse hold per kind.
constexpr int N_SLOTS = 2;

struct LlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    // Per-slot tokens currently resident in the KV as a decoded prefix. Kept so a repeated
    // system prompt costs one decode of the user tail, not a re-prefill of the whole template —
    // the generic longest-common-prefix reuse llama-server does, template-agnostic on purpose.
    std::vector<llama_token> cached[N_SLOTS];
    std::atomic<bool> abort{false};
    int n_batch = 0;

    // Retirement gate. Freeing a handle is not the caller's decision alone: Android trims memory
    // whenever it likes, so the release can land while a generation is still running on another
    // thread. Cancelling only asks that thread to stop — it has to be given the chance to notice,
    // or the context is torn down under a call still reading from it.
    std::mutex gate;
    std::condition_variable idle;
    int  in_flight = 0;     // guarded by gate
    bool retiring  = false; // guarded by gate
};

/**
 * Marks a handle busy for as long as a native call is using it, and refuses one that is already
 * being retired. Every entry point that touches the model or the context takes one; free waits for
 * the count to reach zero before tearing anything down.
 */
struct HandleUse {
    LlamaHandle * h;
    bool ok = false;

    explicit HandleUse(LlamaHandle * handle) : h(handle) {
        if (!h) return;
        std::lock_guard<std::mutex> lock(h->gate);
        if (h->retiring) return;
        h->in_flight++;
        ok = true;
    }

    ~HandleUse() {
        if (!ok) return;
        std::lock_guard<std::mutex> lock(h->gate);
        if (--h->in_flight == 0) h->idle.notify_all();
    }

    HandleUse(const HandleUse &) = delete;
    HandleUse & operator=(const HandleUse &) = delete;
};

std::once_flag backend_once;

void throw_runtime(JNIEnv * env, const std::string & msg) {
    LOGE("%s", msg.c_str());
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// The template buffer contract of llama_chat_apply_template: returns the needed size, which can
// exceed the buffer given — call, resize, call again.
std::string apply_template(llama_model * model, const std::string & sys, const std::string & user) {
    const char * tmpl = llama_model_chat_template(model, /*name*/ nullptr);
    llama_chat_message msgs[2] = {
        { "system", sys.c_str() },
        { "user",   user.c_str() },
    };
    const size_t n_msg = sys.empty() ? 1 : 2;
    const llama_chat_message * first = sys.empty() ? &msgs[1] : &msgs[0];

    std::vector<char> buf(sys.size() + user.size() + 1024);
    int32_t n = llama_chat_apply_template(tmpl, first, n_msg, /*add_ass*/ true, buf.data(), (int32_t) buf.size());
    if (n < 0) return "";
    if ((size_t) n > buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, first, n_msg, true, buf.data(), (int32_t) buf.size());
        if (n < 0) return "";
    }
    return std::string(buf.data(), n);
}

// llama_batch_get_one pins everything to sequence 0, so slot-aware decoding builds its batches
// explicitly: position = index in the slot's sequence, logits only for the prompt's final token.
bool decode_range(LlamaHandle * h, const std::vector<llama_token> & tokens, size_t from, int slot) {
    llama_batch batch = llama_batch_init(h->n_batch, 0, 1);
    for (size_t i = from; i < tokens.size(); i += h->n_batch) {
        const size_t n = std::min((size_t) h->n_batch, tokens.size() - i);
        common_batch_clear(batch);
        for (size_t j = 0; j < n; j++) {
            const size_t idx = i + j;
            common_batch_add(batch, tokens[idx], (llama_pos) idx, { (llama_seq_id) slot },
                             idx == tokens.size() - 1);
        }
        if (llama_decode(h->ctx, batch) != 0 || h->abort.load()) {
            llama_batch_free(batch);
            return false;
        }
    }
    llama_batch_free(batch);
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeLoadModel(
        JNIEnv * env, jobject, jstring jpath, jint nCtx, jint nThreads, jint nGpuLayers) {
    std::call_once(backend_once, [] { llama_backend_init(); });

    const std::string path = jstr(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    // 0 = CPU only, negative = every layer on the GPU. Set explicitly in both directions: the
    // library compiles the Vulkan backend in unconditionally, and the default must not decide
    // which silicon runs a model the caller's verdict machinery already ruled on.
    mparams.n_gpu_layers = (int32_t) nGpuLayers;
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        throw_runtime(env, "failed to load model: " + path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) nCtx;
    cparams.n_seq_max = N_SLOTS;
    // Without this the context is divided evenly between the sequences — n_ctx / n_seq_max cells
    // each — so asking for two slots silently halves what any single call can hold. The slots exist
    // to keep two prompt kinds resident, not to partition capacity: a short classification and a
    // receipt-sized document have nothing in common except that whichever runs should have the
    // whole pool available to it. Unified, they share it and the eviction check below is what keeps
    // them from overlapping.
    cparams.kv_unified = true;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        throw_runtime(env, "failed to create context (n_ctx=" + std::to_string(nCtx) + ")");
        return 0;
    }

    auto * h = new LlamaHandle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    h->n_batch = (int) llama_n_batch(ctx);
    llama_set_abort_callback(ctx, [](void * ud) { return static_cast<LlamaHandle *>(ud)->abort.load(); }, h);

    LOGI("model loaded: %s (n_ctx=%d threads=%d)", path.c_str(), nCtx, nThreads);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) return;
    {
        std::unique_lock<std::mutex> lock(h->gate);
        if (h->retiring) return;   // a concurrent free already owns the teardown
        h->retiring = true;
        // Ask any running generation to stop, then wait for it to actually leave. Setting the flag
        // without waiting is what let the teardown run underneath a call still copying from the
        // context; the abort callback and the per-token loop both poll this.
        h->abort.store(true);
        h->idle.wait(lock, [h] { return h->in_flight == 0; });
    }
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

JNIEXPORT jstring JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeJsonSchemaToGrammar(JNIEnv * env, jobject, jstring jschema) {
    try {
        const auto schema = nlohmann::ordered_json::parse(jstr(env, jschema));
        const std::string grammar = json_schema_to_grammar(schema);
        if (grammar.empty()) {
            throw_runtime(env, "schema converted to an empty grammar");
            return nullptr;
        }
        return env->NewStringUTF(grammar.c_str());
    } catch (const std::exception & e) {
        throw_runtime(env, std::string("json_schema_to_grammar failed: ") + e.what());
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeComplete(
        JNIEnv * env, jobject, jlong handle,
        jstring jsys, jstring juser, jstring jgrammar, jint maxTokens, jfloat temperature,
        jint jslot) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    HandleUse use(h);
    if (h && !use.ok) return nullptr;   // being retired; the caller reads this as a cancellation
    if (!h) {
        throw_runtime(env, "complete() on a freed handle");
        return nullptr;
    }
    const int slot = (jslot >= 0 && jslot < N_SLOTS) ? (int) jslot : 0;
    h->abort.store(false);
    llama_perf_context_reset(h->ctx); // timings answer for this call, not the context's lifetime

    const std::string prompt = apply_template(h->model, jstr(env, jsys), jstr(env, juser));
    if (prompt.empty()) {
        throw_runtime(env, "chat template produced no prompt");
        return nullptr;
    }

    std::vector<llama_token> tokens = common_tokenize(h->vocab, prompt, /*add_special*/ true, /*parse_special*/ true);
    // Against the per-sequence capacity, which is what a single call actually gets — not the
    // context total. Measuring the total is how an oversized prompt used to pass this check and
    // then fail inside llama_decode with nothing said about why.
    const int capacity = (int) llama_n_ctx_seq(h->ctx);
    if (tokens.empty() || (int) tokens.size() >= capacity - maxTokens) {
        throw_runtime(env, "prompt does not fit the context (" + std::to_string(tokens.size()) +
                           " tokens, capacity " + std::to_string(capacity) +
                           ", reserving " + std::to_string(maxTokens) + " for the reply)");
        return nullptr;
    }

    // The slots share one KV pool of n_ctx cells. When this call plus the other slot's resident
    // prefix cannot coexist, the other slot is evicted — it repays its prefill next time, which
    // is exactly what every call paid before slots existed; this call proceeding matters more.
    const int other = 1 - slot;
    if (tokens.size() + (size_t) maxTokens + h->cached[other].size() >= (size_t) llama_n_ctx(h->ctx)) {
        llama_memory_seq_rm(llama_get_memory(h->ctx), (llama_seq_id) other, -1, -1);
        h->cached[other].clear();
    }

    // Reuse whatever prefix is already resident; at least the final token must be re-decoded so
    // sampling has fresh logits.
    size_t lcp = 0;
    while (lcp < tokens.size() && lcp < h->cached[slot].size() && tokens[lcp] == h->cached[slot][lcp]) lcp++;
    if (lcp == tokens.size()) lcp = tokens.size() - 1;
    llama_memory_seq_rm(llama_get_memory(h->ctx), (llama_seq_id) slot, (llama_pos) lcp, -1);
    h->cached[slot].clear(); // repopulated only on success — a failed decode leaves an honest empty cache

    if (!decode_range(h, tokens, lcp, slot)) {
        if (h->abort.load()) return nullptr;
        throw_runtime(env, "prompt decode failed");
        return nullptr;
    }

    const std::string grammar = jstr(env, jgrammar);
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler * gs = llama_sampler_init_grammar(h->vocab, grammar.c_str(), "root");
        if (!gs) {
            llama_sampler_free(chain);
            throw_runtime(env, "grammar failed to parse");
            return nullptr;
        }
        llama_sampler_chain_add(chain, gs);
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(1.0f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    bool cancelled = false;
    llama_pos pos = (llama_pos) tokens.size();
    llama_batch gbatch = llama_batch_init(1, 0, 1);
    for (int i = 0; i < maxTokens; i++) {
        if (h->abort.load()) { cancelled = true; break; }
        llama_token tok = llama_sampler_sample(chain, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        out += common_token_to_piece(h->ctx, tok, /*special*/ false);
        common_batch_clear(gbatch);
        common_batch_add(gbatch, tok, pos++, { (llama_seq_id) slot }, true);
        if (llama_decode(h->ctx, gbatch) != 0) {
            if (!h->abort.load()) {
                llama_batch_free(gbatch);
                llama_sampler_free(chain);
                throw_runtime(env, "decode failed mid-generation");
                return nullptr;
            }
            cancelled = true;
            break;
        }
    }
    llama_batch_free(gbatch);
    llama_sampler_free(chain);

    if (cancelled) return nullptr;
    h->cached[slot] = std::move(tokens); // prompt only: the next call's seq_rm drops generated tokens
    return env->NewStringUTF(out.c_str());
}

// [prompt-eval ms, prompt tokens, decode ms, decoded tokens] for the most recent completion —
// the benchmark's seam for reporting prefill and decode speed separately.
JNIEXPORT jlongArray JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeLastTimings(JNIEnv * env, jobject, jlong handle) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    HandleUse use(h);
    if (!use.ok || !h->ctx) return nullptr;
    const llama_perf_context_data d = llama_perf_context(h->ctx);
    const jlong vals[4] = {
        (jlong) d.t_p_eval_ms, (jlong) d.n_p_eval,
        (jlong) d.t_eval_ms,   (jlong) d.n_eval,
    };
    jlongArray arr = env->NewLongArray(4);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 4, vals);
    return arr;
}

// [free, total] bytes on the first GPU device ggml reports, or null when there is none. Answers
// a question the compatibility probe deliberately does not: the probe says whether GPU inference
// *works* on this device — one small model, one sentinel, no opinion about size — while this says
// whether a *particular* model has room. A driver that runs a 1MB model perfectly will still fault
// on a 2GB one, and the two facts belong to different questions.
//
// On a unified-memory phone GPU the budget is a share of system RAM and the figure is advisory,
// not a guarantee: treat it as the input to a warning, never as permission.
JNIEXPORT jlongArray JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeGpuMemory(JNIEnv * env, jobject) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) != GGML_BACKEND_DEVICE_TYPE_GPU) continue;
        size_t free_bytes = 0, total_bytes = 0;
        ggml_backend_dev_memory(dev, &free_bytes, &total_bytes);
        const jlong vals[2] = { (jlong) free_bytes, (jlong) total_bytes };
        jlongArray arr = env->NewLongArray(2);
        if (!arr) return nullptr;
        env->SetLongArrayRegion(arr, 0, 2, vals);
        return arr;
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h) h->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeClearMemory(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    HandleUse use(h);
    if (!use.ok || !h->ctx) return;
    llama_memory_clear(llama_get_memory(h->ctx), /*data*/ true);
    for (auto & c : h->cached) c.clear();
}

JNIEXPORT jint JNICALL
Java_com_voxapps_llamacpp_LlamaBridgeImpl_nativeContextTokenCount(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    HandleUse use(h);
    if (!use.ok || !h->ctx) return 0;
    jint total = 0;
    for (int slot = 0; slot < N_SLOTS; slot++) {
        total += (jint) (llama_memory_seq_pos_max(llama_get_memory(h->ctx), (llama_seq_id) slot) + 1);
    }
    return total;
}

} // extern "C"
