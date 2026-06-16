#pragma once

#include "llama.h"

#include <android/log.h>

#define LOG_TAG "SynapseLlama"

#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGd(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static void synapse_android_log_callback(
        enum ggml_log_level level,
        const char * text,
        void * /* user_data */) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGe("%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGw("%s", text);
    } else if (level == GGML_LOG_LEVEL_INFO) {
        LOGi("%s", text);
    } else {
        LOGd("%s", text);
    }
}
