#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <mutex>
#include <string>
#include "whisper.h"

namespace {
std::mutex g_mutex;

void log_error(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, "OriginWhisper", "%s", message);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_unvoid_originceiler_WhisperNative_transcribe(
        JNIEnv *env,
        jobject,
        jstring model_path,
        jfloatArray samples,
        jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char *path_chars = env->GetStringUTFChars(model_path, nullptr);
    std::string path(path_chars);
    env->ReleaseStringUTFChars(model_path, path_chars);

    // A whisper_context carries mutable decoder state. Reusing it worked for the
    // first utterance on this Vivo build but could deadlock inside whisper_full()
    // on the next assistant round. A fresh context makes every request isolated.
    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    whisper_context *context = whisper_init_from_file_with_params(path.c_str(), context_params);
    if (context == nullptr) {
        log_error("Could not load Whisper model");
        return env->NewStringUTF("");
    }

    const jsize sample_count = env->GetArrayLength(samples);
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "ru";
    params.translate = false;
    params.n_threads = std::max(2, static_cast<int>(threads));
    params.no_context = true;
    params.single_segment = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.no_timestamps = true;

    const int result = whisper_full(context, params, audio, sample_count);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    if (result != 0) {
        log_error("Whisper transcription failed");
        whisper_free(context);
        return env->NewStringUTF("");
    }

    std::string text;
    const int segments = whisper_full_n_segments(context);
    for (int index = 0; index < segments; ++index) {
        const char *segment = whisper_full_get_segment_text(context, index);
        if (segment != nullptr) text += segment;
    }
    whisper_free(context);
    text.erase(text.begin(), std::find_if(text.begin(), text.end(), [](unsigned char value) {
        return !std::isspace(value);
    }));
    text.erase(std::find_if(text.rbegin(), text.rend(), [](unsigned char value) {
        return !std::isspace(value);
    }).base(), text.end());
    return env->NewStringUTF(text.c_str());
}
