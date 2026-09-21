#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <android/log.h>
#include <memory>
#include <cstring>
#include <algorithm>
#include <thread>

#include "llama.h"

#define LOG_TAG "ReshiftLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct NativeLlamaSession {
    std::string model_path;
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;

    NativeLlamaSession(const std::string& path) : model_path(path) {}

    ~NativeLlamaSession() {
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_free_model(model);
            model = nullptr;
        }
    }
};

static std::once_flag g_llama_init_flag;

static void ensure_llama_backend_initialized() {
    std::call_once(g_llama_init_flag, []() {
        llama_backend_init();
        LOGI("llama_backend_init() successfully executed.");
    });
}

static void safe_call_progress(JNIEnv *env, jobject j_callback, jmethodID cb_method, int tokens, const std::string& text) {
    if (!j_callback || !cb_method) return;
    jstring j_str = env->NewStringUTF(text.c_str());
    if (j_str) {
        env->CallVoidMethod(j_callback, cb_method, tokens, j_str);
        env->DeleteLocalRef(j_str);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_ox_fzer0x_snakeloader_ai_OnDeviceLlmEngine_nativeInitModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring j_model_path,
        jboolean use_gpu
) {
    if (!j_model_path) return 0;

    const char *path_cstr = env->GetStringUTFChars(j_model_path, nullptr);
    if (!path_cstr) return 0;

    std::string path(path_cstr);
    env->ReleaseStringUTFChars(j_model_path, path_cstr);

    ensure_llama_backend_initialized();

    auto session = std::make_unique<NativeLlamaSession>(path);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = use_gpu ? 99 : 0; // Offload 99 layers if GPU requested, 0 for CPU NEON

    LOGI("Loading llama.cpp model from file: %s (Backend: %s)", path.c_str(), use_gpu ? "Vulkan GPU (n_gpu_layers=99)" : "CPU ARM64 NEON");
    session->model = llama_load_model_from_file(path.c_str(), model_params);
    if (!session->model) {
        LOGE("Failed to load llama.cpp model from %s", path.c_str());
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 4096; // Extended context window size (4096 tokens)
    ctx_params.n_batch = 512; // Max batch size
    ctx_params.n_threads = std::max(1, (int)std::thread::hardware_concurrency() - 1);
    ctx_params.n_threads_batch = ctx_params.n_threads;

    session->ctx = llama_new_context_with_model(session->model, ctx_params);
    if (!session->ctx) {
        LOGE("Failed to create llama.cpp context for model %s", path.c_str());
        return 0;
    }

    LOGI("llama.cpp model and context successfully initialized! Address: %p", session.get());
    return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT jstring JNICALL
Java_ox_fzer0x_snakeloader_ai_OnDeviceLlmEngine_nativeGenerate(
        JNIEnv *env,
        jobject /* thiz */,
        jlong context_ptr,
        jstring j_prompt,
        jstring j_system_instruction,
        jint max_tokens,
        jfloat temperature,
        jobject j_callback
) {
    if (context_ptr == 0) {
        jclass exClass = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exClass, "NativeLlamaSession pointer is null");
        return nullptr;
    }

    auto *session = reinterpret_cast<NativeLlamaSession *>(context_ptr);
    if (!session || !session->model || !session->ctx) {
        jclass exClass = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exClass, "NativeLlamaSession is invalid or model uninitialized");
        return nullptr;
    }

    jmethodID cb_method = nullptr;
    if (j_callback) {
        jclass cb_class = env->GetObjectClass(j_callback);
        if (cb_class) {
            cb_method = env->GetMethodID(cb_class, "onProgress", "(ILjava/lang/String;)V");
        }
    }

    const char *prompt_cstr = j_prompt ? env->GetStringUTFChars(j_prompt, nullptr) : "";
    const char *sys_cstr = j_system_instruction ? env->GetStringUTFChars(j_system_instruction, nullptr) : "";

    std::string prompt(prompt_cstr ? prompt_cstr : "");
    std::string sys(sys_cstr ? sys_cstr : "");

    if (j_prompt && prompt_cstr) env->ReleaseStringUTFChars(j_prompt, prompt_cstr);
    if (j_system_instruction && sys_cstr) env->ReleaseStringUTFChars(j_system_instruction, sys_cstr);

    // Clear KV cache from any previous generation runs on this context
    llama_kv_cache_clear(session->ctx);

    // Build Chat Format Prompt
    std::string full_prompt;
    if (!sys.empty()) {
        full_prompt += "<|im_start|>system\n" + sys + "<|im_end|>\n";
    }
    full_prompt += "<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n";

    LOGI("Starting real llama.cpp token inference... Prompt len: %zu", full_prompt.length());

    // Tokenize Prompt
    const int n_prompt_max = static_cast<int>(full_prompt.length()) + 128;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(session->model, full_prompt.c_str(), static_cast<int>(full_prompt.length()), tokens.data(), static_cast<int>(tokens.size()), true, true);

    if (n_tokens < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("// Error: Tokenization failed in llama.cpp");
    }

    tokens.resize(n_tokens);

    // Cap prompt tokens to fit context window (4096 - max_tokens)
    const int effective_max_tokens = (max_tokens > 0) ? std::min(max_tokens, 2048) : 2048;
    const int max_allowed_prompt_tokens = 4096 - effective_max_tokens - 32;
    if (n_tokens > max_allowed_prompt_tokens) {
        LOGI("Prompt tokens (%d) exceed max allowed context (%d). Truncating prompt tokens.", n_tokens, max_allowed_prompt_tokens);
        n_tokens = max_allowed_prompt_tokens;
        tokens.resize(n_tokens);
    }

    // Chunked Batch Decoding Loop (Batch size = 512)
    const int n_batch = 512;
    for (int i = 0; i < n_tokens; i += n_batch) {
        int n_eval = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n_eval, i, 0);

        if (j_callback && cb_method) {
            std::string status_msg = "Evaluating prompt tokens (" + std::to_string(i + n_eval) + "/" + std::to_string(n_tokens) + ")...";
            safe_call_progress(env, j_callback, cb_method, -1, status_msg);
        }

        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode failed at batch chunk position %d", i);
            return env->NewStringUTF("// Error: Prompt chunk decoding failed in llama.cpp");
        }
    }

    std::string generated_result;
    int cur_generated = 0;
    int limit_tokens = effective_max_tokens;
    llama_token eos_token = llama_token_eos(session->model);
    std::vector<llama_token> last_tokens;
    last_tokens.reserve(64);

    while (cur_generated < limit_tokens) {
        // Sample next token
        auto * logits = llama_get_logits_ith(session->ctx, -1);
        int n_vocab = llama_n_vocab(session->model);

        std::vector<llama_token_data> candidates;
        candidates.reserve(n_vocab);
        for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
            candidates.push_back(llama_token_data{token_id, logits[token_id], 0.0f});
        }

        llama_token_data_array candidates_p = { candidates.data(), candidates.size(), false };

        // Apply sampling chain: Repetition Penalty -> Top-K -> Top-P -> Temperature
        if (!last_tokens.empty()) {
            llama_sample_repetition_penalties(session->ctx, &candidates_p, last_tokens.data(), last_tokens.size(), 1.1f, 0.0f, 0.0f);
        }
        llama_sample_top_k(session->ctx, &candidates_p, 40, 1);
        llama_sample_top_p(session->ctx, &candidates_p, 0.95f, 1);

        llama_token new_token;
        if (temperature > 0.0f) {
            llama_sample_temp(session->ctx, &candidates_p, temperature);
            new_token = llama_sample_token(session->ctx, &candidates_p);
        } else {
            new_token = llama_sample_token_greedy(session->ctx, &candidates_p);
        }

        if (new_token == eos_token) {
            LOGI("EOS token reached after %d tokens", cur_generated);
            break;
        }

        if (last_tokens.size() >= 64) {
            last_tokens.erase(last_tokens.begin());
        }
        last_tokens.push_back(new_token);

        if (new_token == eos_token) {
            LOGI("EOS token reached after %d tokens", cur_generated);
            break;
        }

        char piece_buf[256];
        int n_chars = llama_token_to_piece(session->model, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_chars > 0) {
            generated_result.append(piece_buf, n_chars);
        }

        cur_generated++;

        // Report real-time streaming progress to Java/Kotlin every 2 tokens
        if (j_callback && cb_method && (cur_generated % 2 == 0 || cur_generated == 1)) {
            safe_call_progress(env, j_callback, cb_method, cur_generated, generated_result);
        }

        // Prepare batch for next single token
        llama_batch batch = llama_batch_get_one(&new_token, 1, n_tokens + cur_generated, 0);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode failed during token generation step %d", cur_generated);
            break;
        }
    }

    if (j_callback && cb_method) {
        safe_call_progress(env, j_callback, cb_method, cur_generated, generated_result);
    }

    LOGI("llama.cpp inference complete! Generated %d tokens (%zu bytes)", cur_generated, generated_result.size());
    return env->NewStringUTF(generated_result.c_str());
}

JNIEXPORT void JNICALL
Java_ox_fzer0x_snakeloader_ai_OnDeviceLlmEngine_nativeFreeModel(
        JNIEnv *env,
        jobject /* thiz */,
        jlong context_ptr
) {
    if (context_ptr != 0) {
        auto *session = reinterpret_cast<NativeLlamaSession *>(context_ptr);
        LOGI("Freeing NativeLlamaSession at address: %p", session);
        delete session;
    }
}

} // extern "C"
