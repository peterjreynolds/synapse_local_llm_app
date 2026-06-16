#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <string>

#include "common.h"
#include "llama.h"
#include "logging.h"
#include "sampling.h"

constexpr int N_THREADS_MIN = 2;
constexpr int N_THREADS_MAX = 6;
constexpr int N_THREADS_HEADROOM = 2;
constexpr int CONTEXT_SIZE = 4096;
constexpr int OVERFLOW_HEADROOM = 4;
constexpr int BATCH_SIZE = 512;

static llama_model * g_model = nullptr;
static llama_context * g_context = nullptr;
static llama_batch g_batch;
static common_sampler * g_sampler = nullptr;
static llama_pos g_current_position = 0;
static int g_remaining_tokens = 0;
static std::string g_cached_token_chars;

static void reset_prompt_state() {
    g_current_position = 0;
    g_remaining_tokens = 0;
    g_cached_token_chars.clear();
    if (g_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

static void reset_sampler(const float temperature) {
    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    common_params_sampling sampler_params;
    sampler_params.temp = temperature;
    g_sampler = common_sampler_init(g_model, sampler_params);
}

static int decode_tokens(
        const llama_tokens & tokens,
        const llama_pos start_position,
        const bool compute_last_logit) {
    for (int token_index = 0; token_index < (int) tokens.size(); token_index += BATCH_SIZE) {
        const int current_batch_size = std::min((int) tokens.size() - token_index, BATCH_SIZE);
        common_batch_clear(g_batch);

        if (start_position + token_index + current_batch_size >= CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGe("Prompt exceeds embedded llama.cpp context.");
            return 1;
        }

        for (int batch_index = 0; batch_index < current_batch_size; batch_index++) {
            const int absolute_index = token_index + batch_index;
            const llama_token token_id = tokens[absolute_index];
            const llama_pos position = start_position + absolute_index;
            const bool want_logit = compute_last_logit && absolute_index == (int) tokens.size() - 1;
            common_batch_add(g_batch, token_id, position, {0}, want_logit);
        }

        if (llama_decode(g_context, g_batch) != 0) {
            LOGe("llama_decode failed while processing prompt.");
            return 2;
        }
    }

    return 0;
}

static bool is_valid_utf8(const char * string) {
    if (string == nullptr) {
        return true;
    }

    const auto * bytes = (const unsigned char *) string;
    int width;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            width = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            width = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            width = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            width = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int index = 1; index < width; ++index) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }

    return true;
}

extern "C"
JNIEXPORT void JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_init(
        JNIEnv * env,
        jobject /* unused */,
        jstring native_library_directory) {
    llama_log_set(synapse_android_log_callback, nullptr);

    const auto * backend_path = env->GetStringUTFChars(native_library_directory, nullptr);
    ggml_backend_load_all_from_path(backend_path);
    env->ReleaseStringUTFChars(native_library_directory, backend_path);

    llama_backend_init();
    LOGi("Embedded llama.cpp backend initialized.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_load(
        JNIEnv * env,
        jobject /* unused */,
        jstring model_path_value) {
    llama_model_params model_params = llama_model_default_params();
    const auto * model_path = env->GetStringUTFChars(model_path_value, nullptr);
    auto * model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(model_path_value, model_path);

    if (model == nullptr) {
        return 1;
    }

    g_model = model;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_prepare(
        JNIEnv * /* env */,
        jobject /* unused */) {
    if (g_model == nullptr) {
        return 1;
    }

    const int online_processors = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int thread_count = std::max(
            N_THREADS_MIN,
            std::min(N_THREADS_MAX, online_processors - N_THREADS_HEADROOM));

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = CONTEXT_SIZE;
    context_params.n_batch = BATCH_SIZE;
    context_params.n_ubatch = BATCH_SIZE;
    context_params.n_threads = thread_count;
    context_params.n_threads_batch = thread_count;

    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        return 2;
    }

    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    reset_sampler(0.7f);
    reset_prompt_state();
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_systemInfo(
        JNIEnv * env,
        jobject /* unused */) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jint JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_processSystemPrompt(
        JNIEnv * env,
        jobject /* unused */,
        jstring system_prompt_value) {
    reset_prompt_state();

    const auto * system_prompt_chars = env->GetStringUTFChars(system_prompt_value, nullptr);
    const std::string system_prompt = std::string(system_prompt_chars) + "\n\n";
    env->ReleaseStringUTFChars(system_prompt_value, system_prompt_chars);

    const auto system_tokens = common_tokenize(
            g_context,
            system_prompt,
            true,
            true);

    if ((int) system_tokens.size() >= CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        return 1;
    }

    const int decode_result = decode_tokens(system_tokens, g_current_position, false);
    if (decode_result != 0) {
        return decode_result;
    }

    g_current_position += (llama_pos) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_processUserPrompt(
        JNIEnv * env,
        jobject /* unused */,
        jstring user_prompt_value,
        jint predict_length,
        jfloat temperature) {
    const auto * user_prompt_chars = env->GetStringUTFChars(user_prompt_value, nullptr);
    const std::string user_prompt = std::string(user_prompt_chars);
    env->ReleaseStringUTFChars(user_prompt_value, user_prompt_chars);

    reset_sampler(temperature);

    const auto user_tokens = common_tokenize(
            g_context,
            user_prompt,
            false,
            true);

    if ((int) user_tokens.size() >= CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        return 1;
    }

    const int decode_result = decode_tokens(user_tokens, g_current_position, true);
    if (decode_result != 0) {
        return decode_result;
    }

    g_current_position += (llama_pos) user_tokens.size();
    g_remaining_tokens = predict_length;
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_generateNextToken(
        JNIEnv * env,
        jobject /* unused */) {
    if (g_remaining_tokens <= 0) {
        return nullptr;
    }
    if (g_current_position >= CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("Embedded context is full.");
        return nullptr;
    }

    const auto token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, token_id, true);

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token_id)) {
        return nullptr;
    }

    common_batch_clear(g_batch);
    common_batch_add(g_batch, token_id, g_current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("llama_decode failed while generating token.");
        return nullptr;
    }

    g_current_position++;
    g_remaining_tokens--;

    const auto token_chars = common_token_to_piece(g_context, token_id);
    g_cached_token_chars += token_chars;

    if (is_valid_utf8(g_cached_token_chars.c_str())) {
        const auto token_text = env->NewStringUTF(g_cached_token_chars.c_str());
        g_cached_token_chars.clear();
        return token_text;
    }

    return env->NewStringUTF("");
}

extern "C"
JNIEXPORT void JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_unload(
        JNIEnv * /* env */,
        jobject /* unused */) {
    reset_prompt_state();

    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_context != nullptr) {
        llama_batch_free(g_batch);
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_app_synapse_localllm_data_runtime_embedded_EmbeddedLlamaEngine_shutdown(
        JNIEnv * /* env */,
        jobject /* unused */) {
    llama_backend_free();
}
